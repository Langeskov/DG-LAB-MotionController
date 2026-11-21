package com.motioncontroller.service

import com.motioncontroller.dglab.DglabState
import com.motioncontroller.mapping.MappingConfig
import com.motioncontroller.mapping.WaveConfig
import com.motioncontroller.ml.MotionPipelineStatus
import com.motioncontroller.ml.MotionPrediction

/** START/STOP 命令的短暂收敛窗口（真实状态：命令已受理，等待下一步状态落定）。 */
enum class ControlTransition { NONE, STARTING, STOPPING }

/**
 * UI 唯一的渲染状态。由 [ControllerService] 维护，Activity 通过 Binder 观察。
 *
 * 状态来源约定（不要用 UI 临时状态覆盖）：
 *  - 连接：`link`（中继/APP/设备/tid/急停阶段）；
 *  - 识别：`pipeline` + `prediction` + `stableClass`（**模型**稳定类别）；
 *  - 控制生效类别：`effectiveClass`（AUTO = 模型，MANUAL = 用户指定），`manualClass != null` 即人工接管；
 *  - 控制会话：`armed` + `controlTransition` + `emergencyStopped`；
 *  - 强度：`targetIntensity`（本次 tick 实际下发的值）与 `link.*`（目标/估计/APP 上报）。
 */
data class UiState(
    val serviceRunning: Boolean = false,
    /** 是否已"开始控制"（用户显式点击后才会下发强度） */
    val armed: Boolean = false,
    val link: DglabState = DglabState(),
    val pipeline: MotionPipelineStatus = MotionPipelineStatus(),
    val prediction: MotionPrediction? = null,
    val smoothedProbs: List<Float> = List(5) { 0f },
    /** 模型判定的稳定类别（工程/诊断用；普通界面应显示 [effectiveClass]） */
    val stableClass: Int = -1,
    /** 当前实际参与映射的类别：AUTO = 模型稳定类别，MANUAL = [manualClass] */
    val effectiveClass: Int = -1,
    /** 人工接管的类别；null = AUTO（模型决定） */
    val manualClass: Int? = null,
    val mapping: MappingConfig = MappingConfig.Default,
    val manualOffset: Float = 0f,
    val targetIntensity: Int = 0,
    val currentIntensity: Int = 0,
    val emergencyStopped: Boolean = false,
    /**
     * E-STOP 复位流程阶段（真实状态机在 ControllerService；UI 只渲染）。
     * 只有 [EstopResetPhase.NONE] 且 [emergencyStopped] = false 才代表锁存已解除。
     */
    val estopResetPhase: EstopResetPhase = EstopResetPhase.NONE,
    /** 复位检查/启动自检清单（由 [EstopResetPolicy.checks] 派生；UI 逐项展示）。 */
    val estopResetChecks: List<EstopResetCheck> = emptyList(),
    /** 最近一次复位失败原因（[EstopResetPhase.FAILED] 时非空；锁存保持不变）。 */
    val estopResetFailure: EstopResetFailure? = null,
    /** 复位成功（锁存已清除）的时刻（elapsedRealtime ms）；0 = 本次会话尚未成功复位过。 */
    val estopResetCompletedAtMs: Long = 0L,
    /**
     * 设备停止状态未被验证：急停触发后为 true，并在复位成功后继续保持 true，
     * 直到用户重新 START 控制（操作员接受风险）为止。PRE-FLIGHT 会展示对应警告。
     */
    val estopDeviceStopUnverified: Boolean = false,
    /** START/STOP 命令收敛窗口（短时 UI 过渡；不代表控制权切换） */
    val controlTransition: ControlTransition = ControlTransition.NONE,
    /** 引擎启动失败原因（中继/管线），null 表示无失败 */
    val engineError: String? = null,
    /** 给 DG-LAB APP 的连接地址（手动输入用；0.0.0.0 会被解析为局域网 IP） */
    val address: String = "",
    /** 给二维码用的 dungeon-lab 配对链接 */
    val qrPayload: String = "",
    val logs: List<String> = emptyList(),
    val scalerValid: Boolean = false,
    val bindHost: String = "127.0.0.1",
    val port: Int = 9998,
    /**
     * 死手看门狗暂停：已 armed 但超过 3s 没有新的推理结果（例如息屏后传感器暂停），
     * 映射已重置且强度强制归零；下一条推理到达后自动恢复正常的 2 窗口映射（不自动重新 arm）。
     */
    val recognitionPaused: Boolean = false,
    /** 映射层「REST 矛盾消除」当前是否生效（调试/UI 用） */
    val restOverrideActive: Boolean = false,
    /** 距最近一次推理结果的时长（ms，SystemClock.elapsedRealtime 基准；0 = 刚刚） */
    val predictionAgeMs: Long = 0L,
    /** 引擎启动/停止进行中（生命周期异步化后有短暂的 loading 窗口） */
    val engineLoading: Boolean = false,
    /** 本地波形（强度包络合成）配置；协议限制与安全约定见 mapping/Waveform.kt */
    val wave: WaveConfig = WaveConfig.Default,
    /** 波形会话进行中（enabled 且当前确实在调制输出） */
    val waveActive: Boolean = false,
    /** 当前包络增益 0..1（未调制时为 1） */
    val waveGain: Float = 1f,
    /** 调制前的映射输出（波形面板显示包络上下沿用） */
    val waveBaseIntensity: Int = 0,
    /** 波形会话剩余秒数（0 = 无限或未运行） */
    val waveRemainingSec: Int = 0,
) {
    val appConnected: Boolean get() = link.appConnected
    val deviceReady: Boolean get() = link.deviceReady
    val canControl: Boolean get() = armed && link.relayRunning && link.appConnected && link.deviceReady && !emergencyStopped
    /** 是否处于人工接管（映射来源 = MANUAL） */
    val manualOverrideActive: Boolean get() = manualClass != null
    /** 急停复位是否正在进行（UI 应锁定复位按钮并展示进度/自检）。 */
    val estopResetInProgress: Boolean
        get() = estopResetPhase == EstopResetPhase.REQUESTED ||
            estopResetPhase == EstopResetPhase.RESETTING ||
            estopResetPhase == EstopResetPhase.SELF_CHECK
}
