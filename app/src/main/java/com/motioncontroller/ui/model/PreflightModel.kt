package com.motioncontroller.ui.model

import com.motioncontroller.service.UiState
import com.motioncontroller.ui.i18n.EnUiStrings
import com.motioncontroller.ui.i18n.UiStrings
import com.motioncontroller.ui.i18n.fmt

/** PRE-FLIGHT 自检项状态（UI 只展示这四种语义）。 */
enum class CheckStatus(val labelEn: String) {
    PASS("PASS"),
    WAITING("WAITING"),
    FAIL("FAIL"),
    DISCONNECTED("DISCONNECTED"),
}

/**
 * 一条自检结果。
 *
 * [label] / [detail] 由 [UiStrings] 提供（模型层不引 Android 依赖，文案经参数注入）；
 * [critical] 为 true 的项决定“能否进入 OPERATION / 能否执行启动流程”；
 * 非关键项（GNSS、设备）只影响能力与提示，不阻塞进入主界面。
 * DG-LAB APP 连接为关键项：未连接绝不允许进入控制页（真机反馈：曾出现未连接就进入控制页）。
 */
data class PreflightCheck(
    val id: String,
    val label: String,
    val status: CheckStatus,
    val detail: String,
    val critical: Boolean,
)

data class PreflightSnapshot(
    val checks: List<PreflightCheck>,
    /** 是否允许进入 OPERATION：所有关键项 PASS。 */
    val canEnter: Boolean,
    /** 是否存在关键项 FAIL（失败不能被启动动效掩盖）。 */
    val criticalFailure: Boolean,
    /** 是否存在关键项仍等待（WAITING / DISCONNECTED）。 */
    val waiting: Boolean,
    /** 启动动效只允许在 canEnter 且无失败时播放。 */
    val bootSequenceAllowed: Boolean,
    /** 首个阻塞进入的项（优先 FAIL，其次 DISCONNECTED，再次 WAITING），可为 null。 */
    val blocker: PreflightCheck? = null,
) {
    fun check(id: String): PreflightCheck? = checks.firstOrNull { it.id == id }
}

/**
 * PRE-FLIGHT 自检派生（纯函数，可 JVM 单测）。
 *
 * 状态真实来源：
 *  - LOCAL SERVICE / RELAY：ControllerService 的引擎状态 + V4 中继状态；
 *  - INFERENCE / SCALER：MotionPipeline 状态（模型是否加载、scaler 是否真实校准）；
 *  - GNSS / APP / DEVICE：传感器管线与 DG-LAB 链路真实状态。
 * 不在此处展示分类置信度、推理耗时、缓冲区等工程细节（移入 ENGINEERING）。
 *
 * 文案全部来自 [strings]（默认英文，保持既有单测语义）；判定逻辑与语言无关。
 */
object PreflightModel {

