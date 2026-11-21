package com.motioncontroller.ui.model

import com.motioncontroller.service.ControlTransition
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.i18n.EnUiStrings
import com.motioncontroller.ui.i18n.UiStrings

/** OPERATION 主界面的控制状态机（由真实后端状态派生，不允许 UI 自行编造）。 */
enum class ControlPhase(val labelEn: String) {
    STANDBY("STANDBY"),
    STARTING("STARTING"),
    RUNNING("RUNNING"),
    STOPPING("STOPPING"),
    FAULT("FAULT"),
    ESTOP_LATCHED("E-STOP LATCHED"),
}

/**
 * START / STOP / E-STOP 的可用性与阻塞原因。
 *
 * @param blockers 启动被阻塞的原因（按优先级；空 = 可以启动）。用于在按钮下方显示原因，
 *                 而不是简单把按钮变灰。
 */
data class ControlSnapshot(
    val phase: ControlPhase,
    val armed: Boolean,
    val estopLatched: Boolean,
    val startEnabled: Boolean,
    val stopEnabled: Boolean,
    val estopEnabled: Boolean,
    val resetEstopEnabled: Boolean,
    val blockers: List<String>,
) {
    val primaryBlocker: String? get() = blockers.firstOrNull()
}

object ControlModel {

    /**
     * 从 UiState 派生控制状态机。纯函数，可 JVM 单测。
     *
     * [strings] 只影响文案（阻塞原因等），不影响任何状态判定；默认英文以保持既有单测语义。
     */
    fun derive(state: UiState, strings: UiStrings = EnUiStrings): ControlSnapshot {
        val estop = state.emergencyStopped
        val faulted = !state.engineLoading && !state.serviceRunning && state.engineError != null
        val phase = when {
            estop -> ControlPhase.ESTOP_LATCHED
            faulted -> ControlPhase.FAULT
            state.controlTransition == ControlTransition.STARTING && state.armed -> ControlPhase.STARTING
            state.controlTransition == ControlTransition.STOPPING -> ControlPhase.STOPPING
            state.armed -> ControlPhase.RUNNING
            else -> ControlPhase.STANDBY
        }
        val blockers = buildBlockers(state, strings)
        return ControlSnapshot(
            phase = phase,
            armed = state.armed,
            estopLatched = estop,
            startEnabled = !estop && blockers.isEmpty(),
            stopEnabled = state.armed && !estop,
            estopEnabled = !estop && (state.serviceRunning || state.engineLoading),
            resetEstopEnabled = estop && state.serviceRunning,
            blockers = blockers,
        )
    }

    /**
     * 启动被阻塞的原因。全部来自真实状态：
     * 本地服务/中继/APP/设备/急停锁存/启动中。识别未就绪**不**阻塞启动
     * （控制会话可以先进入 RUNNING，等待模型给出稳定类别后才产生强度）。
     */
    fun buildBlockers(state: UiState, strings: UiStrings = EnUiStrings): List<String> = buildList {
        if (state.emergencyStopped) {
            add(strings.blockerEstopLatched)
            return@buildList
        }
        if (state.engineLoading) {
            add(strings.blockerServiceStarting)
            return@buildList
        }
        if (!state.serviceRunning) {
            add(state.engineError ?: strings.blockerServiceOffline)
            return@buildList
        }
        if (!state.link.relayRunning) add(strings.blockerRelayOffline)
        if (!state.link.appConnected) add(strings.blockerAppNotConnected)
        if (!state.link.deviceReady) add(strings.blockerDeviceNotConnected)
    }

    /**
     * 归还自动控制（RETURN TO AUTO）的可用性。与 ControllerService.returnToAuto 的守卫保持一致：
     * 模型结果必须新鲜可用（管线运行、有稳定类别、未处于看门狗暂停、年龄 ≤3s），
     * 否则不能归还——绝不沿用陈旧模型结果恢复自动控制。
     */
    fun returnToAutoBlocker(state: UiState, strings: UiStrings = EnUiStrings): String? {
        if (state.manualClass == null) return null
        return when {
            !state.pipeline.running -> strings.autoBlockerModelOffline
            state.prediction == null -> strings.autoBlockerNoResult
            state.recognitionPaused -> strings.autoBlockerPaused
            state.stableClass < 0 -> strings.autoBlockerNotSettled
            state.predictionAgeMs > MAX_AUTO_AGE_MS -> strings.autoBlockerStale
            else -> null
        }
    }

    fun canReturnToAuto(state: UiState): Boolean = returnToAutoBlocker(state) == null

    /** 归还自动时允许的模型数据最大年龄（与 service 的看门狗阈值一致）。 */
    const val MAX_AUTO_AGE_MS = 3000L
}
