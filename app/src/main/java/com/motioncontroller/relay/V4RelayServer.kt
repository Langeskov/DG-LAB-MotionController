package com.motioncontroller.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.HandshakeBuilder
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 中继向进程内控制器回调的事件（全部在 Java-WebSocket 的网络线程上触发，实现方自己保证线程安全）。
 */
interface V4RelayListener {

    /** APP（被控方）成功接入：hello + controller_attached 已入队，控制器可以开始发 `devices.get` */
    fun onAppAttached(clientId: String) {}

    /** APP 断开；控制器必须归零 */
    fun onAppDisconnected(clientId: String) {}

    /** APP → 控制器的应用层消息（`message.data` 原样） */
    fun onAppMessage(clientId: String, data: JsonElement) {}

    /** 无 APP 接入超过 idleTimeout（单机形态只记录，不主动断开） */
    fun onControllerIdle() {}

    /** 中继自己的协议日志（连上/拒绝/断开/空闲） */
    fun onRelayLog(line: String) {}

    /**
     * 服务端 WebSocket 握手**成功**完成（请求解析 + 响应构建都没抛异常）。
     *
     * [summary] 为一行摘要：`UA=<User-Agent> Connection=<Connection 头>`。
     * 用于排查「DG-LAB APP 无法连接」（AGENTS 3.2 的 Connection 头陷阱就靠它定位）。
     */
    fun onHandshakeSummary(summary: String) {}

    /**
     * 连接被拒绝（无 tid / tid 不匹配 / 握手帧发送失败）。
     * [reason] 为一行中文诊断（如 `tid=xxx 不匹配 controller=yyy`）。
     */
    fun onConnectionRejected(reason: String) {}

    /**
     * APP（已 attach 的被控方）连接关闭明细；被拒绝的连接不会走这里。
     * [code]/[reason] 为 WebSocket 关闭码/原因（[reason] 可能是空串），[remote] 表示对端发起。
     */
    fun onAppClosed(clientId: String, code: Int, reason: String, remote: Boolean) {}
}

/**
 * 修复 Java-WebSocket 的握手头 bug。
 *
 * `Draft_6455.postProcessHandshakeResponseAsServer()` 会把客户端请求里的 `Connection` 头**原样回显**：
 * ```
 * response.put(CONNECTION, request.getFieldValue(CONNECTION)); // to respond to a Connection keep alives
 * ```
 * DG-LAB APP 的 OkHttp 3.14.9 发送的是 `Connection: Upgrade; close`，随后 OkHttp 又严格校验
 * 响应必须是 `Connection: Upgrade`，于是抛
 * `ProtocolException: Expected 'Connection' header value 'Upgrade' but was 'Upgrade; close'`，
 * APP 直接判定「连接失败」并 reset（我们这边表现为接入后 1006 秒断，见 AGENTS 3.2）。
 *
 * 覆盖该方法，强制回写标准值。
 */
private class UpgradeConnectionDraft : Draft_6455() {
    override fun postProcessHandshakeResponseAsServer(
        request: ClientHandshake,
        response: ServerHandshakeBuilder,
    ): HandshakeBuilder {
        val builder = super.postProcessHandshakeResponseAsServer(request, response)
        builder.put("Connection", "Upgrade")
        builder.put("Upgrade", "websocket")
        return builder
    }

    /**
     * Java-WebSocket 会为每个连接调用 `copyInstance()` 复制 Draft；
     * `Draft_6455.copyInstance()` 返回基类实例，必须覆盖以保住上面的握手修复。
     */
    override fun copyInstance(): Draft = UpgradeConnectionDraft()
}

