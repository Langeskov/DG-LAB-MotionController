package com.motioncontroller.ui.i18n

import com.motioncontroller.mapping.WaveShape
import com.motioncontroller.ml.MotionClass
import com.motioncontroller.ui.model.CheckStatus
import com.motioncontroller.ui.model.ConnectionMode
import com.motioncontroller.ui.model.ControlPhase
import com.motioncontroller.ui.model.MappingSource
import com.motioncontroller.ui.model.SpeedStatus
import java.util.Locale

/**
 * 全部界面文案（纯 Kotlin 接口，无 Android/Compose 依赖，可 JVM 单测）。
 *
 * 约定：
 *  - **全部以属性声明**，[ZhUiStrings] / [EnUiStrings] 一一实现（编译器保证键完整）；
 *    单测用反射遍历本接口的 String 属性断言「无空串、模板占位符一致」。
 *  - 需要拼接动态值的文案用 `{0}` `{1}` 占位符 + [fmt]，不用 `String.format`
 *    （避免正文里的 `%` 被误解析）。
 *  - 中文（[ZhUiStrings]）为第一语言，按中文习惯重写而非逐词翻译；
 *    英文（[EnUiStrings]）保留原有设备丝印风格文案。
 *  - 术语统一：强度 / 波形 / 急停 / 人工接管 / 自检 / 中继 / 设备 /
 *    开始控制 / 停止控制 / 连接 / 速度 / 识别 / 推理。
 *  - 按钮用动词短语（开始控制、复制地址），说明句完整简洁。
 */
interface UiStrings {

    // ───────────────────────── 通用 / 导航 ─────────────────────────
    val appName: String
    /** 品牌视觉副标题（拉丁字母，不随语言翻译）。 */
    val brandSubtitle: String
    val navBack: String
    val cancel: String
    val reset: String
    val unknown: String
    val on: String
    val off: String
    val yes: String
    val no: String
    val trueText: String
    val falseText: String
    val showDetails: String
    val hideDetails: String

    // ───────────────────────── 系统交互（Toast / 分享） ─────────────────────────
    val toastCopied: String
    val toastNothingToCopy: String
    val toastReturnToAutoRejected: String
    val copyLabelAddress: String
    val shareSubjectLogs: String
    val shareSubjectDiagnostics: String
    val shareChooserDiagnostics: String

    // ───────────────────────── 三状态页面 ─────────────────────────
    val screenPreflight: String
    val screenOperation: String
    val screenEngineering: String

    // ───────────────────────── 启动动效 ─────────────────────────
    val bootInitializing: String
    val bootLinkEstablished: String
    val bootSystemReady: String
    /** 占位符：{0} = 阶段号（01/02/03）。 */
    val bootStageProgress: String
    val tapToSkip: String

    // ───────────────────────── 连接模式 ─────────────────────────
    val modeSingleName: String
    val modeMultiName: String
    val modeSingleDesc: String
    val modeMultiDesc: String
    val modeHintLocal: String
    val modeHintLan: String

    // ───────────────────────── PRE-FLIGHT ─────────────────────────
    /** 占位符：{0} = 版本号。 */
    val versionLabel: String
    val connectionModeTitle: String
    val modeMultiLan: String
    val modeSingleLocal: String
    val restartingRelay: String
    val markerRestarting: String
    val markerActive: String
    val markerSelect: String
    val linkPanelTitle: String
    val tagLan: String
    val tagLocal: String
    val copyAddress: String
    val relayNotReady: String
    val showQr: String
    val hideQr: String
    val qrContentDescription: String
    val scanWithApp: String
    val qrUnavailableAddress: String
    val qrUnavailableEncode: String
    val addressNotRunning: String
    val preflightCheckTitle: String
    val statusReady: String
    val statusWaiting: String
    val statusFail: String
    val checksIncomplete: String
    /** 占位符：{0} = 自检项名，{1} = 详情。 */
    val blockedWaitingFor: String
    val criticalCheckFailed: String
    val enterOperation: String
    val preflightFailed: String
    val retry: String
    val restartingEngine: String

    // ───────────────────────── 自检项（PreflightModel） ─────────────────────────
    val checkLocalService: String
    val checkV4Relay: String
    val checkMotionModel: String
    val checkScaler: String
    val checkGnssSpeed: String
    val checkDgLabApp: String
    val checkDevice: String

