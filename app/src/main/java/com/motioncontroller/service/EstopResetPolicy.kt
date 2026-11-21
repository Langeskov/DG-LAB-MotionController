package com.motioncontroller.service

/**
 * E-STOP 复位流程的纯逻辑层（无 Android 依赖，可 JVM 单测）。
 *
 * 设计原则（对应需求第 7、8 节）：
 *  - 复位不是“把布尔值改回 false”：必须先确认**停止命令真的能送达**（中继/APP/设备都在线），
 *    再重新下发安全帧并等待 APP 回执，最后跑启动自检；任一环节失败 → 保持锁存并给出失败原因。
 *  - APP ACK 只代表 DG-LAB APP 处理了 RPC，**不代表 BLE 设备已物理停止**；
 *    因此 [EstopResetCheckId.DEVICE_CONFIRM] 永远只能是 [EstopResetCheckStatus.UNVERIFIED]。
 *  - 复位成功后也只清除锁存，不启动控制、不发送新的输出指令。
 */

/** E-STOP 复位流程阶段（真实状态机在 ControllerService；UI 只渲染）。 */
enum class EstopResetPhase {
    /** 未在复位（正常锁存状态或已解除）。 */
    NONE,
    /** 用户已请求复位，流程即将开始。 */
    REQUESTED,
    /** 正在重发安全命令并等待 APP 回执。 */
    RESETTING,
    /** 启动自检（模型/scaler/链路）。 */
    SELF_CHECK,
    /** 复位失败：保持锁存，等待用户重试或恢复链路。 */
    FAILED,
}

/** 复位前检查 / 启动自检项（文案由 UI i18n 层按 id 映射）。 */
enum class EstopResetCheckId {
    SERVICE,
    RELAY,
    APP_LINK,
    DEVICE_LINK,
    STOP_COMMAND,
    APP_ACK,
    MODEL,
    SCALER,
    DEVICE_CONFIRM,
}

enum class EstopResetCheckStatus { WAITING, PASS, FAIL, UNVERIFIED }

data class EstopResetCheck(
    val id: EstopResetCheckId,
    val status: EstopResetCheckStatus,
    /** 原始诊断信息（如 APP 回执 reason / 引擎错误）；可为 null，UI 原样或忽略。 */
    val detail: String? = null,
)

/** 复位失败原因（UI 通过 code 映射本地化文案；detail 只做诊断补充）。 */
enum class EstopResetFailureCode {
    SERVICE_OFFLINE,
    RELAY_OFFLINE,
    APP_NOT_CONNECTED,
    DEVICE_NOT_CONNECTED,
    STOP_COMMAND_NOT_SENT,
    STOP_COMMAND_NOT_ACKED,
    MODEL_NOT_READY,
    SCALER_INVALID,
    LINK_REPLACED,
    UNKNOWN,
}

data class EstopResetFailure(
    val code: EstopResetFailureCode,
    val detail: String? = null,
)

/**
 * 复位决策所需的真实状态快照。
 *
 * @param serviceRunning 前台引擎是否在运行（UiState.serviceRunning）
 * @param engineAvailable 当前是否有可用引擎实例（activeEngine != null）
 * @param stopIssued 控制器是否执行过急停逻辑（DglabState.estopIssued）
 * @param framesQueued 归零/clear 帧是否真的进入发送队列（DglabState.estopFramesQueued）
 * @param appAcked APP 是否已回执（DglabState.estopAppAcked）——**不等于设备已停止**
 */
data class EstopResetLinkSnapshot(
    val serviceRunning: Boolean = false,
    val engineAvailable: Boolean = false,
    val relayRunning: Boolean = false,
    val appConnected: Boolean = false,
    val deviceReady: Boolean = false,
    val stopIssued: Boolean = false,
    val framesQueued: Boolean = false,
    val appAcked: Boolean = false,
    val ackReason: String? = null,
    val modelLoaded: Boolean = false,
    val scalerValid: Boolean = false,
)

object EstopResetPolicy {

    /**
     * 链路可用性检查（服务/中继/APP/设备）。复位流程先过这一层，
     * 只有链路可用时才谈得上“重发停止命令并等待回执”。
     */
    fun evaluateLinks(snapshot: EstopResetLinkSnapshot): EstopResetFailure? = when {
        !snapshot.serviceRunning || !snapshot.engineAvailable ->
            EstopResetFailure(EstopResetFailureCode.SERVICE_OFFLINE)
        !snapshot.relayRunning ->
            EstopResetFailure(EstopResetFailureCode.RELAY_OFFLINE)
        !snapshot.appConnected ->
            EstopResetFailure(EstopResetFailureCode.APP_NOT_CONNECTED)
        !snapshot.deviceReady ->
            EstopResetFailure(EstopResetFailureCode.DEVICE_NOT_CONNECTED)
        else -> null
    }