/**
 * V4 WebSocket 中继（Java-WebSocket 服务端）。
 *
 * 角色判定按 V4 语义：
 *  - URL 带 `tid` 的连接是 APP（被控方），且 `tid` 必须等于本进程内控制器的 [controllerId]；
 *    - 不存在 → `{"type":"error","code":"controller_not_found"}` + close 4001；
 *    - 存在 → hello + `controller_attached`，并通知控制器；
 *  - 不带 `tid` 的连接在 V4 里是外部控制端；本实现控制器在进程内，直接拒绝（close 4010），日志说明。
 *
 * 转发规则：
 *  - APP 的 `{"type":"message","data":...}` → 控制器；
 *  - 控制器 → APP 的 message 由中继包上 `{"type":"message","data":...}`（不带 clientId）。
 *
 * 线程/时序约定：
 *  - **握手帧先入队、再发布 APP**：否则 tick/UI 线程可能在 hello 之前插入 message，APP 会把首帧丢掉；
 *  - 所有发送都先看连接状态与 [WebSocket.hasBufferedData]（慢 APP 的写积压），失败即返回 false，
 *    绝不往连接队列里无限堆帧；
 *  - connectionLostTimeout 显式设置，不依赖业务心跳兜底；
 *  - idle/heartbeat 调度器的捕获、取消、关闭都在 [timerLock] 下做，并在 stop 后吞掉
 *    [RejectedExecutionException]（网络线程与 stop 的竞态）。
 */
