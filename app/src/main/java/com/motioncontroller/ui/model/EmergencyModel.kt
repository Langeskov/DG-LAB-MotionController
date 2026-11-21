package com.motioncontroller.ui.model

import com.motioncontroller.service.EstopResetCheck
import com.motioncontroller.service.EstopResetCheckId
import com.motioncontroller.service.EstopResetCheckStatus
import com.motioncontroller.service.EstopResetFailure
import com.motioncontroller.service.EstopResetFailureCode
import com.motioncontroller.service.EstopResetLinkSnapshot
import com.motioncontroller.service.EstopResetPhase
import com.motioncontroller.service.EstopResetPolicy
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.i18n.UiStrings

/**
 * 全屏 EMERGENCY MODE 的展示模型（纯派生，可 JVM 单测）。
 *
 * 关键安全语义：
 *  - [EmergencyNodeId.DEVICE] 永远不可能是 [EmergencyNodeStatus.ACKED]：
 *    本工程没有 BLE 级停止回执，APP ACK 不能解释为“设备已物理停止”。
 *  - [deviceStopVerified] 在本版本恒为 false（保留字段是为了让 UI 语义显式，而不是留口子）。
 *  - 复位资格由 [EstopResetPolicy] 派生：服务/中继/APP/设备不在线时不允许复位，
 *    只展示阻塞原因（避免用户“点了复位就以为安全”）。
 */
enum class EmergencyNodeId { COMMAND, FRAMES, APP_ACK, DEVICE }

enum class EmergencyNodeStatus {
    /** 命令尚未发出（无引擎/无链路）。 */
    NOT_SENT,
    ISSUED,
    QUEUED,
    PENDING,
    ACKED,
    FAILED,
    /** 设备端状态无法验证（BLE 无回执）。 */
    UNVERIFIED,
}

data class EmergencyNode(val id: EmergencyNodeId, val status: EmergencyNodeStatus)

data class EmergencySnapshot(
    val latched: Boolean,
    val nodes: List<EmergencyNode>,
    val serviceRunning: Boolean,
    val relayRunning: Boolean,
    val appConnected: Boolean,
    val deviceReady: Boolean,
    val intensityTargetA: Int,
    val intensityEstimateA: Int,
    val intensityReportedA: Int?,
    /** 设备是否已被确认停止：本版本恒为 false（无 BLE 回执通道）。 */
    val deviceStopVerified: Boolean,
    /** 当前是否满足复位前置条件（链路在线；不包含 APP ACK——那要到 RESETTING 阶段才确认）。 */
    val canReset: Boolean,
    /** 不满足复位条件时的原因（null = 可复位）。 */
    val resetBlockerCode: EstopResetFailureCode?,
    val resetPhase: EstopResetPhase,
    val resetChecks: List<EstopResetCheck>,
    val resetFailure: EstopResetFailure?,
    val resetInProgress: Boolean,
    /** 本次会话是否已成功完成过一次复位（锁存已解除）。 */
    val resetCompleted: Boolean,
) {
    fun status(id: EmergencyNodeId): EmergencyNodeStatus =
        nodes.firstOrNull { it.id == id }?.status ?: EmergencyNodeStatus.NOT_SENT
}

object EmergencyModel {

    fun derive(state: UiState): EmergencySnapshot {
        val link = state.link
        val latched = state.emergencyStopped

        val commandStatus = when {
            !latched -> EmergencyNodeStatus.NOT_SENT
            link.estopIssued -> EmergencyNodeStatus.ISSUED
            !state.serviceRunning -> EmergencyNodeStatus.FAILED
            else -> EmergencyNodeStatus.NOT_SENT
        }
        val framesStatus = when {
            !latched -> EmergencyNodeStatus.NOT_SENT
            link.estopFramesQueued -> EmergencyNodeStatus.QUEUED
            link.estopIssued -> EmergencyNodeStatus.PENDING
            else -> EmergencyNodeStatus.NOT_SENT
        }
        val appAckStatus = when {
            !latched -> EmergencyNodeStatus.NOT_SENT
            link.estopAppAcked -> EmergencyNodeStatus.ACKED
            else -> EmergencyNodeStatus.PENDING
        }
        // 设备确认永远 UNVERIFIED：没有可用的 BLE 回执通道。
        val deviceStatus = EmergencyNodeStatus.UNVERIFIED

        val precheck = EstopResetPolicy.evaluateLinks(precheckSnapshot(state))
        return EmergencySnapshot(
            latched = latched,
            nodes = listOf(
                EmergencyNode(EmergencyNodeId.COMMAND, commandStatus),
                EmergencyNode(EmergencyNodeId.FRAMES, framesStatus),
                EmergencyNode(EmergencyNodeId.APP_ACK, appAckStatus),
                EmergencyNode(EmergencyNodeId.DEVICE, deviceStatus),
            ),
            serviceRunning = state.serviceRunning,
            relayRunning = link.relayRunning,
            appConnected = link.appConnected,
            deviceReady = link.deviceReady,
            intensityTargetA = link.targetIntensityA,
            intensityEstimateA = link.estimateIntensityA,
            intensityReportedA = link.reportedIntensityA,
            deviceStopVerified = false,
            canReset = latched &&
                !state.estopResetInProgress &&
                precheck == null,
            resetBlockerCode = precheck?.code,
            resetPhase = state.estopResetPhase,
            resetChecks = state.estopResetChecks,
            resetFailure = state.estopResetFailure,
            resetInProgress = state.estopResetInProgress,
            resetCompleted = state.estopResetCompletedAtMs > 0L,
        )
    }

