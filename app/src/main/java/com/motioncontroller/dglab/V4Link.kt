package com.motioncontroller.dglab

import com.motioncontroller.relay.RelayProtocol
import kotlinx.coroutines.flow.StateFlow

/**
 * [DglabLinkApi] 的 V4 实现：
 *
 *  - `relay/V4RelayServer`：Java-WebSocket 服务端，负责 hello / tid attach / message 透传 / 心跳 / idle；
 *  - `V4Controller`：进程内控制器（RPC、设备列表、强度闭环、急停），直接挂在中继上；
 *  - 本类只做契约转接，不持有额外状态（方便 JVM 单测直接 new）。
 *
 * 线程约束：所有方法可在任意线程调用；内部状态由控制器锁保护，网络发送由 Java-WebSocket 串行化。
 */
class V4Link(
    idleTimeoutMs: Long = RelayProtocol.DEFAULT_IDLE_TIMEOUT_MS,
    heartbeatIntervalMs: Long = RelayProtocol.DEFAULT_HEARTBEAT_MS,
    tickMs: Long = DEFAULT_TICK_MS,
    opIntervalMs: Long = DEFAULT_OP_INTERVAL_MS,
    /** Phase 4A：t=0 波形任务实验能力；默认关闭（会绕过强度闭环安全约束） */
    pulseTasksEnabled: Boolean = false,
) : DglabLinkApi {

    private val controller = V4Controller(
        idleTimeoutMs = idleTimeoutMs,
        heartbeatIntervalMs = heartbeatIntervalMs,
        tickMs = tickMs,
        opIntervalMs = opIntervalMs,
        pulseTasksEnabled = pulseTasksEnabled,
    )

    override val state: StateFlow<DglabState> = controller.state

    override val logLines: StateFlow<List<String>> = controller.logLines

    /** 中继是否已判定 idle（5 分钟无 APP 接入）。单机形态只提示，不断开。 */
    val isRelayIdle: Boolean get() = controller.isRelayIdle()

    override fun start(
        bindHost: String,
        port: Int,
        preferredTargetId: String?,
    ) = controller.start(bindHost, port, preferredTargetId)

    override fun stop() = controller.stop()

    override fun setTargetIntensity(channel: Int, target: Int) =
        controller.setTargetIntensity(channel, target)

    override fun resetIntensity(channel: Int) = controller.resetIntensity(channel)

    override fun resetAllIntensities() = controller.resetAllIntensities()

    override fun startPulseTask(spec: PulseTaskSpec): Boolean = controller.startPulseTask(spec)

    override fun clearPulseTasks() = controller.clearPulseTasks()

    override fun clearAllOperate() = controller.clearAllOperate()

    override fun emergencyStop() = controller.emergencyStop()

    override fun clearEmergencyStop() = controller.clearEmergencyStop()

    override fun controlAvailable(): Boolean = controller.controlAvailable()

    override fun clientUrl(hostOverride: String?): String = controller.clientUrl(hostOverride)

    private companion object {
        const val DEFAULT_TICK_MS = 100L
        const val DEFAULT_OP_INTERVAL_MS = 200L
    }
}