    // 自检详情
    val detailBindingService: String
    val detailEngineFailedFallback: String
    val detailStartingEngine: String
    val detailForegroundEngine: String
    val detailEngineNotRunning: String
    val detailWaitingEngine: String
    /** 占位符：{0} = targetId。 */
    val detailRelayListening: String
    val detailRelayNotListening: String
    val detailModelLoaded: String
    val detailLoadingModel: String
    val detailScalerCalibrated: String
    val detailScalerInvalid: String
    val detailLoadingScaler: String
    /** 占位符：{0} = 定位年龄。 */
    val detailFixStale: String
    val detailNoFix: String
    /** 占位符：{0} = 客户端数量。 */
    val detailClientAttached: String
    val detailAppConnectHint: String
    val coyoteDevice: String
    val detailDeviceConnectHint: String

    // ───────────────────────── 自检状态（CheckStatus） ─────────────────────────
    val statusPass: String
    val statusDisconnected: String

    // ───────────────────────── 控制状态机（ControlPhase） ─────────────────────────
    val phaseStandby: String
    val phaseStarting: String
    val phaseStopping: String
    val phaseFault: String

    // ───────────────────────── 映射来源（MappingSource） ─────────────────────────
    val mappingSourceAuto: String
    val manualOverride: String

    // ───────────────────────── 启动阻塞原因（ControlModel） ─────────────────────────
    val blockerEstopLatched: String
    val blockerServiceStarting: String
    val blockerServiceOffline: String
    val blockerRelayOffline: String
    val blockerAppNotConnected: String
    val blockerDeviceNotConnected: String

    // ───────────────────────── 归还自动的阻塞原因 ─────────────────────────
    val autoBlockerModelOffline: String
    val autoBlockerNoResult: String
    val autoBlockerPaused: String
    val autoBlockerNotSettled: String
    val autoBlockerStale: String

    // ───────────────────────── 速度（SpeedStatus） ─────────────────────────
    val speedLive: String
    val speedStale: String
    val speedUnavailable: String
    val speedDetailLive: String
    val speedDetailStale: String
    val speedDetailUnavailable: String

    // ───────────────────────── 运动类别（MotionClass） ─────────────────────────
    val motionStationary: String
    val motionWalking: String
    val motionCycling: String
    val motionCar: String
    val motionTrain: String
    val motionPending: String
    val waitingForRecognition: String

    // ───────────────────────── OPERATION ─────────────────────────
    val warnRecognitionPausedTitle: String
    val warnRecognitionPausedBody: String
    val engineFaultTitle: String
    val tagEstop: String
    val lampApp: String
    val lampDev: String
    val tagAuto: String
    val tagManual: String
    val groundSpeed: String
    val fixActive: String
    val noFix: String
    val speedStaleNote: String
    val speedUnavailableNote: String
    val motionMode: String
    val controlAuthority: String
    val authorityAutoDesc: String
    val authorityManualDesc: String
    val takeManualControl: String
    val takeManualDisabledEstop: String
    val takeManualArmedNote: String
    val returnToAuto: String
    val returnToAutoSubtitle: String
    val controlSuspendedEstop: String
    val outputProfile: String
    val outBase: String
    val outOffset: String
    val outTarget: String
    val outApplied: String
    /** 占位符：{0} = 下发目标 A，{1} = 估计 A，{2} = APP 上报。 */
    val sentEstApp: String
    val outComputed: String
    /** 占位符：{0} = 计算值，{1} = 基准，{2} = 带符号偏移。 */
    val computedValue: String
    val outClamped: String
    /** 占位符：{0} = 钳制值，{1} = 安全上限。 */
    val clampedValue: String
    val outMapping: String
    val sentTarget: String
    val appReported: String
    /** 占位符：{0} = 偏移限幅。 */
    val manualOffsetLimit: String
    val outputLayersNote: String
    val safetyLimit: String
    val systemReadiness: String
    /** OPERATION readiness 分组：控制链路（RELAY/APP/DEVICE）。 */
    val controlLink: String
    /** OPERATION readiness 分组：运动识别能力（MODEL/SCALER/GNSS）。 */
    val motionEngine: String
    val suspended: String
    val notReady: String
    val readinessRelay: String
    val readinessApp: String
    val readinessDevice: String
    val readinessModel: String
    val readinessScaler: String
    val readinessGnss: String
    val controlEngine: String
    val startControl: String
    val stopControl: String
    /** 占位符：{0} = 映射来源（自动 / 人工接管）。 */
    val startSubtitle: String
    val stopSubtitle: String
    val emergencyStop: String
    val estopSubtitleOnline: String
    val estopSubtitleOffline: String
    val estopLatched: String
    val resetRequired: String
    val estopPhaseCommand: String
    val estopPhaseFrames: String
    val estopPhaseAppAck: String
    val estopPhaseDevice: String
    val estopValueIssued: String
    val estopValueQueued: String
    val estopValueNotQueued: String
    /** 占位符：{0} = APP 回执 reason。 */
    val estopValueAcked: String
    val estopValuePending: String
    val estopValueUnverified: String
    val resetEstop: String
    val resetEstopSubtitle: String
    val resetEstopDisabledSubtitle: String
    val resetEstopDialogTitle: String
    val resetEstopDialogBody: String

