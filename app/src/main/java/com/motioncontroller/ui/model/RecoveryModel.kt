package com.motioncontroller.ui.model

import com.motioncontroller.service.UiState
import com.motioncontroller.ui.i18n.EnUiStrings
import com.motioncontroller.ui.i18n.UiStrings

/** 恢复检查快照：只读现有 UiState，不启动/重启任何服务或连接。 */
data class RecoverySnapshot(
    val checks: List<PreflightCheck>,
    /** 是否允许恢复 OPERATION：既有启动门禁通过，且控制确实处于 DISARMED。 */
    val canRestore: Boolean,
    /** 首个阻塞项（FAIL/DISCONNECTED/WAITING 优先级同 PreflightModel），可为 null。 */
    val blocker: PreflightCheck? = null,
) {
    val passCount: Int get() = checks.count { it.status == CheckStatus.PASS }
    val totalCount: Int get() = checks.size
}

/**
 * E-STOP 复位后的恢复检查（纯函数，可 JVM 单测）。
 *
 * 设计约束：
 *  - 复用 [PreflightModel] 的既有门禁（LOCAL SERVICE / RELAY / MODEL / SCALER / APP 为关键项，
 *    GNSS / DEVICE / E-STOP RECOVERY 为非关键项），**不复制第二套连接检查规则**；
 *  - 只检查当前运行状态，不触发引擎重启、不重新握手、不重启 Relay；
 *  - DEVICE UNVERIFIED / 设备未连接不会被本模型误判为“APP 断连”：它们不阻塞恢复（与既有业务一致，
 *    但会显示真实状态并由 START 门禁继续阻塞）；
 *  - 额外要求控制会话 DISARMED（复位后必须保持未启动；即使异常 armed，也不自动进入 OPERATION）。
 */
object RecoveryModel {

    private val displayOrder = listOf(
        "service",
        "relay",
        "app",
        "device",
        "model",
        "scaler",
        "gnss",
        "estop_recovery",
    )

    fun derive(
        state: UiState,
        serviceBound: Boolean,
        strings: UiStrings = EnUiStrings,
    ): RecoverySnapshot {
        val preflight = PreflightModel.derive(state, serviceBound, strings)
        val control = PreflightCheck(
            id = "control_session",
            label = strings.recoveryCheckControlSession,
            status = if (!state.armed && !state.emergencyStopped) CheckStatus.PASS else CheckStatus.FAIL,
            detail = if (!state.armed && !state.emergencyStopped) {
                strings.recoveryControlDisarmedDetail
            } else {
                strings.recoveryControlActiveDetail
            },
            critical = true,
        )
        val checks = preflight.checks.sortedBy { check ->
            displayOrder.indexOf(check.id).let { if (it < 0) displayOrder.size else it }
        } + control
        val canRestore = preflight.canEnter &&
            control.status == CheckStatus.PASS &&
            !state.emergencyStopped
        val blocker = when {
            control.status != CheckStatus.PASS -> control
            else -> preflight.blocker
        }
        return RecoverySnapshot(
            checks = checks,
            canRestore = canRestore,
            blocker = blocker,
        )
    }
}
