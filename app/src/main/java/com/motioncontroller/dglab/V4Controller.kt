package com.motioncontroller.dglab

import com.motioncontroller.relay.NetworkAddress
import com.motioncontroller.relay.RelayLog
import com.motioncontroller.relay.RelayProtocol
import com.motioncontroller.relay.V4RelayListener
import com.motioncontroller.relay.V4RelayServer
import com.motioncontroller.relay.arrayOrNull
import com.motioncontroller.relay.daemonThreadFactory
import com.motioncontroller.relay.describeThrowable
import com.motioncontroller.relay.intOrNull
import com.motioncontroller.relay.jsonObject
import com.motioncontroller.relay.jsonOf
import com.motioncontroller.relay.objOrNull
import com.motioncontroller.relay.scalarStringOrNull
import com.motioncontroller.relay.stringOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程内 V4 控制器。
 *
 * 它不是 WebSocket 客户端，而是直接挂在 [V4RelayServer] 上（中继把 APP 的 `message` 事件转给它）。
 * 职责：
 *  - APP 接入 → 发 `devices.get`，处理 `devices.snapshot|patch` / `slots.patch`，维护设备与 activeSlotId；
 *  - 强度闭环：`setTargetIntensity` 只记录目标，tick 用"APP 上报值优先、否则下发增量估计"做差，
 *    每 opIntervalMs 最多发一条 `device.op`（t=3 AddIntensity，单次 |delta| ≤ 10；目标归零走 t=7 v=0）；
 *  - 安全：急停锁存、APP 断开/中继停止归零、`device.op.clear` 清任务；
 *  - 协议日志（最近 200 条，线程安全）。
 *
 * 线程模型（审计修复）：
 *  - 所有状态字段由 [lock] 保护；ticker/中继回调/UI 方法都只在这把锁内**构建帧 + 入队**，
 *    不直接写 socket；
 *  - 真正的发送由 [OutboundWriter] 的单一写线程在锁外执行，队列有界：慢 APP 只丢自己的帧，
 *    不会拖住 tick/UI 线程，也不会让内存无界增长；
 *  - 1:N：pendingRequests 以 (clientId, reqId) 为键；`device.op` 只发给 activeSlotId 的归属 APP，
 *    归属未知时广播兜底并打日志；响应只结算对应 clientId 的 pending。
 */