    // ───────────────────────── E-STOP 全屏紧急模式 ─────────────────────────
    val emergencyTitle: String
    val emergencyActivated: String
    /** 顶部循环滚动警报文字（拉丁/中文均可，含 `///` 分隔符）。 */
    val emergencyBorderTop: String
    /** 底部循环滚动警报文字（与顶部不同内容建立层次）。 */
    val emergencyBorderBottom: String
    val emergencySessionSuspended: String
    val emergencySessionSuspendedBody: String
    val emergencyControlLocked: String
    val emergencyControlLockedBody: String
    val emergencyCommandChain: String
    val emergencyNodeCommand: String
    val emergencyNodeFrames: String
    val emergencyNodeAppAck: String
    val emergencyNodeDevice: String
    val emergencyStatusIssued: String
    val emergencyStatusQueued: String
    val emergencyStatusPending: String
    val emergencyStatusAcked: String
    val emergencyStatusFailed: String
    val emergencyStatusNotSent: String
    val emergencyStatusUnverified: String
    val emergencyDeviceUnverified: String
    val emergencyDeviceUnverifiedBody: String
    val emergencyLinkStatus: String
    val emergencyLinkRelay: String
    val emergencyLinkApp: String
    val emergencyLinkDevice: String
    val emergencyLinkIntensity: String
    val emergencyResetSection: String
    /** “复位不会自动恢复控制输出”。 */
    val emergencyResetHint: String
    val emergencyResetBlocked: String
    val emergencyResetDialogTitle: String
    val emergencyResetDialogBody: String
    val emergencyResetRequested: String
    val emergencyResetting: String
    val emergencySelfChecking: String
    val emergencyResetFailed: String
    val emergencyResetRetry: String
    val emergencyResetProgressNote: String
    val emergencyCannotLeave: String
    val emergencyReissueStop: String
    val emergencyRetryRelay: String
    val emergencyNoDeviceAck: String
    /** 复位成功后的退出转场：E-STOP CLEARED（第一段）。 */
    val emergencyExitClearedTag: String
    val emergencyExitClearedTitle: String
    val emergencyExitClearedBody: String
    /** 复位成功后的退出转场：SYSTEM REINITIALIZING（第二段）。 */
    val emergencyExitReinitTag: String
    val emergencyExitReinitTitle: String
    val emergencyExitReinitBody: String
    /** 恢复检查通过、回到 OPERATION 后的短暂提示。 */
    val emergencyRestoredTag: String
    val emergencyRestoredTitle: String
    val emergencyRestoredBody: String
    val recoveryCheckControlSession: String
    val recoveryControlDisarmedDetail: String
    val recoveryControlActiveDetail: String
    /** 占位符：{0} = 通过项数，{1} = 总项数。 */
    val recoveryPassCount: String
    val recoverySessionNote: String
    val recoveryCheckingTag: String

    // 复位检查项（EstopResetCheckId → 文案）
    val resetCheckService: String
    val resetCheckRelay: String
    val resetCheckAppLink: String
    val resetCheckDeviceLink: String
    val resetCheckStopCommand: String
    val resetCheckAppAck: String
    val resetCheckModel: String
    val resetCheckScaler: String
    val resetCheckDeviceConfirm: String

    // 复位失败原因（EstopResetFailureCode → 文案）
    val resetFailServiceOffline: String
    val resetFailRelayOffline: String
    val resetFailAppNotConnected: String
    val resetFailDeviceNotConnected: String
    val resetFailStopNotSent: String
    val resetFailStopNotAcked: String
    val resetFailModelNotReady: String
    val resetFailScalerInvalid: String
    val resetFailLinkReplaced: String
    val resetFailUnknown: String

    // 复位成功后 PRE-FLIGHT 的恢复提醒
    val checkEstopRecovery: String
    val detailEstopRecoveryUnverified: String