    fun derive(
        state: UiState,
        serviceBound: Boolean,
        strings: UiStrings = EnUiStrings,
    ): PreflightSnapshot {
        val engineError = state.engineError
        val pipeline = state.pipeline
        val link = state.link

        // 1) 本地服务 + 前台引擎
        val service = when {
            !serviceBound -> PreflightCheck(
                id = "service",
                label = strings.checkLocalService,
                status = CheckStatus.WAITING,
                detail = strings.detailBindingService,
                critical = true,
            )
            state.engineError != null && !state.engineLoading -> PreflightCheck(
                id = "service",
                label = strings.checkLocalService,
                status = CheckStatus.FAIL,
                detail = engineError ?: strings.detailEngineFailedFallback,
                critical = true,
            )
            state.engineLoading -> PreflightCheck(
                id = "service",
                label = strings.checkLocalService,
                status = CheckStatus.WAITING,
                detail = strings.detailStartingEngine,
                critical = true,
            )
            state.serviceRunning -> PreflightCheck(
                id = "service",
                label = strings.checkLocalService,
                status = CheckStatus.PASS,
                detail = strings.detailForegroundEngine,
                critical = true,
            )
            else -> PreflightCheck(
                id = "service",
                label = strings.checkLocalService,
                status = CheckStatus.FAIL,
                detail = engineError ?: strings.detailEngineNotRunning,
                critical = true,
            )
        }

        // 2) V4 中继
        val relay = when {
            state.engineLoading || !serviceBound -> PreflightCheck(
                id = "relay",
                label = strings.checkV4Relay,
                status = CheckStatus.WAITING,
                detail = strings.detailWaitingEngine,
                critical = true,
            )
            engineError != null -> PreflightCheck(
                id = "relay",
                label = strings.checkV4Relay,
                status = CheckStatus.FAIL,
                detail = engineError,
                critical = true,
            )
            link.relayRunning -> PreflightCheck(
                id = "relay",
                label = strings.checkV4Relay,
                status = CheckStatus.PASS,
                detail = fmt(strings.detailRelayListening, link.targetId.ifBlank { "-" }),
                critical = true,
            )
            else -> PreflightCheck(
                id = "relay",
                label = strings.checkV4Relay,
                status = CheckStatus.FAIL,
                detail = strings.detailRelayNotListening,
                critical = true,
            )
        }

        // 3) 运动识别模型
        val modelLoaded = pipeline.modelLoaded
        val model = when {
            modelLoaded -> PreflightCheck(
                id = "model",
                label = strings.checkMotionModel,
                status = CheckStatus.PASS,
                detail = strings.detailModelLoaded,
                critical = true,
            )
            pipeline.lastError != null && !modelLoaded -> PreflightCheck(
                id = "model",
                label = strings.checkMotionModel,
                status = CheckStatus.FAIL,
                detail = pipeline.lastError,
                critical = true,
            )
            else -> PreflightCheck(
                id = "model",
                label = strings.checkMotionModel,
                status = CheckStatus.WAITING,
                detail = strings.detailLoadingModel,
                critical = true,
            )
        }

        // 4) Scaler（归一化参数）：无效则模型输出不可信，视为关键失败
        val scaler = when {
            pipeline.scalerValid -> PreflightCheck(
                id = "scaler",
                label = strings.checkScaler,
                status = CheckStatus.PASS,
                detail = strings.detailScalerCalibrated,
                critical = true,
            )
            pipeline.lastError != null && pipeline.lastError.contains("scaler", ignoreCase = true) ->
                PreflightCheck(
                    id = "scaler",
                    label = strings.checkScaler,
                    status = CheckStatus.FAIL,
                    detail = pipeline.lastError,
                    critical = true,
                )
            pipeline.modelLoaded && !pipeline.scalerValid -> PreflightCheck(
                id = "scaler",
                label = strings.checkScaler,
                status = CheckStatus.FAIL,
                detail = strings.detailScalerInvalid,
                critical = true,
            )
            else -> PreflightCheck(
                id = "scaler",
                label = strings.checkScaler,
                status = CheckStatus.WAITING,
                detail = strings.detailLoadingScaler,
                critical = true,
            )
        }

        // 5) GNSS 速度：非关键（无定位时模型仍可运行，准确率下降）
        val gnss = when {
            pipeline.gpsAvailable -> PreflightCheck(
                id = "gnss",
                label = strings.checkGnssSpeed,
                status = CheckStatus.PASS,
                detail = "${formatSpeedKmh(pipeline.gpsSpeedKmh)} km/h",
                critical = false,
            )
            pipeline.gpsEverFix -> PreflightCheck(
                id = "gnss",
                label = strings.checkGnssSpeed,
                status = CheckStatus.WAITING,
                detail = fmt(strings.detailFixStale, formatAgeMs(pipeline.gpsFixAgeMs)),
                critical = false,
            )
            else -> PreflightCheck(
                id = "gnss",
                label = strings.checkGnssSpeed,
                status = CheckStatus.WAITING,
                detail = strings.detailNoFix,
                critical = false,
            )
        }

        // 6) DG-LAB APP（关键：未连接不得进入控制页，防止误进 OPERATION）
        val app = PreflightCheck(
            id = "app",
            label = strings.checkDgLabApp,
            status = if (link.appConnected) CheckStatus.PASS else CheckStatus.DISCONNECTED,
            detail = if (link.appConnected) {
                fmt(strings.detailClientAttached, link.appClientIds.size)
            } else {
                strings.detailAppConnectHint
            },
            critical = true,
        )

        // 7) 郊狼主机（非关键：无设备仍可进 OPERATION 查看，但 START 会被阻塞并显示原因）
        val device = PreflightCheck(
            id = "device",
            label = strings.checkDevice,
            status = if (link.deviceReady) CheckStatus.PASS else CheckStatus.DISCONNECTED,
            detail = if (link.deviceReady) {
                link.devices.firstOrNull { it.slotId == link.activeSlotId }?.name?.ifBlank { strings.coyoteDevice }
                    ?: strings.coyoteDevice
            } else {
                strings.detailDeviceConnectHint
            },
            critical = false,
        )

        // 8) 急停复位后的恢复提醒（非关键）：锁存已解除，但设备停机状态无法通过 BLE 验证。
        //    必须持续展示，直到用户重新 START（在 OPERATION 中显式接受）为止。
        val estopRecovery = if (state.estopDeviceStopUnverified) {
            listOf(
                PreflightCheck(
                    id = "estop_recovery",
                    label = strings.checkEstopRecovery,
                    status = CheckStatus.WAITING,
                    detail = strings.detailEstopRecoveryUnverified,
                    critical = false,
                ),
            )
        } else {
            emptyList()
        }

        val checks = listOf(service, relay, model, scaler, gnss, app, device) + estopRecovery
        val criticalChecks = checks.filter { it.critical }
        val criticalFailure = criticalChecks.any { it.status == CheckStatus.FAIL }
        val waiting = criticalChecks.any { it.status != CheckStatus.PASS }
        val canEnter = criticalChecks.all { it.status == CheckStatus.PASS }
        return PreflightSnapshot(
            checks = checks,
            canEnter = canEnter,
            criticalFailure = criticalFailure,
            waiting = waiting,
            bootSequenceAllowed = canEnter,
            blocker = buildBlocker(criticalChecks),
        )
    }

    /**
     * 首个阻塞项（给“进入”按钮副标题/失败横幅用）：
     * FAIL 最优先（要展示错误详情），其次 DISCONNECTED（等待外部连接），最后 WAITING。
     */
    private fun buildBlocker(criticalChecks: List<PreflightCheck>): PreflightCheck? {
        val pending = criticalChecks.filter { it.status != CheckStatus.PASS }
        return pending.firstOrNull { it.status == CheckStatus.FAIL }
            ?: pending.firstOrNull { it.status == CheckStatus.DISCONNECTED }
            ?: pending.firstOrNull()
    }
}