class V4RelayServer(
    private val listener: V4RelayListener,
    private val idleTimeoutMs: Long = RelayProtocol.DEFAULT_IDLE_TIMEOUT_MS,
    private val heartbeatIntervalMs: Long = RelayProtocol.DEFAULT_HEARTBEAT_MS,
    private val connectionLostTimeoutSeconds: Int = DEFAULT_CONNECTION_LOST_TIMEOUT_S,
) {

    private enum class Role { APP, REJECTED }

    private class ConnInfo(
        val clientId: String,
        val role: Role,
    )

    /** 控制器 targetId（即 APP 连接 URL 里的 tid），由中继在 [start] 时分配 */
    @Volatile
    var controllerId: String = ""
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 已判定 idle（5 分钟无 APP 接入）；单机形态不主动 close */
    @Volatile
    var isIdle: Boolean = false
        private set

    @Volatile
    var port: Int = 0
        private set

    /** 绑定失败重试策略（单测可注入 sleeper/次数）；默认 3 次重试、250/500ms 退避。 */
    internal var bindRetry: BindRetry = BindRetry()

    /** 单测注入：每次绑定尝试前调用（参数为尝试序号 0 起），可抛异常模拟绑定失败（如端口占用）。 */
    internal var bindAttemptHook: ((attempt: Int) -> Unit)? = null

    /** 本次绑定的实际地址（含实际端口）；stop 时用来等端口真正释放。 */
    @Volatile
    private var boundAddress: InetSocketAddress? = null

    /** 发送背压丢弃计数（审计用：慢 APP 时不再无界堆积） */
    @Volatile
    private var lastBackpressureLogAtMs: Long = 0L

    /**
     * 每条连接"连续遇到未刷出数据"的计数。
     *
     * [WebSocket.hasBufferedData] 在正常的连续小帧（hello + controller_attached，
     * 或急停一次发的 A/B 归零 + clear）里也会短暂为 true，如果一见它就丢帧会把握手和急停打断。
     * 因此只在"持续积压"（连续 N 帧都还没刷出去）时才判定为背压并丢帧。
     */
    private val bufferedStreaks = ConcurrentHashMap<WebSocket, Int>()

    private val conns = ConcurrentHashMap<WebSocket, ConnInfo>()
    private val handshakeDescriptors = ConcurrentHashMap<WebSocket, String>()
    private val appsLock = Any()
    private val timerLock = Any()

    /** clientId → APP 连接（插入顺序，UI 展示稳定）。握手帧入队之后才会出现在这里。 */
    private val apps = LinkedHashMap<String, WebSocket>()

    private var server: WebSocketServer? = null
    private var scheduler: ScheduledExecutorService? = null
    private var heartbeatTask: ScheduledFuture<*>? = null
    private var idleTask: ScheduledFuture<*>? = null

    // ---- 审计计数 ----
    private val invalidJsonFrames = AtomicLong()
    private val nonObjectFrames = AtomicLong()
    private val unknownTypeFrames = AtomicLong()
    private val binaryFrames = AtomicLong()
    private val backpressureDrops = AtomicLong()

    /**
     * 绑定并启动。port 传 0 时由系统分配（单测用），实际端口通过返回值/[port] 读。
     *
     * 「地址占用」类绑定失败（`BindException` / "Address already in use"）自动按
     * [BindRetry] 退避重试（默认 3 次、250/500ms），兜住引擎快速重启时旧端口未释放的竞态（问题4）。
     *
     * @param preferredTargetId 复用上次的控制器 ID（持久化），保证 APP 里手输的地址跨重启不变；
     *        为空/非法时才随机生成。
     * @return 实际监听端口
     * @throws IllegalStateException 绑定失败（含重试用尽）
     */
    @Synchronized
    fun start(bindHost: String, port: Int, preferredTargetId: String? = null): Int {
        stopInternal(notifyApps = false)

        val targetId = preferredTargetId
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.matches(Regex("[0-9a-f]{8}")) }
            ?: ClientIdGenerator.next()
        controllerId = targetId
        isIdle = false

        val address = resolveAddress(bindHost, port)

        // 绑定失败（端口未释放等「地址占用」类）自动重试：引擎快速重启时旧 relay 端口偶发还在
        // 内核回收（问题4）。每次尝试都是独立的 startLatch 等待；失败实例当场 stop，不留悬挂线程。
        val serverRef = try {
            bindRetry.run(log = ::log) { attempt ->
                bindAttemptHook?.invoke(attempt)
                bindOnce(address, bindHost, port)
            }
        } catch (t: Throwable) {
            server = null
            controllerId = ""
            throw if (t is IllegalStateException) {
                t
            } else {
                IllegalStateException("V4 中继启动失败: ${describeThrowable(t)}", t)
            }
        }
        server = serverRef

        val actualPort = serverRef.port
        this.port = if (actualPort > 0) actualPort else port
        isRunning = true

        val sched = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("V4Relay"))
        synchronized(timerLock) {
            scheduler = sched
            if (heartbeatIntervalMs > 0) {
                heartbeatTask = try {
                    sched.scheduleAtFixedRate(
                        { broadcastHeartbeat() },
                        heartbeatIntervalMs,
                        heartbeatIntervalMs,
                        TimeUnit.MILLISECONDS,
                    )
                } catch (_: RejectedExecutionException) {
                    null
                }
            }
        }
        scheduleIdleCheck()
        log(
            "中继启动 host=$bindHost port=${this.port} targetId=$targetId " +
                "connectionLostTimeout=${connectionLostTimeoutSeconds}s",
        )
        return this.port
    }

    /**
     * 一次完整的绑定尝试：建服务端 → [WebSocketServer.start] → 等 [CountDownLatch]。
     *
     * 保留原始 startLatch 等待逻辑；失败时当场停掉本次实例并抛 [IllegalStateException]
     * （cause 保留原始异常，供 [BindRetry] 判定是否属于「端口占用」可重试类）。
     */
    private fun bindOnce(address: InetSocketAddress, bindHost: String, port: Int): WebSocketServer {
        val startLatch = CountDownLatch(1)
        val startFailure = AtomicReference<Throwable?>(null)
        val serverRef = createServer(address, startLatch, startFailure)
        serverRef.isReuseAddr = true
        // 显式设置连接失活检测；不再只靠 30s 业务心跳（APP 不响应时永远发现不了半开连接）
        serverRef.setConnectionLostTimeout(connectionLostTimeoutSeconds)

        try {
            serverRef.start()
        } catch (t: Throwable) {
            try {
                serverRef.stop(STOP_TIMEOUT_MS)
            } catch (_: Throwable) {
                // ignore
            }
            throw IllegalStateException("V4 中继启动失败: ${describeThrowable(t)}", t)
        }

        val started = startLatch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val failure = startFailure.get()
        if (!started || failure != null) {
            try {
                serverRef.stop(STOP_TIMEOUT_MS)
            } catch (_: Throwable) {
                // ignore
            }
            val cause = failure
            throw IllegalStateException("V4 中继绑定 $bindHost:$port 失败: ${cause?.message}", cause)
        }

        val actualPort = serverRef.port
        boundAddress = InetSocketAddress(address.address, if (actualPort > 0) actualPort else port)
        return serverRef
    }

    /** 建一个带全部回调的 Java-WebSocket 服务端（每次绑定尝试独立实例）。 */
    private fun createServer(
        address: InetSocketAddress,
        startLatch: CountDownLatch,
        startFailure: AtomicReference<Throwable?>,
    ): WebSocketServer = object : WebSocketServer(address, listOf<Draft>(UpgradeConnectionDraft())) {
        override fun onStart() {
            startLatch.countDown()
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            handleOpen(conn)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            handleClose(conn, code, reason, remote)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            handleMessage(conn, message)
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            handleBinaryMessage(conn, message)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) {
                startFailure.compareAndSet(null, ex)
                startLatch.countDown()
                log("中继启动错误 ${describeThrowable(ex)}")
                return
            }
            log("中继错误 conn=${conns[conn]?.clientId ?: "-"} ${describeThrowable(ex)}")
        }

        override fun onWebsocketHandshakeReceivedAsServer(
            conn: WebSocket,
            draft: Draft,
            request: ClientHandshake,
        ): ServerHandshakeBuilder {
            request.resourceDescriptor?.let { handshakeDescriptors[conn] = it }
            // 保留一行握手诊断（DG-LAB APP 的 OkHttp 对 Connection 头敏感，见 AGENTS 3.2）
            val summary = "UA=${request.getFieldValue("User-Agent") ?: "-"} " +
                "Connection=${request.getFieldValue("Connection") ?: "-"}"
            runCatching { log("WS 握手 $summary") }
            val builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
            // 只有握手真正成功（super 未抛异常）才计数/上报（问题4 连接诊断）
            notifyHandshakeSummary(summary)
            return builder
        }
    }

    @Synchronized
    fun stop() {
        stopInternal(notifyApps = true)
    }

    /** 当前已接入的 APP clientId（稳定顺序） */
    fun appClientIds(): List<String> = synchronized(appsLock) { apps.keys.toList() }

    fun appCount(): Int = synchronized(appsLock) { apps.size }

    /**
     * 控制器 → APP：发给所有已接入 APP，中继负责包上 `type=message`（不带 clientId）。
     *
     * @return 实际发出的连接数
     */
    fun sendToApps(data: JsonElement): Int {
        val snapshot = synchronized(appsLock) { apps.values.toList() }
        if (snapshot.isEmpty()) return 0
        var sent = 0
        for (conn in snapshot) {
            if (sendToAppsQuietly(conn, data)) sent++
        }
        return sent
    }

    fun sendToApp(clientId: String, data: JsonElement): Boolean {
        val conn = synchronized(appsLock) { apps[clientId] } ?: return false
        return sendToAppsQuietly(conn, data)
    }

    // ---------------------------------------------------------------- 内部实现

    private fun handleOpen(conn: WebSocket) {
        val descriptor = handshakeDescriptors.remove(conn) ?: conn.resourceDescriptor
        val targetId = RelayProtocol.targetIdFromDescriptor(descriptor)
        val clientId = ClientIdGenerator.next()
        // 握手成功之前一律按"已拒绝"保管，避免 onClose 通知控制器一个从未 attach 的 clientId
        conns[conn] = ConnInfo(clientId, Role.REJECTED)

        if (targetId == null) {
            // V4 语义：没有 tid 即控制端。本实现控制端在进程内，拒绝外部控制端。
            sendQuietly(conn, RelayProtocol.hello(clientId), checkBackpressure = false)
            sendQuietly(
                conn,
                RelayProtocol.error(
                    RelayProtocol.ERROR_CONTROLLER_NOT_FOUND,
                    "embedded controller only: connect with ?tid=<targetId>",
                ),
                checkBackpressure = false,
            )
            log("拒绝无 tid 的控制端连接 clientId=$clientId（控制端内嵌，只接受 APP 连接）")
            notifyConnectionRejected("无 tid：控制端内嵌，只接受 APP 连接（clientId=$clientId）")
            closeQuietly(conn, RelayProtocol.CLOSE_CONTROLLER_REJECTED, "embedded_controller_only")
            return
        }

        if (targetId != controllerId) {
            sendQuietly(conn, RelayProtocol.hello(clientId), checkBackpressure = false)
            sendQuietly(
                conn,
                RelayProtocol.error(RelayProtocol.ERROR_CONTROLLER_NOT_FOUND),
                checkBackpressure = false,
            )
            log("拒绝 APP 连接 clientId=$clientId: tid=$targetId 不匹配 controller=$controllerId")
            notifyConnectionRejected("tid=$targetId 不匹配 controller=$controllerId（clientId=$clientId）")
            closeQuietly(conn, RelayProtocol.CLOSE_CONTROLLER_NOT_FOUND, "controller_not_found")
            return
        }

        // 关键顺序：先把 hello + controller_attached 入队，再发布 APP / 通知控制器。
        // 否则 tick/UI 线程可能在 hello 之前插入 message，APP 把首帧当垃圾。
        val helloQueued = sendQuietly(conn, RelayProtocol.hello(clientId), checkBackpressure = false)
        val attachedQueued = helloQueued &&
            sendQuietly(conn, RelayProtocol.controllerAttached(controllerId), checkBackpressure = false)
        if (!helloQueued || !attachedQueued) {
            log("APP 握手帧发送失败 clientId=$clientId（连接已不可用），不发布给控制器")
            notifyConnectionRejected("hello/controller_attached 发送失败（clientId=$clientId）")
            closeQuietly(conn, RelayProtocol.CLOSE_HANDSHAKE_FAILED, "handshake_send_failed")
            return
        }

        val staleClients = mutableListOf<String>()
        synchronized(appsLock) {
            // 重连常见路径：旧 TCP 连接可能已经半死但还没触发 onClose，先剔除再发布新连接
            val iterator = apps.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!entry.value.isOpen) {
                    staleClients.add(entry.key)
                    iterator.remove()
                }
            }
            apps[clientId] = conn
        }
        // 握手帧入队之后才把 role 提升为 APP，保证手写消息路径与发布顺序一致
        conns[conn] = ConnInfo(clientId, Role.APP)
        cancelIdleCheck()
        isIdle = false
        log("APP 接入 clientId=$clientId targetId=$targetId 总数=${appCount()}")

        for (staleId in staleClients) {
            log("清理半开旧连接 clientId=$staleId（重连）")
            notifyAppDisconnected(staleId)
        }
        try {
            listener.onAppAttached(clientId)
        } catch (t: Throwable) {
            log("onAppAttached 回调异常 ${describeThrowable(t)}")
        }
    }

    private fun handleClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        handshakeDescriptors.remove(conn)
        bufferedStreaks.remove(conn)
        val info = conns.remove(conn) ?: return
        if (info.role != Role.APP) return

        // 关闭明细先上报（诊断字段 lastCloseCode/lastCloseReason，含"提前清理"路径）
        notifyAppClosed(info.clientId, code, reason, remote)

        var published = false
        val wasLast = synchronized(appsLock) {
            published = apps.remove(info.clientId) != null
            apps.isEmpty()
        }
        if (!published) {
            // handleOpen 已经把它当作半开连接清理并通知过控制器了
            log("APP 断开（已提前清理）clientId=${info.clientId} code=$code")
            return
        }
        log("APP 断开 clientId=${info.clientId} code=$code reason=${reason.ifBlank { "-" }} remote=$remote")
        notifyAppDisconnected(info.clientId)
        if (wasLast && isRunning) scheduleIdleCheck()
    }

    private fun notifyAppDisconnected(clientId: String) {
        try {
            listener.onAppDisconnected(clientId)
        } catch (t: Throwable) {
            log("onAppDisconnected 回调异常 ${describeThrowable(t)}")
        }
    }

    private fun notifyAppClosed(clientId: String, code: Int, reason: String, remote: Boolean) {
        try {
            listener.onAppClosed(clientId, code, reason, remote)
        } catch (t: Throwable) {
            log("onAppClosed 回调异常 ${describeThrowable(t)}")
        }
    }

    private fun notifyConnectionRejected(reason: String) {
        try {
            listener.onConnectionRejected(reason)
        } catch (t: Throwable) {
            log("onConnectionRejected 回调异常 ${describeThrowable(t)}")
        }
    }

    private fun notifyHandshakeSummary(summary: String) {
        try {
            listener.onHandshakeSummary(summary)
        } catch (t: Throwable) {
            log("onHandshakeSummary 回调异常 ${describeThrowable(t)}")
        }
    }

    private fun handleMessage(conn: WebSocket, message: String) {
        val info = conns[conn] ?: return
        if (info.role != Role.APP) return

        val parsed: JsonElement = try {
            Json.parseToJsonElement(message)
        } catch (t: Throwable) {
            val count = invalidJsonFrames.incrementAndGet()
            log("忽略无效 JSON clientId=${info.clientId}（累计 $count，${describeThrowable(t)}）")
            return
        }
        val obj = parsed as? JsonObject
        if (obj == null) {
            val count = nonObjectFrames.incrementAndGet()
            log("忽略非对象帧 clientId=${info.clientId}（累计 $count）")
            return
        }

        when (obj.stringOrNull("type")) {
            RelayProtocol.TYPE_PING -> sendQuietly(conn, RelayProtocol.pong(System.currentTimeMillis()))
            RelayProtocol.TYPE_PONG -> Unit
            RelayProtocol.TYPE_MESSAGE -> {
                val data = obj["data"]
                if (data == null) {
                    val count = nonObjectFrames.incrementAndGet()
                    log("忽略缺少 data 的 message clientId=${info.clientId}（累计 $count）")
                    return
                }
                try {
                    listener.onAppMessage(info.clientId, data)
                } catch (t: Throwable) {
                    log("onAppMessage 处理异常 clientId=${info.clientId}: ${describeThrowable(t)}")
                }
            }
            else -> {
                val count = unknownTypeFrames.incrementAndGet()
                log(
                    "忽略未知帧类型 clientId=${info.clientId} type=${obj.stringOrNull("type") ?: "-"}" +
                        "（累计 $count）",
                )
            }
        }
    }

    private fun handleBinaryMessage(conn: WebSocket, message: ByteBuffer) {
        val info = conns[conn] ?: return
        if (info.role != Role.APP) return
        val count = binaryFrames.incrementAndGet()
        log("忽略二进制帧 clientId=${info.clientId} bytes=${message.remaining()}（累计 $count）")
    }

    private fun broadcastHeartbeat() {
        val snapshot = synchronized(appsLock) { apps.values.toList() }
        if (snapshot.isEmpty()) return
        for (conn in snapshot) sendQuietly(conn, RelayProtocol.heartbeat())
    }

    private fun scheduleIdleCheck() {
        synchronized(timerLock) {
            idleTask?.cancel(false)
            idleTask = null
            if (!isRunning) return
            val sched = scheduler ?: return
            idleTask = try {
                sched.schedule(
                    { onIdleExpired() },
                    idleTimeoutMs.coerceAtLeast(1L),
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: RejectedExecutionException) {
                // stop() 已经关闭调度器：忽略，避免网络线程抛未捕获异常
                null
            }
        }
    }

    private fun cancelIdleCheck() {
        synchronized(timerLock) {
            idleTask?.cancel(false)
            idleTask = null
        }
    }

    private fun onIdleExpired() {
        if (!isRunning) return
        if (appCount() > 0) return
        isIdle = true
        val seconds = idleTimeoutMs / 1000
        log("idle_timeout: 控制器 $controllerId 已 ${seconds}s 无 APP 接入（单机形态不主动断开，等待重连）")
        try {
            listener.onControllerIdle()
        } catch (t: Throwable) {
            log("onControllerIdle 回调异常 ${describeThrowable(t)}")
        }
    }

    private fun stopInternal(notifyApps: Boolean) {
        val wasRunning = isRunning || server != null
        isRunning = false
        isIdle = false

        val targetId = controllerId

        // 先让 APP 收到 controller_disconnected（4000）再停服务，避免"静默掉线"
        val snapshot = synchronized(appsLock) {
            val snapshot = apps.entries.map { it.key to it.value }
            apps.clear()
            snapshot
        }
        for ((clientId, conn) in snapshot) {
            if (notifyApps) {
                sendQuietly(conn, RelayProtocol.controllerDisconnected(targetId), checkBackpressure = false)
                closeQuietly(conn, RelayProtocol.CLOSE_CONTROLLER_DISCONNECTED, "controller_disconnected")
            } else {
                closeQuietly(conn, RelayProtocol.CLOSE_CONTROLLER_DISCONNECTED, "relay_restart")
            }
            log("关闭 APP 连接 clientId=$clientId（中继停止）")
        }

        val closedCount = snapshot.size
        conns.clear()
        handshakeDescriptors.clear()
        bufferedStreaks.clear()

        synchronized(timerLock) {
            heartbeatTask?.cancel(false)
            heartbeatTask = null
            idleTask?.cancel(false)
            idleTask = null
            scheduler?.shutdownNow()
            scheduler = null
        }

        val s = server
        server = null
        if (s != null) {
            try {
                s.stop(STOP_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                log("中继停止异常 ${describeThrowable(t)}")
            }
        }

        // WebSocketServer.stop(timeout) 是同步等待，但端口偶发仍要等内核回收：显式等它释放，
        // 保证「旧 relay 完全停止 → 新 relay 才绑定」（问题4）。等不到就交给绑定重试兜底。
        val bound = boundAddress
        boundAddress = null
        if (s != null && bound != null && !PortProbe.awaitPortReleased(bound)) {
            log(
                "端口 ${bound.hostString}:${bound.port} 等待释放超时" +
                    "（${PortProbe.DEFAULT_TIMEOUT_MS}ms），交给绑定重试兜底",
            )
        }

        if (wasRunning) log("中继已停止（关闭 APP ${closedCount} 个）")
        controllerId = ""
        port = 0
    }

    private fun sendToAppsQuietly(conn: WebSocket, data: JsonElement): Boolean =
        sendQuietly(conn, RelayProtocol.messageToApp(data))

    /**
     * 发送一帧并报告结果。
     *
     * @param checkBackpressure true 时检查 [WebSocket.hasBufferedData]：连接持续积压
     *        （连续 [MAX_BUFFERED_STREAK] 帧都没被 worker 写出去）时丢弃本帧并计数，
     *        避免慢 APP 让连接 outQueue 无界增长。偶发的"上一帧还没刷完"不丢帧
     *        （否则 hello+controller_attached / 急停三连帧会被误伤）。
     *        握手/关闭/Pong 这类小且必须送达的帧传 false。
     */
    private fun sendQuietly(conn: WebSocket, frame: JsonObject, checkBackpressure: Boolean = true): Boolean {
        return try {
            if (!conn.isOpen || conn.isClosing) return false
            if (checkBackpressure && conn.hasBufferedData()) {
                val streak = bufferedStreaks.merge(conn, 1, Int::plus) ?: 1
                if (streak > MAX_BUFFERED_STREAK) {
                    val count = backpressureDrops.incrementAndGet()
                    val now = System.currentTimeMillis()
                    if (now - lastBackpressureLogAtMs >= BACKPRESSURE_LOG_INTERVAL_MS) {
                        lastBackpressureLogAtMs = now
                        log("发送背压：连接持续积压，丢弃帧（累计 $count）")
                    }
                    return false
                }
            } else {
                bufferedStreaks.remove(conn)
            }
            conn.send(frame.toString())
            true
        } catch (t: Throwable) {
            log(
                "发送帧失败 type=${frame.stringOrNull("type") ?: "-"} " +
                    "clientId=${conns[conn]?.clientId ?: "-"} ${describeThrowable(t)}",
            )
            false
        }
    }

    private fun closeQuietly(conn: WebSocket, code: Int, reason: String) {
        try {
            conn.close(code, reason)
        } catch (_: Throwable) {
            // 连接可能已经关闭
        }
    }

    private fun resolveAddress(bindHost: String, port: Int): InetSocketAddress {
        val host = bindHost.trim().ifBlank { "127.0.0.1" }
        return when (host) {
            "0.0.0.0", "*" -> InetSocketAddress(port)
            "::", "[::]" -> InetSocketAddress(InetAddress.getByName("::"), port)
            else -> InetSocketAddress(InetAddress.getByName(host), port)
        }
    }

    private fun log(line: String) {
        listener.onRelayLog(line)
    }

    private companion object {
        const val START_TIMEOUT_MS = 3_000L
        const val STOP_TIMEOUT_MS = 500

        /** 显式连接失活检测；>2x 业务心跳（30s），对 OkHttp APP 也足够宽松 */
        const val DEFAULT_CONNECTION_LOST_TIMEOUT_S = 120

        const val BACKPRESSURE_LOG_INTERVAL_MS = 2_000L

        /** 连续多少帧都"未刷出"才判定为慢连接（正常连续小帧/急停三连不丢） */
        const val MAX_BUFFERED_STREAK = 64
    }
}