    // ───────────────────────── 本地波形（WavePanel） ─────────────────────────
    val wavePanelTitle: String
    val wavePanelDesc: String
    /** 紧凑摘要里的诚实标注：本地强度包络 ≠ 设备脉冲波形。 */
    val wavePanelShortNote: String
    val waveStatusModulating: String
    val waveStatusEnabled: String
    val waveStatusOff: String
    val waveShapeFlat: String
    val waveShapeSine: String
    val waveShapeTriangle: String
    val waveShapeSquare: String
    val waveShapeSawtooth: String
    val waveShapeBurst: String
    val waveDurationContinuous: String
    val waveDuration30: String
    val waveDuration60: String
    val waveDuration300: String
    /** 占位符：{0} = 自定义时长（如 120s）。 */
    val waveDurationCustom: String
    val waveShapeTitle: String
    val frequency: String
    val depth: String
    val duration: String
    val envelope: String
    val waveBaseOutput: String
    val waveModOutput: String
    val waveRemaining: String
    val waveArmedNote: String

    // ───────────────────────── ENGINEERING 通用 ─────────────────────────
    val engineeringTitle: String
    val diagnosticConsole: String
    val tabConnection: String
    val tabMotion: String
    val tabOutput: String
    val tabDiagnostics: String

    // ───────────────────────── ENGINEERING · CONNECTION ─────────────────────────
    val linkStatus: String
    val statusOnline: String
    val statusOffline: String
    val statusListening: String
    val statusConnected: String
    val statusLatched: String
    val statusClear: String
    val engineRestartInProgress: String
    /** 占位符：{0} = 客户端数量。 */
    val clientsCount: String
    /** 占位符：{0} = 槽位 ID。 */
    val activeSlotValue: String
    val serverEndpoint: String
    val bindHost: String
    val bindPort: String
    val relayState: String
    val portFieldLabel: String
    /** 占位符：{0} = 端口号。 */
    val restartWillUsePort: String
    val waitingRelayRestart: String
    val sameDevice: String
    val multiDevice: String
    val restartRelayTitle: String
    val restartRelayBody: String
    val appConnectionAddress: String
    val targetId: String
    val clientUrl: String
    val copy: String
    val addressLabel: String
    val pairingCode: String
    val noPayload: String
    val qrPayloadEmpty: String
    val qrGenerationFailed: String
    val addressSemanticsTitle: String
    val addressSemanticsBody: String
    val appClients: String
    val clients: String
    val activeSlot: String
    val noAppClient: String
    /** 占位符：{0} = 剩余客户端数。 */
    val moreClients: String
    val routingTitle: String
    val routingBody: String
    val devicesTitle: String
    val noDeviceSnapshot: String
    /** 占位符：{0} = 设备类型，{1} = 槽位。 */
    val deviceDetail: String
    val linkDiagnostics: String
    val lastOpReason: String
    val outboundQueue: String
    val droppedOps: String
    val lastError: String
    val engineErrorLabel: String
    val linkErrorTitle: String
    val backpressureTitle: String
    val backpressureBody: String

    // ───────────────────────── ENGINEERING · MOTION ─────────────────────────
    val modelPanelTitle: String
    val modelFile: String
    val modelSession: String
    val statusLoaded: String
    val statusNotLoaded: String
    val modelSessionDetail: String
    val scalerLabel: String
    val statusValid: String
    val statusInvalid: String
    val pipelineLabel: String
    val statusRunning: String
    val statusStopped: String
    val scalerInvalidTitle: String
    val scalerInvalidBody: String
    val pipelineErrorTitle: String
    val stableClassPanel: String
    val tagStable: String
    val tagAwaiting: String
    val modelIndex: String
    val awaitingValue: String
    val effectiveClassLabel: String
    val mappingSourceLabel: String
    val manualOverrideActiveTitle: String
    /** 占位符：{0} = 人工类别索引。 */
    val manualOverrideActiveBody: String
    val currentPrediction: String
    val predictedClass: String
    val confidence: String
    val gyroMean: String
    val accStd: String
    val windowEnd: String
    /** 占位符：{0} = 时间戳。 */
    val windowEndValue: String
    val windowNoticeTitle: String
    val windowNoticeBody: String
    val classProbabilities: String
    val emaNoticeTitle: String
    /** 占位符：{0} = probEma。 */
    val emaNoticeBody: String
    val classIndexMap: String
    /** 占位符：{0} = 类别名，{1} = 基准强度。 */
    val classBaseValue: String
    val imuGnssPanel: String
    val gyroLabel: String
    val statusAvailable: String
    val statusMissing: String
    val gyroMissingDetail: String
    val rotationVector: String
    val rotationMissingDetail: String
    val gnssFix: String
    /** 占位符：{0} = 是否曾定位，{1} = 定位年龄。 */
    val gnssFixDetail: String
    val gnssSpeedLabel: String
    val staleSuffix: String
    val gpsFixAge: String
    val gpsEverFix: String
    val gnssWarningTitle: String
    val gnssWarningBody: String
    val inferencePipeline: String
    val bufferedFrames: String
    val lastInference: String
    val predictionAge: String
    val recognitionLabel: String
    val statusPaused: String
    val statusActive: String
    val statusEnabled: String
    val statusDisabled: String
    val recognitionPausedDetail: String
    val restOverrideLabel: String
    val statusInactive: String
    val restRuleTitle: String
    /** 占位符：{0} = 陀螺阈值，{1} = 加速度阈值，{2} = 窗口数。 */
    val restRuleBody: String
    val recognitionPausedTitle: String
    val recognitionPausedBody: String

