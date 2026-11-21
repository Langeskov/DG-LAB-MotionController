package com.motioncontroller.dglab

import kotlinx.coroutines.flow.StateFlow

/**
 * 中继 + V4 控制器的对外契约。
 *
 * 实现（[V4Link]）内部同时持有：
 *  - Java-WebSocket 服务端（DG-LAB APP 作为被控方连入，按 V4 Relay 规则转发）；
 *  - 进程内 V4 控制器（不需要额外 WS 连接，直接与中继对接）；
 *  - 强度闭环（AddIntensity 相对量 → 收敛到目标值）。
 *
 * 线程约束：所有方法均可在主线程调用，实现内部切到自己的线程/协程。
 */
interface DglabLinkApi {

    val state: StateFlow<DglabState>

    /** 最近协议日志（新→旧或旧→新由实现决定，UI 只做展示） */
    val logLines: StateFlow<List<String>>

    /**
     * 启动中继。bindHost: "127.0.0.1"（同机）或 "0.0.0.0"（双机/热点）。
     * 重复调用应先 stop。preferredTargetId 用于保持 APP 侧地址稳定（跨重启复用同一 tid）。
     */
    fun start(bindHost: String, port: Int, preferredTargetId: String? = null)

    fun stop()

    /**
     * 设置目标强度（绝对，0..200）。实现负责：与真实强度做差、分批下发 AddIntensity、节流。
     * channel: 0 = A，1 = B。
     */
    fun setTargetIntensity(channel: Int, target: Int)

    /** 立即归零该通道（SetIntensity t=7, v=0） */
    fun resetIntensity(channel: Int)

    /**
     * 双通道同时归零。
     *
     * 默认实现分两次调用 [resetIntensity]；V4 实现覆盖为「一次优先入队」（两次独立的优先入队会
     * 让第二次清掉第一次还没被写线程取走的帧，见 AGENTS Phase 3 审计 B1）。
     */
    fun resetAllIntensities() {
        resetIntensity(0)
        resetIntensity(1)
    }

    /** 清除所有任务（device.op.clear，无参数） */
    fun clearAllOperate()

    /**
     * Phase 4A：设备波形任务（`device.op` t=0 AppendPulseData）。
     *
     * 默认实现返回 false（不支持/未启用）。V4 实现只在构造时显式传入
     * `pulseTasksEnabled = true` 的实验模式下才可能入队；该能力会绕过强度闭环安全约束，
     * 启用前必须完成 Phase 3 报告 §8 的准入条件。
     *
     * @return true = 帧已入队（不代表 APP/设备已确认播放）；false = 校验失败/链路不可用/未启用
     */
    fun startPulseTask(spec: PulseTaskSpec): Boolean = false

    /**
     * Phase 4A：停止并清除所有设备波形任务（STOP/断连/退出时调用）。
     *
     * 默认空实现；V4 实现会先发 `device.op.clear` 再清本地状态（无活动任务时不发帧）。
     */
    fun clearPulseTasks() {}

    /** 急停：双通道归零 + 清任务 + 锁存（锁存后 setTargetIntensity 不得下发） */
    fun emergencyStop()

    /** 解除急停锁存（必须由 UI 显式确认后调用） */
    fun clearEmergencyStop()

    /** 当前是否具备下发条件：中继已启动 + APP 已接入 + 有可用设备 + 未急停 */
    fun controlAvailable(): Boolean

    /** 给 DG-LAB APP 的连接地址。hostOverride 为空时用当前绑定地址（0.0.0.0 会替换为局域网 IP）。 */
    fun clientUrl(hostOverride: String? = null): String
}

/** 设备（郊狼主机）摘要 */
data class DglabDevice(
    val slotId: String,
    val name: String,
    val type: String,
    val connected: Boolean = true,
    /** 设备电量（APP `props.power`，0-100 之类；APP 未上报为 null） */
    val power: Int? = null,
    /** 设备/固件版本（APP `props.version`，原始文本；未上报为 null） */
    val version: String? = null,
    /** 通道 A 静音状态（slotState；未上报/不支持为 null，**不得**默认当作 false） */
    val channelAMuted: Boolean? = null,
    /** 通道 B 静音状态（同上） */
    val channelBMuted: Boolean? = null,
    /**
     * 通道 A 状态（COYOTE_030 `props.channelAStatus`；0 无输出/过低、1 开路、2 正常、3 输出故障、4 通道屏蔽；
     * 未上报/不支持为 null）。来自 APP 上报，**不是**本工程实测语义。
     */
    val channelAStatus: Int? = null,
    /** 通道 B 状态（同上） */
    val channelBStatus: Int? = null,
)

/**
 * 急停命令链中单条命令的回执状态（Phase 3 语义修正：A 归零 / B 归零 / clear 必须分别可追踪）。
 *
 * 只反映 **APP 层 RPC 回执**，不代表 BLE 设备端已物理停止。
 */
enum class EstopCommandKind {
    /** SetIntensity t=7 v=0，通道 A */
    A_ZERO,

    /** SetIntensity t=7 v=0，通道 B */
    B_ZERO,

    /** device.op.clear 清任务 */
    CLEAR,
}

/** 单条急停命令的 APP 回执结果。 */
enum class EstopCommandAckState {
    /** 帧已发出，尚未收到有效成功回执（含超时未响应） */
    PENDING,

    /** APP 返回了成功语义的回执（reason 缺失 / completed / cleared 等） */
    ACKED,

    /** APP 返回错误，或 reason 为 replaced / cancelled 等非成功语义（不能算确认） */
    UNCONFIRMED,
}

/** 急停命令链里一条命令的可追踪结果。 */
data class EstopCommandResult(
    val kind: EstopCommandKind,
    val state: EstopCommandAckState,
    /** APP 回执里的 reason 原文（错误信息/失败 reason）；无则 null */
    val reason: String? = null,
)