    /**
     * 完整停止命令检查（含“帧是否真的入队”）：用于复位最后阶段与 UI 展示。
     *
     * 设备必须在线：只有设备在线，控制器才能把 A/B 归零 + clear 真正送达 APP/设备；
     * 设备离线时无法确认停止命令已下发，因此拒绝复位（保持锁存，提示先恢复连接）。
     */
    fun evaluateLink(snapshot: EstopResetLinkSnapshot): EstopResetFailure? =
        evaluateLinks(snapshot) ?: if (snapshot.stopIssued && !snapshot.framesQueued) {
            EstopResetFailure(EstopResetFailureCode.STOP_COMMAND_NOT_SENT)
        } else {
            null
        }

    /** 启动自检：模型必须已加载且 scaler 真实有效（否则预测不可信）。 */
    fun evaluateSelfCheck(snapshot: EstopResetLinkSnapshot): EstopResetFailure? = when {
        !snapshot.modelLoaded -> EstopResetFailure(EstopResetFailureCode.MODEL_NOT_READY)
        !snapshot.scalerValid -> EstopResetFailure(EstopResetFailureCode.SCALER_INVALID)
        else -> null
    }

    /**
     * 能否真正清除锁存：链路/自检全部通过 **且** APP 已回执停止命令。
     * 设备确认永远不在这个条件里——本工程没有 BLE 回执通道。
     */
    fun canClearLatch(snapshot: EstopResetLinkSnapshot): Boolean =
        evaluateLink(snapshot) == null &&
            evaluateSelfCheck(snapshot) == null &&
            snapshot.appAcked

    /**
     * 是否需要（重新）下发停止命令：链路可用，但 APP 尚未回执，或停止帧尚未入队。
     * 覆盖“急停时链路离线、之后才恢复”的场景：复位流程会先补发停止命令再清锁存。
     */
    fun needsStopReissue(snapshot: EstopResetLinkSnapshot): Boolean =
        evaluateLinks(snapshot) == null && (!snapshot.appAcked || !snapshot.framesQueued)

    /** 复位检查清单（UI 逐项展示；DEVICE_CONFIRM 恒为 UNVERIFIED）。 */
    fun checks(snapshot: EstopResetLinkSnapshot): List<EstopResetCheck> = listOf(
        EstopResetCheck(
            id = EstopResetCheckId.SERVICE,
            status = if (snapshot.serviceRunning && snapshot.engineAvailable) {
                EstopResetCheckStatus.PASS
            } else {
                EstopResetCheckStatus.FAIL
            },
        ),
        EstopResetCheck(
            id = EstopResetCheckId.RELAY,
            status = if (snapshot.relayRunning) EstopResetCheckStatus.PASS else EstopResetCheckStatus.FAIL,
        ),
        EstopResetCheck(
            id = EstopResetCheckId.APP_LINK,
            status = if (snapshot.appConnected) EstopResetCheckStatus.PASS else EstopResetCheckStatus.FAIL,
        ),
        EstopResetCheck(
            id = EstopResetCheckId.DEVICE_LINK,
            status = if (snapshot.deviceReady) EstopResetCheckStatus.PASS else EstopResetCheckStatus.FAIL,
        ),
        EstopResetCheck(
            id = EstopResetCheckId.STOP_COMMAND,
            status = when {
                snapshot.framesQueued -> EstopResetCheckStatus.PASS
                snapshot.stopIssued -> EstopResetCheckStatus.FAIL
                else -> EstopResetCheckStatus.WAITING
            },
        ),
        EstopResetCheck(
            id = EstopResetCheckId.APP_ACK,
            status = if (snapshot.appAcked) EstopResetCheckStatus.PASS else EstopResetCheckStatus.WAITING,
            detail = snapshot.ackReason,
        ),
        EstopResetCheck(
            id = EstopResetCheckId.MODEL,
            status = if (snapshot.modelLoaded) EstopResetCheckStatus.PASS else EstopResetCheckStatus.FAIL,
        ),
        EstopResetCheck(
            id = EstopResetCheckId.SCALER,
            status = if (snapshot.scalerValid) EstopResetCheckStatus.PASS else EstopResetCheckStatus.FAIL,
        ),
        // 本工程没有 BLE 级停止回执：无论 APP 是否 ACK，设备确认都只能是 UNVERIFIED。
        EstopResetCheck(
            id = EstopResetCheckId.DEVICE_CONFIRM,
            status = EstopResetCheckStatus.UNVERIFIED,
        ),
    )
}