    // ───────────────────────── ENGINEERING · OUTPUT ─────────────────────────
    val waveNotImplementedTitle: String
    val waveNotImplementedBody: String
    val supportedOps: String
    val opAddIntensity: String
    val opSetIntensity: String
    val opClear: String
    val notSentTitle: String
    val notSentBody: String
    val channelIntensity: String
    val valueLabel: String
    val channelA: String
    val channelB: String
    val estimated: String
    val threeValuesTitle: String
    val threeValuesBody: String
    val mappingTarget: String
    val appliedSlew: String
    val classBasePanel: String
    val classBaseTitle: String
    val classBaseBody: String
    val manualOffsetPanel: String
    /** 占位符：{0} = 偏移限幅。 */
    val offsetLimitLabel: String
    val manualOffsetTitle: String
    val manualOffsetBody: String
    val safetyMaxPanel: String
    val safetyLimitLabel: String
    val safetyLimitBody: String
    val mappingParams: String
    val manualLimit: String
    val switchWindows: String
    val probEmaLabel: String
    val slewUp: String
    val slewDown: String
    val restGyroThr: String
    val restAccThr: String
    val restWindows: String
    val restClasses: String
    val readOnlyTitle: String
    val readOnlyBody: String
    val localWavePanel: String
    val enabledLabel: String
    val shapeLabel: String
    val sessionLabel: String
    val statusIdle: String
    val gainBaseLabel: String
    val protocolLimitTitle: String
    val protocolLimitBody: String

    // ───────────────────────── ENGINEERING · DIAGNOSTICS ─────────────────────────
    val reportExport: String
    val appVersionLabel: String
    val logBuffer: String
    /** 占位符：{0} = 行数。 */
    val linesCount: String
    val reportSize: String
    /** 占位符：{0} = 行数。 */
    val reportReady: String
    val copyLogs: String
    val clipboard: String
    val export: String
    val diagnostics: String
    val reportNoticeTitle: String
    val reportNoticeBody: String
    val controlState: String
    val armedLabel: String
    val phaseLabel: String
    val transition: String
    val estopUi: String
    val estopController: String
    val targetApplied: String
    val startBlockers: String
    val estopPropagation: String
    val uiLatch: String
    val controllerLatch: String
    val framesQueuedLabel: String
    val appAckedLabel: String
    val appAckedDetail: String
    val ackReason: String
    val issuedAt: String
    val deviceUnverifiedTitle: String
    val deviceUnverifiedBody: String
    val performance: String
    val inference: String
    val buffer: String
    val unitFrames: String
    val dropped: String
    val unitOps: String
    val connectionDiagnosticsTitle: String
    val handshakeCount: String
    val lastHandshake: String
    val lastRejectReasonLabel: String
    val lastCloseCodeLabel: String
    val lastCloseReasonLabel: String
    /** 占位符：{0} = 错误行数。 */
    val errorsTitle: String
    val noErrorLines: String
    val filterTitle: String
    val filterBody: String
    /** 占位符：{0} = 展示条数，{1} = 缓冲总数。 */
    val recentEventsTitle: String
    val noEvents: String
    val orderTitle: String
    val orderBody: String

    // ───────────────────────── 界面语言 ─────────────────────────
    val languagePanelTitle: String
    val languageOptionSystem: String
    val languageOptionChinese: String
    val languageOptionEnglish: String
    val languageNote: String