/** 中继/控制器状态快照，UI 直接渲染 */
data class DglabState(
    val relayRunning: Boolean = false,
    val bindHost: String = "127.0.0.1",
    val port: Int = 9998,
    /** 控制器 ID，同时是 APP 连接用的 tid */
    val targetId: String = "",
    /** 已接入的 APP clientId 列表（V4 支持 1:N） */
    val appClientIds: List<String> = emptyList(),
    val devices: List<DglabDevice> = emptyList(),
    val activeSlotId: String? = null,
    /** APP 上报的当前强度（slots.patch props）；未知时为 null */
    val reportedIntensityA: Int? = null,
    val reportedIntensityB: Int? = null,
    /** 控制器最近一次下发的目标强度 */
    val targetIntensityA: Int = 0,
    val targetIntensityB: Int = 0,
    /**
     * 控制器对「APP 当前强度」的闭环估计（AddIntensity 累加 / APP 上报覆盖）。
     * 这是**发送侧估计**，不等于设备确认值；设备确认值只能看 [reportedIntensityA]/[reportedIntensityB]。
     */
    val estimateIntensityA: Int = 0,
    val estimateIntensityB: Int = 0,
    /** 最近一次 device.op / device.op.clear 响应里的 reason（APP 处理结果）；无响应为 null */
    val lastOpResponseReason: String? = null,
    val emergencyStopped: Boolean = false,
    /**
     * 急停命令的传递阶段（UI 必须区分，不能把“已发出”当成“设备已确认”）：
     *  - [estopIssued]：控制器已经执行急停逻辑（入口被调用，锁存已生效）；
     *  - [estopFramesQueued]：A/B 归零 + device.op.clear 帧已进入发送队列（链路当时可用）；
     *  - [estopAppAcked]：急停命令链（[EstopCommandKind] A_ZERO/B_ZERO/CLEAR 各帧）**全部**收到了
     *    成功语义的 APP 回执（[estopCommandResults] 逐条可查）；与急停无关的 device.op 响应、
     *    错误响应、reason=replaced/cancelled、超时未响应都**不会**置位；它仍只是 APP 层确认；
     *  - 设备端（BLE）是否真正执行，本实现没有可用的确认通道，UI 不得声称已确认。
     */
    val estopIssued: Boolean = false,
    val estopFramesQueued: Boolean = false,
    val estopAppAcked: Boolean = false,
    val estopIssuedAtMs: Long = 0L,
    /**
     * 急停命令链回执摘要（例如 `A_ZERO:ACKED(completed),B_ZERO:ACKED(completed),CLEAR:ACKED(ok)`；
     * 失败时含 `NOT_ACKED(replaced/cancelled/timeout/disconnected/错误码)`）；无急停为 null。
     * 只描述 **APP 层 RPC 回执**，不代表 BLE 设备端已物理停止。
     */
    val estopAckReason: String? = null,
    /** 急停命令链逐条回执（A 归零 / B 归零 / clear 分别可追踪）；未急停为空 */
    val estopCommandResults: List<EstopCommandResult> = emptyList(),
    /**
     * Phase 4A：设备波形任务（t=0 AppendPulseData）状态；默认关闭时恒为空。
     * 仅表示 APP 层任务生命周期，**不代表 BLE 设备物理输出确认**。
     */
    val pulseTasks: List<PulseTaskStatus> = emptyList(),
    /** 发送队列积压帧数（工程诊断） */
    val queuedOutboundCount: Int = 0,
    /** 因队列积压被丢弃的强度帧累计数（工程诊断；安全帧走优先路径） */
    val droppedOpCount: Long = 0,
    val lastError: String? = null,

    // ───────── 连接诊断（问题4：偶发「DG-LAB APP 无法连接」排查；由 V4Controller 维护）─────────

    /**
     * 当前 relay 会话内**已完成的 WebSocket 握手次数**（含随后因 tid 不匹配/无 tid 被拒的连接）。
     *
     * 诊断用法：APP 显示「连接失败」但本值不涨 → 握手在协议层就没完成；
     * 本值涨了但 [lastCloseCode] = 1006 → 握手完成但 APP 拒绝了响应（先查 AGENTS 3.2 的 Connection 头）。
     * relay 重启（start）清零；默认 0。
     */
    val handshakeCount: Int = 0,

    /**
     * 最近一次握手摘要，一行：`UA=<User-Agent> Connection=<Connection 头>`。
     * 用于确认握手来源（DG-LAB APP 的 OkHttp 3.14.9 会发 `Connection: Upgrade; close`，见 AGENTS 3.2）。
     * 尚无握手记录为 null；relay 重启清空。
     */
    val lastHandshakeSummary: String? = null,

    /**
     * 最近一次**被拒连接**的原因（一行），例如 `tid=xxx 不匹配 controller=yyy` 或 `无 tid：...`。
     * 尚无拒绝记录为 null；relay 重启清空。
     */
    val lastRejectReason: String? = null,

    /**
     * 最近一次 **APP 断开**的 WebSocket 关闭码（如 4000/4001/1006）；被拒绝的连接不算 APP 断开。
     * 尚无断开记录为 null；relay 重启清空。
     */
    val lastCloseCode: Int? = null,

    /**
     * 最近一次 **APP 断开**的关闭原因（可能为空串，表示对端没有给原因）。
     * 尚无断开记录为 null；relay 重启清空。
     */
    val lastCloseReason: String? = null,
) {
    val appConnected: Boolean get() = appClientIds.isNotEmpty()
    val deviceReady: Boolean get() = devices.isNotEmpty() && activeSlotId != null
}