    /** UI 侧前置检查快照：只用于“能否请求复位”的展示，最终判定在 Service 的复位流程里。 */
    private fun precheckSnapshot(state: UiState): EstopResetLinkSnapshot {
        val pipeline = state.pipeline
        return EstopResetLinkSnapshot(
            serviceRunning = state.serviceRunning,
            engineAvailable = state.serviceRunning,
            relayRunning = state.link.relayRunning,
            appConnected = state.link.appConnected,
            deviceReady = state.link.deviceReady,
            stopIssued = state.link.estopIssued,
            framesQueued = state.link.estopFramesQueued,
            appAcked = state.link.estopAppAcked,
            ackReason = state.link.estopAckReason,
            modelLoaded = pipeline.modelLoaded,
            scalerValid = pipeline.scalerValid,
        )
    }
}

// ───────────────────────────── 文案映射（纯函数） ─────────────────────────────

fun UiStrings.emergencyNodeLabel(id: EmergencyNodeId): String = when (id) {
    EmergencyNodeId.COMMAND -> emergencyNodeCommand
    EmergencyNodeId.FRAMES -> emergencyNodeFrames
    EmergencyNodeId.APP_ACK -> emergencyNodeAppAck
    EmergencyNodeId.DEVICE -> emergencyNodeDevice
}

fun UiStrings.emergencyStatusLabel(status: EmergencyNodeStatus): String = when (status) {
    EmergencyNodeStatus.NOT_SENT -> emergencyStatusNotSent
    EmergencyNodeStatus.ISSUED -> emergencyStatusIssued
    EmergencyNodeStatus.QUEUED -> emergencyStatusQueued
    EmergencyNodeStatus.PENDING -> emergencyStatusPending
    EmergencyNodeStatus.ACKED -> emergencyStatusAcked
    EmergencyNodeStatus.FAILED -> emergencyStatusFailed
    EmergencyNodeStatus.UNVERIFIED -> emergencyStatusUnverified
}

fun UiStrings.resetCheckLabel(id: EstopResetCheckId): String = when (id) {
    EstopResetCheckId.SERVICE -> resetCheckService
    EstopResetCheckId.RELAY -> resetCheckRelay
    EstopResetCheckId.APP_LINK -> resetCheckAppLink
    EstopResetCheckId.DEVICE_LINK -> resetCheckDeviceLink
    EstopResetCheckId.STOP_COMMAND -> resetCheckStopCommand
    EstopResetCheckId.APP_ACK -> resetCheckAppAck
    EstopResetCheckId.MODEL -> resetCheckModel
    EstopResetCheckId.SCALER -> resetCheckScaler
    EstopResetCheckId.DEVICE_CONFIRM -> resetCheckDeviceConfirm
}

fun UiStrings.estopResetCheckStatus(status: EstopResetCheckStatus): String = when (status) {
    EstopResetCheckStatus.WAITING -> statusWaiting
    EstopResetCheckStatus.PASS -> statusPass
    EstopResetCheckStatus.FAIL -> statusFail
    EstopResetCheckStatus.UNVERIFIED -> emergencyStatusUnverified
}

fun UiStrings.estopResetFailureText(failure: EstopResetFailure): String = when (failure.code) {
    EstopResetFailureCode.SERVICE_OFFLINE -> resetFailServiceOffline
    EstopResetFailureCode.RELAY_OFFLINE -> resetFailRelayOffline
    EstopResetFailureCode.APP_NOT_CONNECTED -> resetFailAppNotConnected
    EstopResetFailureCode.DEVICE_NOT_CONNECTED -> resetFailDeviceNotConnected
    EstopResetFailureCode.STOP_COMMAND_NOT_SENT -> resetFailStopNotSent
    EstopResetFailureCode.STOP_COMMAND_NOT_ACKED -> resetFailStopNotAcked
    EstopResetFailureCode.MODEL_NOT_READY -> resetFailModelNotReady
    EstopResetFailureCode.SCALER_INVALID -> resetFailScalerInvalid
    EstopResetFailureCode.LINK_REPLACED -> resetFailLinkReplaced
    EstopResetFailureCode.UNKNOWN -> resetFailUnknown
}

fun UiStrings.estopResetBlockerText(code: EstopResetFailureCode): String =
    estopResetFailureText(EstopResetFailure(code))