    // ───────────────────────── 枚举 → 文案（共享映射逻辑） ─────────────────────────

    fun checkStatus(status: CheckStatus): String = when (status) {
        CheckStatus.PASS -> statusPass
        CheckStatus.WAITING -> statusWaiting
        CheckStatus.FAIL -> statusFail
        CheckStatus.DISCONNECTED -> statusDisconnected
    }

    fun controlPhase(phase: ControlPhase): String = when (phase) {
        ControlPhase.STANDBY -> phaseStandby
        ControlPhase.STARTING -> phaseStarting
        ControlPhase.RUNNING -> statusRunning
        ControlPhase.STOPPING -> phaseStopping
        ControlPhase.FAULT -> phaseFault
        ControlPhase.ESTOP_LATCHED -> estopLatched
    }

    fun mappingSource(source: MappingSource): String = when (source) {
        MappingSource.AUTO -> mappingSourceAuto
        MappingSource.MANUAL -> manualOverride
    }

    fun connectionModeName(mode: ConnectionMode): String = when (mode) {
        ConnectionMode.SINGLE -> modeSingleName
        ConnectionMode.MULTI -> modeMultiName
    }

    fun connectionModeDesc(mode: ConnectionMode): String = when (mode) {
        ConnectionMode.SINGLE -> modeSingleDesc
        ConnectionMode.MULTI -> modeMultiDesc
    }

    fun speedStatus(status: SpeedStatus): String = when (status) {
        SpeedStatus.LIVE -> speedLive
        SpeedStatus.STALE -> speedStale
        SpeedStatus.UNAVAILABLE -> speedUnavailable
    }

    fun speedDetail(status: SpeedStatus): String = when (status) {
        SpeedStatus.LIVE -> speedDetailLive
        SpeedStatus.STALE -> speedDetailStale
        SpeedStatus.UNAVAILABLE -> speedDetailUnavailable
    }

    fun motionClassLabel(classIndex: Int): String = when (MotionClass.of(classIndex)) {
        MotionClass.STATIONARY -> motionStationary
        MotionClass.WALKING -> motionWalking
        MotionClass.CYCLING -> motionCycling
        MotionClass.CAR -> motionCar
        MotionClass.TRAIN -> motionTrain
        null -> motionPending
    }

    fun waveShapeName(shape: WaveShape): String = when (shape) {
        WaveShape.FLAT -> waveShapeFlat
        WaveShape.SINE -> waveShapeSine
        WaveShape.TRIANGLE -> waveShapeTriangle
        WaveShape.SQUARE -> waveShapeSquare
        WaveShape.SAWTOOTH -> waveShapeSawtooth
        WaveShape.BURST -> waveShapeBurst
    }
}

/**
 * 模板填充：把 `{0}` `{1}`… 替换为对应参数。
 * 不用 [String.format]：正文里的 `%`（如百分比）不应被当成格式符。
 */
fun fmt(template: String, vararg args: Any?): String {
    var out = template
    args.forEachIndexed { index, arg ->
        out = out.replace("{$index}", arg?.toString() ?: "—")
    }
    return out
}

/**
 * 界面语言模式。
 *
 * [SYSTEM] 跟随系统：`Locale.getDefault()` 为 zh* 时用中文，否则英文；
 * [ZH] / [EN] 为显式指定（持久化到 SharedPreferences，键 `ui_language`）。
 */
enum class LanguageMode {
    SYSTEM,
    ZH,
    EN,
    ;

    /** 本模式在给定系统语言下解析出的实际语言（SYSTEM 需要判定）。 */
    fun resolved(locale: Locale = Locale.getDefault()): LanguageMode = when (this) {
        SYSTEM -> if (isChineseLocale(locale)) ZH else EN
        ZH -> ZH
        EN -> EN
    }

    companion object {
        /** 从持久化值解析；空/未知一律回退 [SYSTEM]（安全默认）。 */
        fun fromStored(value: String?): LanguageMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: SYSTEM

        /** zh / zh-CN / zh_Hans… 均视为中文。 */
        fun isChineseLocale(locale: Locale): Boolean =
            locale.language.equals("zh", ignoreCase = true)
    }
}

/** 按语言模式取文案对象（[LanguageMode.SYSTEM] 依据 [locale] 判定 zh* → 中文）。 */
fun uiStringsFor(mode: LanguageMode, locale: Locale = Locale.getDefault()): UiStrings =
    when (mode.resolved(locale)) {
        LanguageMode.ZH -> ZhUiStrings
        else -> EnUiStrings
    }
