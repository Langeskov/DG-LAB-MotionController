package com.motioncontroller.ui.model

import com.motioncontroller.ml.MotionClass
import com.motioncontroller.service.UiState
import kotlin.math.roundToInt

/** 映射来源：AUTO = 模型决定；MANUAL OVERRIDE = 用户指定。 */
enum class MappingSource(val labelEn: String) {
    AUTO("AUTO"),
    MANUAL("MANUAL OVERRIDE"),
}

/**
 * 主界面 OUTPUT PROFILE 展示模型（纯派生，不产生新业务值）。
 *
 * 五层数值必须分开显示，不能混为一谈：
 *  1. [baseIntensity]   当前生效类别的基准值（mapping.classBase）；
 *  2. [manualOffset]    手动偏移；
 *  3. [computedTarget]  base + offset（未钳制，可能为负/超上限）；
 *  4. [clampedTarget]   安全上限钳制后的映射目标（mapper.targetRaw，未经过 slew）；
 *  5. [appliedIntensity] 本次控制 tick 实际下发的强度（经 slew 平滑）。
 * 另外 [sentTargetA] / [sentEstimateA] 是控制器发送侧，[appReportedA] 是 APP 上报（设备侧），
 * 三者来源不同，UI 不能把其中任何一个当作"设备已确认"。
 */
data class OutputProfileSnapshot(
    val source: MappingSource,
    val effectiveClass: Int,
    val classCode: String,
    val classLabelCn: String,
    val baseIntensity: Int,
    val manualOffset: Int,
    val computedTarget: Int,
    val clampedTarget: Int,
    val appliedIntensity: Int,
    val safetyMax: Int,
    val manualOffsetLimit: Int,
    val sentTargetA: Int,
    val sentEstimateA: Int,
    val appReportedA: Int?,
    val linkReady: Boolean,
    val estopLatched: Boolean,
) {
    /** 数值是否真的在往设备下发（仅用于 UI 状态文案，不替代链路判断）。 */
    val outputActive: Boolean get() = linkReady && !estopLatched && appliedIntensity > 0
}

object OutputModel {

    fun derive(state: UiState): OutputProfileSnapshot {
        val source = if (state.manualClass != null) MappingSource.MANUAL else MappingSource.AUTO
        val effectiveClass = when {
            state.manualClass != null -> state.manualClass ?: -1
            else -> state.effectiveClass
        }
        val base = state.mapping.classBase.getOrElse(effectiveClass) { 0f }
        val offset = state.manualOffset
        val computed = base + offset
        val safetyMax = state.mapping.safetyMax
        val clamped = computed.coerceIn(0f, safetyMax.toFloat())
        val cls = MotionClass.of(effectiveClass)
        return OutputProfileSnapshot(
            source = source,
            effectiveClass = effectiveClass,
            classCode = cls?.name ?: "—",
            classLabelCn = cls?.labelCn ?: "待识别",
            baseIntensity = base.roundToInt(),
            manualOffset = offset.roundToInt(),
            computedTarget = computed.roundToInt(),
            clampedTarget = clamped.roundToInt(),
            appliedIntensity = state.targetIntensity,
            safetyMax = safetyMax,
            manualOffsetLimit = state.mapping.manualLimit.roundToInt(),
            sentTargetA = state.link.targetIntensityA,
            sentEstimateA = state.link.estimateIntensityA,
            appReportedA = state.link.reportedIntensityA,
            linkReady = state.link.relayRunning && state.link.appConnected && state.link.deviceReady,
            estopLatched = state.emergencyStopped,
        )
    }
}