internal class V4Controller(
    private val idleTimeoutMs: Long = RelayProtocol.DEFAULT_IDLE_TIMEOUT_MS,
    private val heartbeatIntervalMs: Long = RelayProtocol.DEFAULT_HEARTBEAT_MS,
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val opIntervalMs: Long = DEFAULT_OP_INTERVAL_MS,
    private val pendingTtlMs: Long = DEFAULT_PENDING_TTL_MS,
    private val writerCapacity: Int = OutboundWriter.DEFAULT_CAPACITY,
    private val writerDropThreshold: Int = OutboundWriter.DEFAULT_DROP_THRESHOLD,
    /** Phase 4A：t=0 ApppendPulseData 实验能力；默认关闭（会绕过强度闭环安全约束）。 */
    private val pulseTasksEnabled: Boolean = false,
) : V4RelayListener {

    private data class PendingKey(val clientId: String, val reqId: String)

    private data class PendingRequest(
        val method: String,
        val createdAtMs: Long,
        /** 非 null 表示这是急停命令链里的一条命令（Phase 3：A 归零 / B 归零 / clear 分别结算） */
        val estopKind: EstopCommandKind? = null,
        /** 可选覆盖 TTL（t=0 波形任务可能播放较久才返回终态） */
        val ttlMs: Long? = null,
    )

    private data class FramedRequest(val reqId: String, val method: String, val frame: JsonObject)

    /** Phase 4A：单通道 t=0 波形任务的本地状态（仅 APP 层生命周期，不代表设备物理输出）。 */
    private data class PulseTaskRecord(
        val channel: Int,
        val reqId: String,
        val state: PulseTaskState,
        val reason: String? = null,
    )

    /**
     * 单条急停命令的回执追踪（按 [EstopCommandKind] 聚合，多 APP 场景按 clientId 记录待回执集合）。
     *
     * 语义（Phase 3 修正）：只有三条命令**全部**收到成功语义回执，`DglabState.estopAppAcked` 才为 true；
     * 错误响应 / replaced / cancelled / 超时 / 断连都只会记为 UNCONFIRMED，绝不冒充"设备已停止"。
     */
    private class EstopCommandTracker {
        val pendingClients = LinkedHashSet<String>()
        var acked = false
        var unconfirmed = false
        var reason: String? = null
    }

    private val _state = MutableStateFlow(DglabState())
    val state: StateFlow<DglabState> = _state

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines

    private val lock = Any()
    private val logLock = Any()
    private val logBuffer = ArrayDeque<String>()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val registry = DglabDeviceRegistry()

    // ---- 以下字段都由 [lock] 保护 ----
    /** APP(被控方) clientId → 归属的 slotId（由该 APP 的事件/响应建立） */
    private val slotOwners = LinkedHashMap<String, String>()

    /** (clientId, reqId) → 未决 RPC；按 tick 做 TTL 清理并记录"未响应" */
    private val pendingRequests = LinkedHashMap<PendingKey, PendingRequest>()

    /** 延迟一个 tick 再发 devices.get 的 clientId（attach 后立刻发可能与 APP 初始化竞争） */
    private val pendingDevicesGet = LinkedHashSet<String>()

    private var relay: V4RelayServer? = null

    @Volatile
    private var writer: OutboundWriter? = null
    private var relayRunning = false
    private var bindHost = "127.0.0.1"
    private var displayHost = "127.0.0.1"
    private var port = 9998
    private var targetId = ""
    private var activeSlotId: String? = null
    private val appIds = LinkedHashSet<String>()

    private var targetA = 0
    private var targetB = 0
    /** 当前生效强度的估计值：下发增量时累加，APP 上报时直接覆盖 */
    private var estimateA = 0
    private var estimateB = 0
    private var reportedA: Int? = null
    private var reportedB: Int? = null
    private var emergencyStopped = false
    private var lastError: String? = null

    // 急停传递阶段（见 DglabState 注释；UI 必须区分，不能把“已发出”当成“设备已确认”）
    private var estopIssued = false
    private var estopFramesQueued = false
    private var estopAppAcked = false
    private var estopIssuedAtMs = 0L
    private var estopAckReason: String? = null
    private var lastOpResponseReason: String? = null

    /** 急停命令链逐条追踪；只在 [emergencyStopped] 期间有意义，解除/重启/停止时清空 */
    private val estopCommandTrackers = LinkedHashMap<EstopCommandKind, EstopCommandTracker>()

    /** Phase 4A：channel(0/1) → 当前 t=0 波形任务；reqId → channel 反向索引（结算 APP 终态回执） */
    private val pulseTaskByChannel = LinkedHashMap<Int, PulseTaskRecord>()
    private val pulseTaskChannelByReqId = LinkedHashMap<String, Int>()

    // ---- 连接诊断（问题4：偶发「无法连接」排查；语义见 DglabState 对应字段的 KDoc）----
    /** 当前 relay 会话内已完成的 WS 握手次数（含随后被拒的连接） */
    private var handshakeCount = 0
    private var lastHandshakeSummary: String? = null
    private var lastRejectReason: String? = null
    private var lastCloseCode: Int? = null
    private var lastCloseReason: String? = null

    /** Phase 3 诊断：每个会话只记录一次 snapshot / slots.patch 原文（确认 APP 真实上报字段） */
    private var loggedSnapshotPayload = false
    private var loggedSlotsPatchPayload = false

    private var nextReqId = 0L
    /** A/B 轮转，避免一个通道独占发送窗口 */
    private var nextChannel = 0
    private var lastOpAtNanos = 0L
    private var lastEstopWarnAtMs = 0L
    private var lastNoTargetLogAtMs = 0L
    private var lastOpsFallbackLogAtMs = 0L

    private var executor: ScheduledExecutorService? = null
    private var tickTask: ScheduledFuture<*>? = null

    private val unknownTypeFrames = AtomicLong()
    private val droppedFramesTotal = AtomicLong()
    private val lastDropLogAtMs = AtomicLong(0L)

    // ------------------------------------------------------------------ 生命周期

    /**
     * 启动中继（阻塞到绑定完成/失败）。
     *
     * **顺序保证（问题4：快速重启端口未释放）**：先 [shutdown] 旧 relay —— `WebSocketServer.stop(timeout)`
     * 为同步等待，且 stop 返回前显式等端口释放（见 relay/PortProbe）—— 之后才创建并绑定新 relay；
     * 残余竞态由 `V4RelayServer.start()` 的绑定重试（BindException 退避重试）兜底。
     *
     * TODO（遗留风险，2026-11-01 问题4）：绑定在 [lock] 内进行（改动前既有结构，加固时按最小面原则未动）。
     *  正常启动持锁 ≤3s（startLatch 上限）；叠加绑定重试后的病态最坏情况（连续绑定失败 + latch 超时）
     *  可达 ~12s，期间 tick/UI 的 `controlAvailable()`/`clientUrl()` 读取会被短暂阻塞。
     *  实测常见绑定失败毫秒级返回，暂不构成现场问题；若真机出现 UI 卡顿，下一轮把
     *  `server.start()` 移出锁（绑定/等 latch 在锁外，成功后只在锁内登记 relay + 发布状态），
     *  并处理好「绑定期间并发 start/shutdown」的互斥与回调乱序。
     */
    fun start(bindHost: String, port: Int, preferredTargetId: String? = null) {
        // 先完整停掉旧 relay（写线程排空 + 同步 stop + 端口释放等待），再绑定新 relay
        shutdown(notifyRelay = true)

        val server = V4RelayServer(this, idleTimeoutMs, heartbeatIntervalMs)
        synchronized(lock) {
            relay = server
            this.port = port
            try {
                val actualPort = server.start(bindHost, port, preferredTargetId)
                this.bindHost = bindHost
                this.displayHost = NetworkAddress.displayHostFor(bindHost)
                this.port = actualPort
                this.targetId = server.controllerId
                this.relayRunning = true
                this.lastError = null
                this.nextReqId = 0
                this.nextChannel = 0
                this.lastOpAtNanos = 0
                this.activeSlotId = null
                this.reportedA = null
                this.reportedB = null
                this.estimateA = 0
                this.estimateB = 0
                this.targetA = 0
                this.targetB = 0
                this.appIds.clear()
                this.slotOwners.clear()
                this.pendingRequests.clear()
                this.pendingDevicesGet.clear()
                this.registry.clear()
                this.estopIssued = false
                this.estopFramesQueued = false
                this.estopAppAcked = false
                this.estopIssuedAtMs = 0L
                this.estopAckReason = null
                this.lastOpResponseReason = null
                this.estopCommandTrackers.clear()
                this.pulseTaskByChannel.clear()
                this.pulseTaskChannelByReqId.clear()
                // 连接诊断按 relay 会话清零（语义见 DglabState KDoc）
                this.handshakeCount = 0
                this.lastHandshakeSummary = null
                this.lastRejectReason = null
                this.lastCloseCode = null
                this.lastCloseReason = null

                val outbound = OutboundWriter(
                    capacity = writerCapacity,
                    dropThreshold = writerDropThreshold,
                    sender = { frame, clientId -> dispatchFrame(server, frame, clientId) },
                    onDropped = ::onWriterDropped,
                    onSendFailure = { t -> log("! 发送失败 ${describeThrowable(t)}") },
                )
                writer = outbound
                outbound.start()

                val sched = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("V4Link"))
                executor = sched
                if (tickMs > 0) {
                    tickTask = sched.scheduleAtFixedRate(
                        { tick() },
                        tickMs,
                        tickMs,
                        TimeUnit.MILLISECONDS,
                    )
                }
                log("中继就绪 bind=$bindHost:$actualPort targetId=$targetId")
                log("APP 地址 ${clientUrlLocked(null)}")
                publishLocked()
            } catch (t: Throwable) {
                this.relay = null
                this.relayRunning = false
                this.targetId = ""
                this.lastError = describeThrowable(t)
                log("! 中继启动失败: ${describeThrowable(t)}")
                writer?.stop(WRITER_DRAIN_TIMEOUT_MS)
                writer = null
                executor?.shutdownNow()
                executor = null
                tickTask = null
                runCatching { server.stop() }
                publishLocked()
            }
        }
    }

    fun stop() {
        shutdown(notifyRelay = true)
    }

    /**
     * 停止中继/写线程/调度器，并尽力把归零帧写完。
     *
     * 写线程的 flush 在 [lock] 外等待，避免阻塞其它线程的状态读取。
     */
    private fun shutdown(notifyRelay: Boolean) {
        var server: V4RelayServer? = null
        var outbound: OutboundWriter? = null
        synchronized(lock) {
            server = relay
            outbound = writer
            // 中继/APP 还在时最后尽力归零（安全帧优先，清掉积压的旧指令）
            if (notifyRelay && canSendOperationsLocked()) {
                enqueueSafetyLocked(outbound)
            }
            relay = null
            writer = null
            relayRunning = false
            tickTask?.cancel(false)
            tickTask = null
            executor?.shutdownNow()
            executor = null

            appIds.clear()
            registry.clear()
            slotOwners.clear()
            pendingRequests.clear()
            pendingDevicesGet.clear()
            activeSlotId = null
            targetA = 0
            targetB = 0
            estimateA = 0
            estimateB = 0
            reportedA = null
            reportedB = null
            targetId = ""
            estopIssued = false
            estopFramesQueued = false
            estopAppAcked = false
            estopIssuedAtMs = 0L
            estopAckReason = null
            lastOpResponseReason = null
            estopCommandTrackers.clear()
            pulseTaskByChannel.clear()
            pulseTaskChannelByReqId.clear()
            nextReqId = 0
            nextChannel = 0
            lastOpAtNanos = 0
            if (server != null) {
                log("中继停止（relay 内部会先清理连接，回调不会重入控制器）")
            }
        }
        outbound?.stop(WRITER_DRAIN_TIMEOUT_MS)
        if (notifyRelay) server?.stop()
        synchronized(lock) { publishLocked() }
    }

    // ------------------------------------------------------------------ 对外控制面

    fun setTargetIntensity(channel: Int, target: Int) {
        if (channel != 0 && channel != 1) return
        val value = target.coerceIn(0, MAX_INTENSITY)
        synchronized(lock) {
            if (emergencyStopped) {
                if (value != 0) warnEstopLocked("setTargetIntensity($channel, $value)")
                return
            }
            if (channel == 0) targetA = value else targetB = value
            publishLocked()
        }
    }

    fun resetIntensity(channel: Int) {
        if (channel != 0 && channel != 1) return
        synchronized(lock) {
            setTargetLocked(channel, 0)
            setEstimateLocked(channel, 0)
            val slotId = activeSlotId
            val outbound = writer
            if (slotId != null && outbound != null && canSendOperationsLocked()) {
                val targets = resolveTargetsLocked(METHOD_DEVICE_OP, null)
                if (targets.isNotEmpty()) {
                    val request = buildRequestLocked(METHOD_DEVICE_OP, setIntensityData(slotId, channel))
                    enqueuePriorityLocked(outbound, targets, listOf(request))
                    lastOpAtNanos = System.nanoTime()
                    log("→ APP device.op 归零通道 $channel reqId=${request.reqId} targets=${targets.size}")
                }
            }
            publishLocked()
        }
    }

    fun clearAllOperate() {
        synchronized(lock) {
            val outbound = writer
            if (outbound != null && canSendOperationsLocked()) {
                val targets = resolveTargetsLocked(METHOD_DEVICE_OP_CLEAR, null)
                if (targets.isNotEmpty()) {
                    val request = buildRequestLocked(METHOD_DEVICE_OP_CLEAR, null)
                    enqueuePriorityLocked(outbound, targets, listOf(request))
                    log("→ APP device.op.clear reqId=${request.reqId} targets=${targets.size}")
                }
            }
            publishLocked()
        }
    }

    /**
     * Phase 4A：下发一条 `device.op` t=0 `AppendPulseData` 波形任务（默认关闭的实验能力）。
     *
     * 安全约束（缺一不可）：
     *  - 构造参数 [pulseTasksEnabled]=true（默认 false）；
     *  - 未急停锁存、链路可用（relay + APP + activeSlot + 设备 connected）；
     *  - [PulseTaskSpecValidator] 结构校验通过（帧数/维度/范围/时长上限）。
     *
     * 注意：帧数值语义未验证前**不允许**在人体上使用；本方法只保证“协议帧能发出”，
     * 不保证 APP/设备播放内容安全。
     *
     * @return true = 帧已入队（不代表 APP/设备确认播放）
     */
    fun startPulseTask(spec: PulseTaskSpec): Boolean {
        synchronized(lock) {
            if (!pulseTasksEnabled) {
                log("忽略 t=0 波形任务：Phase 4A 实验能力未启用（默认关闭）")
                return false
            }
            if (emergencyStopped) {
                log("拒绝 t=0 波形任务：急停锁存中")
                return false
            }
            PulseTaskSpecValidator.validate(spec)?.let {
                log("拒绝 t=0 波形任务：$it")
                return false
            }
            if (!canSendOperationsLocked()) {
                log("拒绝 t=0 波形任务：链路不可用（relay/APP/设备）")
                return false
            }
            val slotId = activeSlotId ?: return false
            val outbound = writer ?: return false
            val data = jsonObject(
                "s" to jsonOf(slotId),
                "t" to jsonOf(ACTION_APPEND_PULSE_DATA),
                "c" to jsonOf(spec.channel),
                "d" to jsonOf(spec.durationMs),
                "v" to PulseTaskSpecValidator.framesToJson(spec.frames),
                "ver" to jsonOf(spec.version.wire),
                "im" to JsonPrimitive(spec.replace),
            )
            // 连续任务可能播放到结束才回终态：TTL 至少要覆盖帧消费时间/持续时长
            val frameRuntimeMs = spec.frames.size * PULSE_FRAME_TICK_MS.toLong()
            val ttlMs = maxOf(
                PULSE_MIN_PENDING_TTL_MS,
                frameRuntimeMs + PULSE_MIN_PENDING_TTL_MS,
                spec.durationMs.toLong() + PULSE_MIN_PENDING_TTL_MS,
            ).coerceAtMost(PULSE_MAX_PENDING_TTL_MS)
            val reqId = sendRequestLocked(METHOD_DEVICE_OP, data, droppable = false, ttlMs = ttlMs)
                ?: run {
                    log("! t=0 波形任务未发出（队列已满/写线程已停止）")
                    return false
                }
            pulseTaskChannelByReqId[reqId] = spec.channel
            pulseTaskByChannel[spec.channel] = PulseTaskRecord(spec.channel, reqId, PulseTaskState.RUNNING)
            log(
                "→ APP device.op t=0 波形任务 reqId=$reqId c=${spec.channel} " +
                    "frames=${spec.frames.size} ver=${spec.version.wire} d=${spec.durationMs} " +
                    "im=${spec.replace}（仅协议层，未验证播放内容）",
            )
            publishLocked()
            return true
        }
    }

    /**
     * 停止/清除所有 t=0 波形任务（STOP / 断开 / 退出前调用）。
     *
     * 先发 `device.op.clear`（APP 侧取消任务），再清本地状态；无活动任务时不产生任何帧。
     */
    fun clearPulseTasks() {
        val hasTasks = synchronized(lock) {
            pulseTaskByChannel.isNotEmpty() || pulseTaskChannelByReqId.isNotEmpty()
        }
        if (!hasTasks) return
        log("清除 t=0 波形任务：device.op.clear + 本地状态复位")
        clearAllOperate()
        synchronized(lock) {
            pulseTaskByChannel.clear()
            pulseTaskChannelByReqId.clear()
            publishLocked()
        }
    }

    fun emergencyStop() {
        synchronized(lock) {
            emergencyStopped = true
            estopIssued = true
            estopIssuedAtMs = System.currentTimeMillis()
            estopFramesQueued = false
            estopAppAcked = false
            estopAckReason = null
            estopCommandTrackers.clear()
            // 旧急停命令链的未决请求必须一起作废：迟到的 resp 不能结算新链（Phase 3 审计 B2）
            pendingRequests.entries.removeAll { it.value.estopKind != null }
            // Phase 4A：急停时波形任务必须立即失效（clear 帧随命令链一起下发）
            cancelPulseTasksLocked("emergency_stop")
            targetA = 0
            targetB = 0
            estimateA = 0
            estimateB = 0
            val outbound = writer
            val slotId = activeSlotId
            if (outbound != null && slotId != null && canSendOperationsLocked()) {
                val targets = resolveTargetsLocked(METHOD_DEVICE_OP, null)
                if (targets.isNotEmpty()) {
                    // 命令链必须可逐条结算：A 归零 / B 归零 / clear 各自有独立 reqId 与回执状态
                    val frames = listOf(
                        buildRequestLocked(METHOD_DEVICE_OP, setIntensityData(slotId, 0)) to
                            EstopCommandKind.A_ZERO,
                        buildRequestLocked(METHOD_DEVICE_OP, setIntensityData(slotId, 1)) to
                            EstopCommandKind.B_ZERO,
                        buildRequestLocked(METHOD_DEVICE_OP_CLEAR, null) to
                            EstopCommandKind.CLEAR,
                    )
                    val queued = enqueueEstopLocked(outbound, targets, frames)
                    estopFramesQueued = queued > 0
                    log("急停下发 A/B 归零 + clear（targets=${targets.size}，queued=$queued）")
                } else {

                    log("! 急停：没有可下发的 APP 目标")
                }
            } else {
                log("! 急停：链路不可用，无法下发归零（中继/APP/设备不满足）")
            }
            lastOpAtNanos = System.nanoTime()
            log("急停锁存：A/B 归零 + device.op.clear（需显式解除）")
            publishLocked()
        }
    }

    fun clearEmergencyStop() {
        synchronized(lock) {
            if (!emergencyStopped) return
            emergencyStopped = false
            estopIssued = false
            estopFramesQueued = false
            estopAppAcked = false
            estopIssuedAtMs = 0L
            estopAckReason = null
            estopCommandTrackers.clear()
            log("急停已解除（目标强度保持 0，需重新 setTargetIntensity）")
            publishLocked()
        }
    }

    fun controlAvailable(): Boolean = synchronized(lock) { controlAvailableLocked() }

    fun clientUrl(hostOverride: String?): String = synchronized(lock) { clientUrlLocked(hostOverride) }

    fun isRelayIdle(): Boolean = synchronized(lock) { relay?.isIdle == true }

    // ------------------------------------------------------------------ 中继回调

    override fun onAppAttached(clientId: String) {
        synchronized(lock) {
            val hadApps = appIds.isNotEmpty()
            appIds.add(clientId)
            // 每个新 APP 都视为"新会话"：旧连接可能只是半开还没被探测到（自动重连的常见路径）。
            // 若保留旧 registry/activeSlotId，device.op 会用旧 APP 的 slotId 下发 → 必须清空。
            resetSessionLocked(clientId)
            if (hadApps) {
                log("APP 接入 clientId=$clientId（旧会话状态重置：registry/activeSlot/estimate/pendingDevicesGet）")
            } else {
                log("APP 接入 clientId=$clientId")
            }
            publishLocked()
            // APP 接入后延迟一个 tick 再请求设备列表：APP 收到首帧后可能立刻 reset 状态，
            // 过早的 RPC 会与它的初始化竞争（真机教训）。
        }
    }

    override fun onAppDisconnected(clientId: String) {
        synchronized(lock) {
            pendingDevicesGet.remove(clientId)
            // 该 APP 手里未回执的急停命令：断连后不可能再得到正确回执，必须显式记为 UNCONFIRMED
            val droppedEstopKinds = pendingRequests
                .filterKeys { it.clientId == clientId }
                .values.mapNotNull { it.estopKind }
            pendingRequests.keys.removeAll { it.clientId == clientId }
            slotOwners.entries.removeAll { it.value == clientId }
            val removed = appIds.remove(clientId)
            if (appIds.isEmpty()) {
                // 断连必须归零：APP 端强度状态未知，重连后从 0 重新累积
                registry.clear()
                slotOwners.clear()
                activeSlotId = null
                targetA = 0
                targetB = 0
                estimateA = 0
                estimateB = 0
                reportedA = null
                reportedB = null
                pendingRequests.clear()
                pendingDevicesGet.clear()
                log("APP 全部断开：控制器归零、清空设备")
            } else if (removed) {
                log("APP 断开 clientId=$clientId（剩余 ${appIds.size}）")
            }
            if (droppedEstopKinds.isNotEmpty() && emergencyStopped) {
                for (kind in droppedEstopKinds) {
                    val tracker = estopCommandTrackers[kind] ?: continue
                    tracker.pendingClients.remove(clientId)
                    if (tracker.pendingClients.isEmpty() && !tracker.acked) {
                        tracker.unconfirmed = true
                        tracker.reason = "disconnected"
                    }
                }
                refreshEstopAggregateLocked()
            }
            if (pulseTaskByChannel.isNotEmpty() || pulseTaskChannelByReqId.isNotEmpty()) {
                cancelPulseTasksLocked("disconnected")
            }
            publishLocked()
        }
    }

    override fun onAppMessage(clientId: String, data: JsonElement) {
        val obj = data as? JsonObject
        if (obj == null) {
            val count = unknownTypeFrames.incrementAndGet()
            log("忽略非对象消息 clientId=$clientId（累计 $count）")
            return
        }
        log("← ${RelayProtocol.describeFrame(obj)}")
        synchronized(lock) {
            when (val type = obj.stringOrNull("t")) {
                "ev" -> handleEventLocked(clientId, obj)
                "resp" -> handleResponseLocked(clientId, obj)
                null -> handleUntypedLocked(clientId, obj)
                else -> {
                    val count = unknownTypeFrames.incrementAndGet()
                    log("忽略消息类型 t=$type（累计 $count）")
                }
            }
        }
    }

    override fun onControllerIdle() {
        log("idle_timeout：无 APP 接入已超过 ${idleTimeoutMs / 1000}s（单机形态不主动断开，等待 APP 重连）")
    }

    // ---- 连接诊断回调（问题4）：只记录 + 发布，不影响会话/安全状态 ----

    override fun onHandshakeSummary(summary: String) {
        synchronized(lock) {
            handshakeCount += 1
            lastHandshakeSummary = summary
            publishLocked()
        }
    }

    override fun onConnectionRejected(reason: String) {
        synchronized(lock) {
            lastRejectReason = reason
            publishLocked()
        }
    }

    override fun onAppClosed(clientId: String, code: Int, reason: String, remote: Boolean) {
        synchronized(lock) {
            lastCloseCode = code
            lastCloseReason = reason
            publishLocked()
        }
    }

    override fun onRelayLog(line: String) {
        log(line)
    }

    // ------------------------------------------------------------------ 强度闭环

    private fun tick() {
        try {
            synchronized(lock) {
                dispatchPendingDevicesGetLocked()
                prunePendingLocked()
                if (!controlAvailableLocked()) return
                val now = System.nanoTime()
                val interval = opIntervalMs.coerceAtLeast(1L) * 1_000_000L
                if (lastOpAtNanos != 0L && now - lastOpAtNanos < interval) return
                repeat(2) {
                    val channel = nextChannel
                    nextChannel = 1 - nextChannel
                    if (pumpChannelLocked(channel)) return
                }
            }
        } catch (t: Throwable) {
            log("! ticker 异常 ${describeThrowable(t)}")
        }
    }

    private fun dispatchPendingDevicesGetLocked() {
        if (pendingDevicesGet.isEmpty()) return
        val clients = pendingDevicesGet.toList()
        pendingDevicesGet.clear()
        for (clientId in clients) {
            if (appIds.contains(clientId)) {
                sendRequestLocked(METHOD_DEVICES_GET, null, onlyClientId = clientId)
            }
        }
    }

    private fun prunePendingLocked() {
        if (pendingRequests.isEmpty()) return
        val now = System.currentTimeMillis()
        var estopChanged = false
        var pulseChanged = false
        val iterator = pendingRequests.entries.iterator()
        while (iterator.hasNext()) {
            val (key, pending) = iterator.next()
            val age = now - pending.createdAtMs
            val ttl = pending.ttlMs ?: pendingTtlMs
            if (age >= ttl) {
                iterator.remove()
                log(
                    "! APP 未响应 m=${pending.method} reqId=${key.reqId} " +
                        "clientId=${key.clientId} 已 ${age}ms，移出待处理",
                )
                val kind = pending.estopKind
                if (kind != null && emergencyStopped) {
                    val tracker = estopCommandTrackers[kind]
                    if (tracker != null) {
                        tracker.pendingClients.remove(key.clientId)
                        if (tracker.pendingClients.isEmpty() && !tracker.acked) {
                            tracker.unconfirmed = true
                            tracker.reason = "timeout"
                        }
                        estopChanged = true
                    }
                }
                val pulseChannel = pulseTaskChannelByReqId.remove(key.reqId)
                if (pulseChannel != null) {
                    val record = pulseTaskByChannel[pulseChannel]
                    if (record?.reqId == key.reqId) {
                        pulseTaskByChannel[pulseChannel] =
                            record.copy(state = PulseTaskState.FAILED, reason = "timeout")
                    }
                    pulseChanged = true
                }
            }
        }
        if (estopChanged) {
            refreshEstopAggregateLocked()
        }
        if (estopChanged || pulseChanged) {
            publishLocked()
        }
    }

    private fun pumpChannelLocked(channel: Int): Boolean {
        if (!controlAvailableLocked()) return false
        val target = targetFor(channel)
        val current = estimateFor(channel)
        if (target == 0) {
            if (current == 0) return false
            // SetIntensity 只接受 v=0
            sendSetIntensityLocked(channel)
            return true
        }
        val delta = target - current
        if (delta == 0) return false
        val step = delta.coerceIn(-MAX_STEP, MAX_STEP)
        if (step == 0) return false
        sendAddIntensityLocked(channel, step)
        return true
    }

    private fun sendAddIntensityLocked(channel: Int, delta: Int) {
        val slotId = activeSlotId ?: return
        val data = jsonObject(
            "s" to jsonOf(slotId),
            "t" to jsonOf(ACTION_ADD_INTENSITY),
            "c" to jsonOf(channel),
            "p" to jsonOf(1),
            "v" to jsonOf(delta),
        )
        if (sendRequestLocked(METHOD_DEVICE_OP, data, droppable = true) == null) return
        setEstimateLocked(channel, (estimateFor(channel) + delta).coerceIn(0, MAX_INTENSITY))
        lastOpAtNanos = System.nanoTime()
    }

    private fun sendSetIntensityLocked(channel: Int) {
        val slotId = activeSlotId ?: return
        val data = setIntensityData(slotId, channel)
        if (sendRequestLocked(METHOD_DEVICE_OP, data, droppable = false) == null) return
        setEstimateLocked(channel, 0)
        lastOpAtNanos = System.nanoTime()
    }

    private fun setIntensityData(slotId: String, channel: Int): JsonObject = jsonObject(
        "s" to jsonOf(slotId),
        "t" to jsonOf(ACTION_SET_INTENSITY),
        "c" to jsonOf(channel),
        "v" to jsonOf(0),
    )

    /**
     * 发一条控制器→APP 的 RPC（锁内置帧+入队，锁外由写线程发送）。
     *
     * @param droppable true 时队列积压会丢该帧（周期性强度指令）。
     * @param priority true 时清空积压并优先发送（急停/归零/clear）。
     * @param ttlMs 可选覆盖该请求的 pending TTL（t=0 波形任务可能播放较久才回终态）。
     * @return reqId；没入队返回 null
     */
    private fun sendRequestLocked(
        method: String,
        data: JsonObject?,
        onlyClientId: String? = null,
        droppable: Boolean = false,
        priority: Boolean = false,
        ttlMs: Long? = null,
    ): String? {
        val outbound = writer
        if (outbound == null) {
            log("! 无法发送 $method：中继未运行")
            return null
        }
        val targets = resolveTargetsLocked(method, onlyClientId)
        if (targets.isEmpty()) {
            logThrottledNoTargetLocked(method)
            return null
        }
        val request = buildRequestLocked(method, data)
        var sent = 0
        if (priority) {
            if (enqueuePriorityLocked(outbound, targets, listOf(request)) > 0) sent = targets.size
        } else {
            val now = System.currentTimeMillis()
            for (clientId in targets) {
                if (outbound.submit(request.frame, clientId, droppable)) {
                    pendingRequests[PendingKey(clientId, request.reqId)] =
                        PendingRequest(method, now, ttlMs = ttlMs)
                    sent++
                }
            }
        }
        if (sent == 0) {
            log("! $method 未发出（发送队列已满/写线程已停止）")
            return null
        }
        log("→ APP $method reqId=${request.reqId} targets=$sent${describeOperate(data)}")
        return request.reqId
    }

    /**
     * 1:N 路由：`device.op` 只发给 activeSlotId 归属的 APP；
     * 归属未知（老 APP 没上报设备、或设备列表来自多处）时广播兜底并打日志。
     */
    private fun resolveTargetsLocked(method: String, onlyClientId: String?): List<String> {
        if (onlyClientId != null) {
            return if (appIds.contains(onlyClientId)) listOf(onlyClientId) else emptyList()
        }
        if (method == METHOD_DEVICE_OP || method == METHOD_DEVICE_OP_CLEAR) {
            val owner = activeSlotId?.let { slotOwners[it] }?.takeIf { appIds.contains(it) }
            if (owner != null) return listOf(owner)
            logOpsFallbackLocked()
            return appIds.toList()
        }
        return appIds.toList()
    }

    private fun buildRequestLocked(method: String, data: JsonObject?): FramedRequest {
        val reqId = (++nextReqId).toString()
        val frame = jsonObject(
            "t" to jsonOf("req"),
            "reqId" to jsonOf(reqId),
            "m" to jsonOf(method),
            "data" to data,
        )
        return FramedRequest(reqId, method, frame)
    }

    private fun enqueuePriorityLocked(
        outbound: OutboundWriter,
        targets: List<String>,
        frames: List<FramedRequest>,
    ): Int {
        val now = System.currentTimeMillis()
        val envelopes = ArrayList<OutboundWriter.Envelope>(targets.size * frames.size)
        for (clientId in targets) {
            for (request in frames) {
                envelopes.add(OutboundWriter.Envelope(request.frame, clientId))
                pendingRequests[PendingKey(clientId, request.reqId)] = PendingRequest(request.method, now)
            }
        }
        val accepted = outbound.submitPriority(envelopes)
        if (accepted == 0) {
            // 写线程未运行：帧没有入队，撤掉 pending，避免 60s 的幽灵请求
            for (clientId in targets) {
                for (request in frames) {
                    pendingRequests.remove(PendingKey(clientId, request.reqId))
                }
            }
        }
        return accepted
    }

    /**
     * 双通道归零：**一次**优先入队（A/B 两条 t=7 v=0）。
     *
     * 不能实现成 `resetIntensity(0) + resetIntensity(1)`：第二次 `submitPriority` 会清空写线程还没取走的
     * A 通道帧，而 estimate 已被置 0 → ticker 不会再补发，造成「本地显示 0、设备仍非 0」
     * 并在下次 START 时叠加（Phase 3 审计 B1）。STOP/E-STOP 必须走这里。
     */
    fun resetAllIntensities() {
        synchronized(lock) {
            setTargetLocked(0, 0)
            setTargetLocked(1, 0)
            setEstimateLocked(0, 0)
            setEstimateLocked(1, 0)
            val slotId = activeSlotId
            val outbound = writer
            if (slotId != null && outbound != null && canSendOperationsLocked()) {
                val targets = resolveTargetsLocked(METHOD_DEVICE_OP, null)
                if (targets.isNotEmpty()) {
                    val frames = listOf(
                        buildRequestLocked(METHOD_DEVICE_OP, setIntensityData(slotId, 0)),
                        buildRequestLocked(METHOD_DEVICE_OP, setIntensityData(slotId, 1)),
                    )
                    val queued = enqueuePriorityLocked(outbound, targets, frames)
                    if (queued > 0) {
                        lastOpAtNanos = System.nanoTime()
                        log(
                            "→ APP device.op 双通道归零 reqIds=${frames.joinToString(",") { it.reqId }} " +
                                "targets=${targets.size}",
                        )
                    }
                }
            }
            publishLocked()
        }
    }
    /**
     * 急停命令链入队：与 [enqueuePriorityLocked] 的区别是每条帧都绑定 [EstopCommandKind]，
     * 响应/超时/断连时可按命令逐条结算（Phase 3 语义修正）。
     *
     * 写线程未运行时不会真的发出：此时撤掉 pending，避免留下"等待回执"的假状态。
     */
    private fun enqueueEstopLocked(
        outbound: OutboundWriter,
        targets: List<String>,
        frames: List<Pair<FramedRequest, EstopCommandKind>>,
    ): Int {
        val now = System.currentTimeMillis()
        val envelopes = ArrayList<OutboundWriter.Envelope>(targets.size * frames.size)
        for (clientId in targets) {
            for ((request, kind) in frames) {
                envelopes.add(OutboundWriter.Envelope(request.frame, clientId))
                pendingRequests[PendingKey(clientId, request.reqId)] =
                    PendingRequest(request.method, now, kind)
                estopCommandTrackers.getOrPut(kind) { EstopCommandTracker() }
                    .pendingClients.add(clientId)
            }
        }
        val accepted = outbound.submitPriority(envelopes)
        if (accepted == 0) {
            for (clientId in targets) {
                for ((request, kind) in frames) {
                    pendingRequests.remove(PendingKey(clientId, request.reqId))
                    estopCommandTrackers[kind]?.pendingClients?.remove(clientId)
                }
            }
        }
        return accepted
    }

    private fun enqueueSafetyLocked(outbound: OutboundWriter?) {
        if (outbound == null) return
        val slotId = activeSlotId ?: return
        val targets = resolveTargetsLocked(METHOD_DEVICE_OP, null)
        if (targets.isEmpty()) return
        val frames = listOf(
            buildRequestLocked(METHOD_DEVICE_OP, setIntensityData(slotId, 0)),
            buildRequestLocked(METHOD_DEVICE_OP, setIntensityData(slotId, 1)),
            buildRequestLocked(METHOD_DEVICE_OP_CLEAR, null),
        )
        val queued = enqueuePriorityLocked(outbound, targets, frames)
        if (queued > 0) log("停止前归零下发 A/B + clear（targets=${targets.size}）")
    }

    private fun dispatchFrame(
        server: V4RelayServer,
        frame: JsonObject,
        clientId: String?,
    ): Boolean = if (clientId != null) {
        server.sendToApp(clientId, frame)
    } else {
        server.sendToApps(frame) > 0
    }

    private fun onWriterDropped(count: Int, reason: String) {
        val total = droppedFramesTotal.addAndGet(count.toLong())
        val now = System.currentTimeMillis()
        val previous = lastDropLogAtMs.get()
        if (now - previous >= DROP_LOG_INTERVAL_MS && lastDropLogAtMs.compareAndSet(previous, now)) {
            log("! 发送队列积压，丢弃 $count 帧（reason=$reason，累计 $total）")
        }
    }

    private fun logThrottledNoTargetLocked(method: String) {
        val now = System.currentTimeMillis()
        if (now - lastNoTargetLogAtMs < 1000L) return
        lastNoTargetLogAtMs = now
        log("! 无法发送 $method：无可用 APP 连接")
    }

    private fun logOpsFallbackLocked() {
        val now = System.currentTimeMillis()
        if (now - lastOpsFallbackLogAtMs < FALLBACK_LOG_INTERVAL_MS) return
        lastOpsFallbackLogAtMs = now
        log("! activeSlot 归属未知，device.op 广播兜底（targets=${appIds.size}）")
    }

    // ------------------------------------------------------------------ 设备/事件

    private fun handleEventLocked(clientId: String, frame: JsonObject) {
        when (val event = nestedString(frame, "ev")) {
            "devices.snapshot" -> {
                val devices = nestedArray(frame, "devices")
                if (devices == null) {
                    log("! devices.snapshot 缺少 devices 数组")
                } else {
                    applySnapshotLocked(clientId, devices)
                }
            }
            "devices.patch" -> {
                val added = nestedArray(frame, "added")
                val removed = nestedArray(frame, "removed")
                if (added == null && removed == null) {
                    log("! devices.patch 缺少 added/removed")
                } else {
                    applyPatchLocked(clientId, added, removed)
                }
            }
            "slots.patch" -> {
                val slots = nestedArray(frame, "slots")
                if (slots == null) {
                    log("! slots.patch 缺少 slots 数组")
                } else {
                    applySlotsPatchLocked(clientId, slots)
                }
            }
            "custom.action" -> log("custom.action action=${payloadOf(frame).intOrNull("action")}")
            else -> {
                val count = unknownTypeFrames.incrementAndGet()
                log("忽略事件 ev=${event ?: "-"}（累计 $count）")
            }
        }
    }

    private fun handleResponseLocked(clientId: String, frame: JsonObject) {
        val reqId = frame.scalarStringOrNull("reqId")
        var method: String? = null
        var pending: PendingRequest? = null
        if (reqId == null) {
            log("! resp 缺少 reqId clientId=$clientId")
        } else {
            pending = pendingRequests.remove(PendingKey(clientId, reqId))
            method = pending?.method
            if (pending == null) {
                log("忽略未知响应 clientId=$clientId reqId=$reqId（可能已超时清理/属于其它 APP）")
            }
        }
        val error = frame["error"]
        var errorMessage: String? = null
        if (error != null && error != JsonNull) {
            val message = describeError(error)
            errorMessage = message
            lastError = message
            log("! APP 错误 reqId=${reqId ?: "-"} m=${method ?: "-"} clientId=$clientId $message")
            publishLocked()
        } else if (method != null && lastError != null) {
            // 成功响应后清掉上一次错误（审计要求：否则 UI 一直显示旧错误）
            lastError = null
            publishLocked()
        }
        val result = frame.objOrNull("result") ?: payloadOf(frame).takeIf { it !== frame }
        val devices = result?.arrayOrNull("devices")
        if (devices != null) {
            applySnapshotLocked(clientId, devices)
        }
        val reason = result?.stringOrNull("reason")
        if (method == METHOD_DEVICE_OP || method == METHOD_DEVICE_OP_CLEAR) {
            lastOpResponseReason = reason ?: "-"
            log("device.op 响应 reqId=${reqId ?: "-"} reason=${reason ?: "-"}")
        }
        // 急停命令链结算：只有 A/B 归零 + clear 三条各自收到成功语义回执，estopAppAcked 才为 true。
        // 与急停无关的 device.op 响应（例如复位流程里普通归零）不会影响这里。
        val estopKind = pending?.estopKind
        if (estopKind != null) {
            completeEstopCommandLocked(clientId, estopKind, errorMessage, reason)
        }
        // Phase 4A：t=0 波形任务终态（reqId 反查通道；迟到的旧任务回执被忽略）
        val pulseChannel = reqId?.let { pulseTaskChannelByReqId.remove(it) }
        if (pulseChannel != null) {
            completePulseTaskLocked(pulseChannel, reqId ?: "-", errorMessage, reason)
        }
    }

    /**
     * Phase 4A：结算 t=0 波形任务的 APP 终态回执。
     *
     * reason 映射（公开协议）：completed→COMPLETED、cleared→CLEARED、replaced→REPLACED、
     * cancelled→CANCELLED；错误/未知→FAILED。reason 缺失按 COMPLETED（APP 未给出原因视为任务结束）。
     */
    private fun completePulseTaskLocked(
        channel: Int,
        reqId: String,
        errorMessage: String?,
        reason: String?,
    ) {
        val record = pulseTaskByChannel[channel] ?: return
        if (record.reqId != reqId) return
        val normalized = reason?.lowercase()
        val state = when {
            errorMessage != null -> PulseTaskState.FAILED
            normalized == null || normalized.isBlank() -> PulseTaskState.COMPLETED
            normalized == "completed" -> PulseTaskState.COMPLETED
            normalized == "cleared" -> PulseTaskState.CLEARED
            normalized == "replaced" -> PulseTaskState.REPLACED
            normalized == "cancelled" -> PulseTaskState.CANCELLED
            else -> PulseTaskState.FAILED
        }
        pulseTaskByChannel[channel] = record.copy(
            state = state,
            reason = errorMessage ?: reason,
        )
        log(
            "t=0 波形任务终态 c=$channel reqId=$reqId state=$state " +
                "reason=${reason ?: errorMessage ?: "-"}（APP 层，不代表设备物理输出）",
        )
        publishLocked()
    }

    /** Phase 4A：本地取消所有 t=0 波形任务（不额外发帧；调用方按场景决定是否 clear）。 */
    private fun cancelPulseTasksLocked(reason: String) {
        if (pulseTaskByChannel.isEmpty() && pulseTaskChannelByReqId.isEmpty()) return
        pulseTaskChannelByReqId.clear()
        pulseTaskByChannel.replaceAll { _, record ->
            record.copy(state = PulseTaskState.CANCELLED, reason = reason)
        }
        log("t=0 波形任务本地取消：$reason（如有需要由 device.op.clear 在 APP 侧清理）")
    }

    /**
     * 结算一条急停命令的 APP 回执。
     *
     * 成功语义：无 error，且 reason 缺失 / `completed` / `cleared`；
     * 其它 reason（`replaced` / `cancelled` / 未知值）或错误响应一律 UNCONFIRMED。
     * 只有 A_ZERO / B_ZERO / CLEAR 三条全部 ACKED，[estopAppAcked] 才置位——仍只是 APP 层确认，
     * 不代表 BLE 设备端已物理停止。
     */
    private fun completeEstopCommandLocked(
        clientId: String,
        kind: EstopCommandKind,
        errorMessage: String?,
        reason: String?,
    ) {
        if (!emergencyStopped) return
        val tracker = estopCommandTrackers.getOrPut(kind) { EstopCommandTracker() }
        tracker.pendingClients.remove(clientId)
        if (errorMessage == null && isSuccessfulOpReason(reason)) {
            tracker.acked = true
            tracker.reason = reason?.takeIf { it.isNotBlank() } ?: "ok"
        } else {
            tracker.unconfirmed = true
            tracker.reason = errorMessage ?: reason ?: "unknown"
        }
        refreshEstopAggregateLocked()
        publishLocked()
    }

    /** reason 语义（Phase 3 公开协议审查）：completed / cleared / 缺失 = 成功；其余不能算确认。 */
    private fun isSuccessfulOpReason(reason: String?): Boolean = when (reason?.lowercase()) {
        null, "", "completed", "cleared" -> true
        else -> false
    }

    /**
     * 重算 estopAppAcked / estopAckReason。
     *
     * 规则：三条命令都要有（A_ZERO/B_ZERO/CLEAR），每条都没有待回执的 client、且至少 ACKED 一次、
     * 没有 UNCONFIRMED，才算整条命令链被 APP 层确认。
     */
    private fun refreshEstopAggregateLocked() {
        if (estopCommandTrackers.isEmpty()) {
            estopAppAcked = false
            estopAckReason = null
            return
        }
        estopAppAcked = estopCommandTrackers.keys.containsAll(EstopCommandKind.values().toList()) &&
            estopCommandTrackers.values.all {
                it.pendingClients.isEmpty() && it.acked && !it.unconfirmed
            }
        estopAckReason = estopCommandTrackers.entries.joinToString(",") { (kind, tracker) ->
            val state = when {
                tracker.unconfirmed -> "NOT_ACKED(${tracker.reason ?: "unknown"})"
                tracker.acked -> "ACKED(${tracker.reason ?: "ok"})"
                else -> "PENDING"
            }
            "${kind.name}:$state"
        }
    }

    /** 给 UI/测试的逐条命令回执视图（无急停时为空） */
    private fun buildEstopCommandResultsLocked(): List<EstopCommandResult> =
        EstopCommandKind.values().mapNotNull { kind ->
            val tracker = estopCommandTrackers[kind] ?: return@mapNotNull null
            EstopCommandResult(
                kind = kind,
                state = when {
                    tracker.pendingClients.isNotEmpty() -> EstopCommandAckState.PENDING
                    tracker.unconfirmed -> EstopCommandAckState.UNCONFIRMED
                    tracker.acked -> EstopCommandAckState.ACKED
                    else -> EstopCommandAckState.PENDING
                },
                reason = tracker.reason,
            )
        }

    /** Phase 4A：t=0 波形任务快照（按通道排序；未启用时为空） */
    private fun buildPulseTasksLocked(): List<PulseTaskStatus> =
        pulseTaskByChannel.values
            .sortedBy { it.channel }
            .map { PulseTaskStatus(it.channel, it.state, it.reqId, it.reason) }

    /**
     * APP 没带 `t` 的推送（有的实现直接发 devices.snapshot 对象）按字段推断。
     * 与 `ev` 路径共用同一套 root/data/payload 提取器，避免两种封装行为不一致。
     */
    private fun handleUntypedLocked(clientId: String, frame: JsonObject) {
        val devices = nestedArray(frame, "devices")
        val slots = nestedArray(frame, "slots")
        val added = nestedArray(frame, "added")
        val removed = nestedArray(frame, "removed")
        when {
            devices != null -> applySnapshotLocked(clientId, devices)
            slots != null -> applySlotsPatchLocked(clientId, slots)
            added != null || removed != null -> applyPatchLocked(clientId, added, removed)
            else -> {
                val count = unknownTypeFrames.incrementAndGet()
                log("忽略无类型消息（累计 $count）")
            }
        }
    }

    private fun applySnapshotLocked(clientId: String, devices: JsonArray) {
        val changed = registry.applySnapshot(devices)
        recordSnapshotOwnersLocked(clientId, devices)
        // Phase 3 诊断：只记录每个会话的首个 devices.snapshot 原文（截断），用于确认真实 APP 上报字段
        if (!loggedSnapshotPayload) {
            loggedSnapshotPayload = true
            log("devices.snapshot payload=${devices.toString().take(1000)}")
        }
        syncDeviceStateLocked(changed)
    }

    private fun applyPatchLocked(clientId: String, added: JsonArray?, removed: JsonArray?) {
        val changed = registry.applyPatch(added, removed)
        if (added != null) {
            for (element in added) {
                val slotId = slotIdOf(element) ?: continue
                slotOwners[slotId] = clientId
            }
        }
        if (removed != null) {
            for (element in removed) {
                val slotId = (element as? JsonPrimitive)?.content ?: continue
                slotOwners.remove(slotId)
            }
        }
        retainOwnersLocked()
        syncDeviceStateLocked(changed)
    }

    private fun applySlotsPatchLocked(clientId: String, slots: JsonArray) {
        val changed = registry.applySlotsPatch(slots)
        for (element in slots) {
            val slotId = slotIdOf(element) ?: continue
            // 谁在报这个 slot 的实时状态，强度闭环就认为它"拥有"这个设备（1:N 下最新 reporter 生效）
            if (registry.get(slotId) != null) slotOwners[slotId] = clientId
        }
        // Phase 3 诊断：只记录每个会话的首个 slots.patch 原文（截断），用于确认真实 APP 上报字段
        if (!loggedSlotsPatchPayload) {
            loggedSlotsPatchPayload = true
            log("slots.patch payload=${slots.toString().take(1000)}")
        }
        retainOwnersLocked()
        syncDeviceStateLocked(changed)
    }

    private fun recordSnapshotOwnersLocked(clientId: String, devices: JsonArray) {
        slotOwners.clear()
        for (element in devices) {
            val slotId = slotIdOf(element) ?: continue
            slotOwners[slotId] = clientId
        }
        retainOwnersLocked()
    }

    private fun retainOwnersLocked() {
        if (slotOwners.isEmpty()) return
        val known = registry.entries().mapTo(HashSet()) { it.slotId }
        slotOwners.keys.retainAll(known)
    }

    private fun slotIdOf(element: JsonElement): String? {
        val json = element as? JsonObject ?: return null
        return json.scalarStringOrNull("slotId") ?: json.scalarStringOrNull("id")
    }

    /** 事件 payload：优先根对象，其次 `data`/`payload` 子对象（兼容两种 APP 封装） */
    private fun payloadOf(frame: JsonObject): JsonObject =
        frame.objOrNull("data") ?: frame.objOrNull("payload") ?: frame

    private fun nestedString(frame: JsonObject, key: String): String? =
        frame.stringOrNull(key)
            ?: frame.objOrNull("data")?.stringOrNull(key)
            ?: frame.objOrNull("payload")?.stringOrNull(key)

    private fun nestedArray(frame: JsonObject, key: String): JsonArray? =
        frame.arrayOrNull(key)
            ?: frame.objOrNull("data")?.arrayOrNull(key)
            ?: frame.objOrNull("payload")?.arrayOrNull(key)

    /**
     * 设备状态变化后同步 activeSlotId / 上报强度。只有真的变了才 publish，
     * 避免重复快照/心跳把 StateFlow 刷爆（审计要求 apply* 的 changed 用来跳过 publish）。
     */
    private fun syncDeviceStateLocked(registryChanged: Boolean) {
        var changed = registryChanged
        val newActive = registry.activeSlotId()
        if (newActive != activeSlotId) {
            if (activeSlotId != null) {
                log("activeSlot 切换 ${activeSlotId ?: "-"} → ${newActive ?: "-"}，强度估计归零")
            }
            activeSlotId = newActive
            targetA = 0
            targetB = 0
            estimateA = 0
            estimateB = 0
            reportedA = null
            reportedB = null
            changed = true
        }
        val entry = activeSlotId?.let { registry.get(it) }
        entry?.intensityA?.let {
            if (reportedA != it || estimateA != it) {
                reportedA = it
                estimateA = it
                changed = true
            }
        }
        entry?.intensityB?.let {
            if (reportedB != it || estimateB != it) {
                reportedB = it
                estimateB = it
                changed = true
            }
        }
        if (changed) publishLocked()
    }

    /** 新 APP 会话：清空来自旧连接的全部运行时状态（重连最常见路径，必须安全） */
    private fun resetSessionLocked(newClientId: String) {
        registry.clear()
        slotOwners.clear()
        activeSlotId = null
        reportedA = null
        reportedB = null
        estimateA = 0
        estimateB = 0
        targetA = 0
        targetB = 0
        loggedSnapshotPayload = false
        loggedSlotsPatchPayload = false
        pendingDevicesGet.clear()
        pendingDevicesGet.add(newClientId)
        // 已断开 APP 的未决请求不可能再得到正确响应；仍在线的保留（1:N 下各自结算）
        pendingRequests.keys.removeAll { it.clientId !in appIds }
    }

    // ------------------------------------------------------------------ 状态/工具

    private fun canSendOperationsLocked(): Boolean =
        relayRunning && appIds.isNotEmpty() && activeSlotId != null

    private fun controlAvailableLocked(): Boolean {
        if (!canSendOperationsLocked() || emergencyStopped) return false
        val slotId = activeSlotId ?: return false
        // 审计：设备断开后不能再算"可控制"（否则会对不存在的 slot 下发 device.op）
        return registry.get(slotId)?.connected == true
    }

    private fun targetFor(channel: Int): Int = if (channel == 0) targetA else targetB

    private fun estimateFor(channel: Int): Int = if (channel == 0) estimateA else estimateB

    private fun setTargetLocked(channel: Int, value: Int) {
        if (channel == 0) targetA = value else targetB = value
    }

    private fun setEstimateLocked(channel: Int, value: Int) {
        val clamped = value.coerceIn(0, MAX_INTENSITY)
        if (channel == 0) estimateA = clamped else estimateB = clamped
    }

    private fun clientUrlLocked(hostOverride: String?): String {
        val host = hostOverride?.trim()?.takeIf { it.isNotEmpty() } ?: displayHost
        return "ws://$host:$port/?tid=$targetId"
    }

    private fun warnEstopLocked(action: String) {
        val now = System.currentTimeMillis()
        if (now - lastEstopWarnAtMs < 1000L) return
        lastEstopWarnAtMs = now
        log("忽略 $action：急停锁存中（先 clearEmergencyStop）")
    }

    private fun describeOperate(data: JsonObject?): String {
        if (data == null) return ""
        return buildString {
            data.intOrNull("t")?.let { append(" t=").append(it) }
            data.intOrNull("c")?.let { append(" c=").append(it) }
            data.intOrNull("v")?.let { append(" v=").append(it) }
        }
    }

    private fun describeError(error: JsonElement): String = when (error) {
        is JsonPrimitive -> error.content
        is JsonObject -> error.stringOrNull("code")
            ?: error.stringOrNull("message")
            ?: error.toString()
        else -> error.toString()
    }

    private fun publishLocked() {
        _state.value = DglabState(
            relayRunning = relayRunning,
            bindHost = bindHost,
            port = port,
            targetId = targetId,
            appClientIds = appIds.toList(),
            devices = registry.entries().map { it.summary() },
            activeSlotId = activeSlotId,
            reportedIntensityA = reportedA,
            reportedIntensityB = reportedB,
            targetIntensityA = targetA,
            targetIntensityB = targetB,
            estimateIntensityA = estimateA,
            estimateIntensityB = estimateB,
            lastOpResponseReason = lastOpResponseReason,
            emergencyStopped = emergencyStopped,
            estopIssued = estopIssued,
            estopFramesQueued = estopFramesQueued,
            estopAppAcked = estopAppAcked,
            estopIssuedAtMs = estopIssuedAtMs,
            estopAckReason = estopAckReason,
            estopCommandResults = buildEstopCommandResultsLocked(),
            pulseTasks = buildPulseTasksLocked(),
            queuedOutboundCount = writer?.queuedCount ?: 0,
            droppedOpCount = writer?.droppedOpCount ?: 0L,
            lastError = lastError,
            handshakeCount = handshakeCount,
            lastHandshakeSummary = lastHandshakeSummary,
            lastRejectReason = lastRejectReason,
            lastCloseCode = lastCloseCode,
            lastCloseReason = lastCloseReason,
        )
    }

    /** 协议日志：保留最近 [RelayProtocol.MAX_LOG_LINES] 条，旧→新（UI 直接展示或自行反转） */
    private fun log(message: String) {
        if (message.isBlank()) return
        val line = "${LocalTime.now().format(timeFormatter)} $message"
        val snapshot = synchronized(logLock) {
            logBuffer.addLast(line)
            while (logBuffer.size > RelayProtocol.MAX_LOG_LINES) logBuffer.removeFirst()
            logBuffer.toList()
        }
        _logLines.value = snapshot
        RelayLog.i(LOG_TAG, message)
    }

    // ---- 单测/诊断用（internal，不改变 DglabLinkApi 契约） ----

    internal fun pendingRequestCount(): Int = synchronized(lock) { pendingRequests.size }

    internal fun pendingRequestEntries(): List<Pair<String, String>> = synchronized(lock) {
        pendingRequests.keys.map { it.clientId to it.reqId }
    }

    internal fun queuedOutboundCount(): Int = writer?.queuedCount ?: 0

    internal fun droppedOpCount(): Long = writer?.droppedOpCount ?: 0

    private companion object {
        const val LOG_TAG = "V4"
        const val MAX_INTENSITY = 200
        const val MAX_STEP = 10
        const val ACTION_ADD_INTENSITY = 3
        const val ACTION_SET_INTENSITY = 7

        /** Phase 4A：AppendPulseData（t=0） */
        const val ACTION_APPEND_PULSE_DATA = 0

        /** t=0 帧消费 tick（公开资料约 100ms/帧） */
        const val PULSE_FRAME_TICK_MS = 100L
        const val PULSE_MIN_PENDING_TTL_MS = 30_000L
        const val PULSE_MAX_PENDING_TTL_MS = 15 * 60_000L
        const val DEFAULT_TICK_MS = 100L
        const val DEFAULT_OP_INTERVAL_MS = 200L

        /** 未决 RPC TTL：device.op 的 resp 可能延迟到任务完成，给足时间；超时只记录不影响安全 */
        const val DEFAULT_PENDING_TTL_MS = 60_000L
        const val WRITER_DRAIN_TIMEOUT_MS = 500L
        const val DROP_LOG_INTERVAL_MS = 1_000L
        const val FALLBACK_LOG_INTERVAL_MS = 5_000L

        const val METHOD_DEVICES_GET = "devices.get"
        const val METHOD_DEVICE_OP = "device.op"
        const val METHOD_DEVICE_OP_CLEAR = "device.op.clear"
    }
}
