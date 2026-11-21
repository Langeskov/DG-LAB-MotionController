package com.motioncontroller.mapping

import com.motioncontroller.ml.MotionClass
import com.motioncontroller.ml.MotionPrediction
import kotlin.math.abs

/** 映射配置（UI 设置页可改；默认值见 [MappingConfig.Default]） */
data class MappingConfig(
    /** 每个类别的基准强度，顺序同 [MotionClass]：STATIONARY/WALKING/CYCLING/CAR/TRAIN */
    val classBase: List<Float> = listOf(0f, 10f, 20f, 30f, 40f),
    /** 手动偏移上限（±）。映射目标 = classBase[stableClass] + manualOffset */
    val manualLimit: Float = 40f,
    /** 安全上限（0..200） */
    val safetyMax: Int = 60,
    /** 需要连续多少个窗口同类别才切换（防抖），>=1 */
    val switchWindows: Int = 2,
    /** 类别概率 EMA 系数（0..1，越大越平滑） */
    val probEma: Float = 0.4f,
    /** 强度上升速度上限（单位/秒） */
    val slewPerSecond: Float = 20f,
    /**
     * 强度下降速度上限（单位/秒）。下降/归零必须比上升快：
     * 运动结束、断连、看门狗暂停时能迅速把强度收回来（默认 60/s，约 1s 从 60 收到 0）。
     * 上升仍用 [slewPerSecond]，避免开始控制瞬间电流冲击。
     */
    val slewDownPerSecond: Float = 60f,
    /**
     * 矛盾消除：模型预测为「必然有振动」的类别（CAR/CYCLING）但手机几乎静止时，改判 STATIONARY。
     * 依据：真实数据里静止窗口 gyro≈0.0006 / acc_std≈0.004，而 CAR 的 gyro p10≈0.023；
     * 该规则在 10 个会话的离线评估中 +1.26% 准确率、零误伤（TRAIN 被排除，因为车厢同样安静）。
     */
    val restOverrideEnabled: Boolean = true,
    /** 静止判定：窗口陀螺仪模长均值阈值（rad/s） */
    val restGyroThreshold: Float = 0.02f,
    /** 静止判定：窗口加速度模长标准差阈值（m/s²） */
    val restAccStdThreshold: Float = 0.06f,
    /** 连续多少个「几乎静止」窗口后触发矛盾消除 */
    val restWindows: Int = 2,
    /** 需要校验的类别（默认 CAR/CYCLING；TRAIN 不能加，见上） */
    val restOverrideClasses: Set<Int> = setOf(MotionClass.CYCLING.ordinal, MotionClass.CAR.ordinal),
) {
    companion object {
        val Default = MappingConfig()

        /** 强度绝对值上限（V4 协议 0..200）。 */
        const val MAX_INTENSITY = 200f

        /** 控制循环单步允许的最大 dt（ms）。主线程卡顿/后台调度延迟时必须钳制，避免一次跳变。 */
        const val MAX_DT_MS = 200L

        /** slew 速度的合法范围（单位/秒）。 */
        private const val MIN_SLEW = 1f
        private const val MAX_SLEW = 500f

        /** 手动偏移上限的合法范围。 */
        private const val MAX_MANUAL_LIMIT = 200f
    }

    /**
     * 校验并修正配置：非法/越界值回退到默认值或钳制到合法区间。
     *
     * 纯函数、无 Android 依赖。UI 传入的配置、SharedPreferences 反序列化的配置都必须先过这里，
     * 保证 mapper 内部不会出现维度错误、NaN 或负的 slew（安全相关）。
     */
    fun sanitized(): MappingConfig {
        val d = Default
        val validBase = if (
            classBase.size == MotionClass.entries.size &&
            classBase.all { it.isFinite() && it >= 0f && it <= MAX_INTENSITY }
        ) {
            classBase
        } else {
            d.classBase
        }
        return copy(
            classBase = validBase,
            manualLimit = manualLimit.sanitized(0f, MAX_MANUAL_LIMIT, d.manualLimit),
            safetyMax = safetyMax.coerceIn(0, MAX_INTENSITY.toInt()),
            switchWindows = switchWindows.coerceIn(1, 20),
            probEma = probEma.sanitized(0.01f, 0.99f, d.probEma),
            slewPerSecond = slewPerSecond.sanitized(MIN_SLEW, MAX_SLEW, d.slewPerSecond),
            slewDownPerSecond = slewDownPerSecond.sanitized(MIN_SLEW, MAX_SLEW, d.slewDownPerSecond),
            restGyroThreshold = restGyroThreshold.sanitized(0f, 1000f, d.restGyroThreshold),
            restAccStdThreshold = restAccStdThreshold.sanitized(0f, 1000f, d.restAccStdThreshold),
            restWindows = restWindows.coerceIn(1, 20),
            restOverrideClasses = restOverrideClasses
                .filter { it in 0 until MotionClass.entries.size }
                .toSet(),
        )
    }

    private fun Float.sanitized(min: Float, max: Float, fallback: Float): Float =
        if (isFinite()) coerceIn(min, max) else fallback
}

/**
 * 状态 → 目标强度映射（纯计算，无 Android 依赖，可 JVM 单测）。
 *
 *  - 对 5 类概率做 EMA；
 *  - 连续 N 个窗口同类才切换“稳定类别”；
 *  - targetRaw = classBase[stableClass] + manualOffset，钳制到 [0, safetyMax]；
 *  - step(dtMs) 以 slewPerSecond（上升）/ slewDownPerSecond（下降、归零）逼近 targetRaw；
 *    dtMs 会被钳制到 [0, [MappingConfig.MAX_DT_MS]]，调用方用毫秒即可；
 *  - 还没有稳定类别时 target 恒为 0（运动未识别不下发强度）。
 */
class MotionIntensityMapper(initial: MappingConfig = MappingConfig.Default) {

    @Volatile
    var config: MappingConfig = initial.sanitized()
        private set

    /** 手动偏移（-manualLimit..+manualLimit） */
    @Volatile
    var manualOffset: Float = 0f
        private set

    private val emaProbs = FloatArray(MotionClass.entries.size)
    private var emaInitialized = false

    private var stableClass: Int = -1
    private var candidateClass: Int = -1
    private var candidateCount: Int = 0
    private var quietStreak = 0

    /** 最近一次是否由「矛盾消除」强制判为静止（调试/UI 用） */
    @Volatile
    var restOverrideActive: Boolean = false
        private set

    /** 平滑后的目标（未经 slew） */
    @Volatile
    var targetRaw: Float = 0f
        private set

    /** 当前实际下发值（经 slew 限制） */
    @Volatile
    var current: Float = 0f
        private set

    /** 最近一次推理的原始结果（UI 展示） */
    @Volatile
    var lastPrediction: MotionPrediction? = null
        private set

    /**
     * 人工接管：用户显式指定的运动类别（null = AUTO，由模型决定）。
     *
     * 只改变「选择哪个 classBase」这一层，**不改动映射公式与安全限制**：
     * `target = classBase[controlClass] + manualOffset` 仍然会被 safetyMax 钳制并经过同样的 slew。
     * 人工接管期间模型推理照常进行（[modelStableClassIndex] 仍会被更新），但不参与控制。
     */
    @Volatile
    var manualClassIndex: Int? = null
        private set

    /** 模型自己判定的稳定类别（-1 = 尚未稳定）；仅用于工程展示/归还自动的判断。 */
    val modelStableClassIndex: Int get() = stableClass

    /** 当前真正参与映射的类别：人工接管优先，否则用模型稳定类别。 */
    val effectiveClassIndex: Int get() = manualClassIndex ?: stableClass

    /** 当前生效类别的基准强度（未叠加 manualOffset，未钳制）。 */
    val effectiveBase: Float get() = config.classBase.getOrElse(effectiveClassIndex) { 0f }

    fun updateConfig(cfg: MappingConfig) {
        config = cfg.sanitized()
        manualOffset = manualOffset.coerceIn(-config.manualLimit, config.manualLimit)
        recalcTarget()
        current = current.coerceIn(0f, config.safetyMax.toFloat())
    }

    fun setManualOffset(value: Float) {
        manualOffset = value.coerceIn(-config.manualLimit, config.manualLimit)
        recalcTarget()
    }

    /**
     * 人工接管/归还自动。index=null 表示 AUTO（按模型结果映射）。
     *
     * 越界 index 视为 AUTO 并返回 false（调用方应据此提示而不是静默生效）。
     * 切换只改变 effectiveClass → targetRaw，输出变化仍由 [step] 的 slew 限制，不会产生阶跃。
     */
    fun setManualClass(index: Int?): Boolean {
        if (index != null && index !in 0 until MotionClass.entries.size) return false
        manualClassIndex = index
        recalcTarget()
        return true
    }

    /** 喂入一次窗口推理；返回稳定类别（-1 表示还没有稳定类别） */
    fun onPrediction(prediction: MotionPrediction): Int {
        lastPrediction = prediction
        val n = MotionClass.entries.size
        val alpha = config.probEma.coerceIn(0.01f, 0.99f)
        for (i in 0 until n) {
            val p = prediction.probs.getOrElse(i) { 0f }
            emaProbs[i] = if (!emaInitialized) p else emaProbs[i] * (1f - alpha) + p * alpha
        }
        emaInitialized = true

        var best = 0
        for (i in 1 until n) if (emaProbs[i] > emaProbs[best]) best = i

        // 矛盾消除：模型说 CAR/CYCLING（这两类必然有振动），但 IMU 显示手机几乎静止。
        // 注意不能用这条规则识别「静止 vs 火车」：TRAIN 车厢的 IMU 能量与静止几乎一样。
        val quiet = prediction.gyroMean < config.restGyroThreshold &&
            prediction.accStd < config.restAccStdThreshold
        quietStreak = if (quiet) quietStreak + 1 else 0
        val forcedRest = config.restOverrideEnabled &&
            quietStreak >= config.restWindows.coerceAtLeast(1) &&
            (best in config.restOverrideClasses || stableClass in config.restOverrideClasses)
        if (forcedRest) {
            restOverrideActive = true
            stableClass = MotionClass.STATIONARY.ordinal
            candidateClass = -1
            candidateCount = 0
            recalcTarget()
            return stableClass
        }
        restOverrideActive = false

        if (best == stableClass) {
            candidateClass = -1
            candidateCount = 0
        } else {
            if (best == candidateClass) {
                candidateCount++
            } else {
                candidateClass = best
                candidateCount = 1
            }
            if (candidateCount >= config.switchWindows.coerceAtLeast(1)) {
                stableClass = best
                candidateClass = -1
                candidateCount = 0
            }
        }
        recalcTarget()
        return stableClass
    }

    /**
     * 每 dtMs 调用一次，返回当前允许下发的强度（已 slew + 钳制）。
     *
     * 上升（targetRaw > current）用 [MappingConfig.slewPerSecond]；
     * 下降/归零（targetRaw <= current，含 target==0）用 [MappingConfig.slewDownPerSecond]，
     * 保证运动结束或看门狗暂停时强度能快速收回来。
     * dtMs 钳制到 [0, [MappingConfig.MAX_DT_MS]]：主线程卡顿、后台调度、暂停恢复都不会造成强度跳变。
     */
    fun step(dtMs: Long): Int {
        recalcTarget()
        val dt = dtMs.coerceIn(0L, MappingConfig.MAX_DT_MS)
        if (dt > 0L) {
            val diff = targetRaw - current
            val rate = if (diff > 0f) config.slewPerSecond else config.slewDownPerSecond
            val maxDelta = rate.coerceAtLeast(0f) * dt / 1000f
            current = when {
                abs(diff) <= maxDelta -> targetRaw
                diff > 0 -> current + maxDelta
                else -> current - maxDelta
            }
        }
        current = current.coerceIn(0f, config.safetyMax.toFloat())
        return current.toInt()
    }

    /**
     * 清空所有平滑状态并归零（停止/急停/断连/看门狗暂停）。
     *
     * 同时结束人工接管会话：安全保护机制终止控制会话后，人工选择不能默默延续到下一次控制，
     * 必须由用户重新接管（与需求 6.2 的“直到用户主动归还或系统终止本次会话”一致）。
     */
    fun reset() {
        emaProbs.fill(0f)
        emaInitialized = false
        stableClass = -1
        candidateClass = -1
        candidateCount = 0
        quietStreak = 0
        restOverrideActive = false
        manualClassIndex = null
        targetRaw = 0f
        current = 0f
    }

    /** 立即归零但保留配置与手动偏移（重新启用前调用） */
    fun forceZero() {
        targetRaw = 0f
        current = 0f
    }

    val stableClassIndex: Int get() = stableClass

    /** EMA 后各类概率（拷贝，顺序同 MotionClass） */
    fun smoothedProbs(): FloatArray = emaProbs.copyOf()

    private fun recalcTarget() {
        val controlClass = manualClassIndex ?: stableClass
        if (controlClass < 0) {
            targetRaw = 0f
            return
        }
        val base = config.classBase.getOrElse(controlClass) { 0f }
        targetRaw = (base + manualOffset).coerceIn(0f, config.safetyMax.toFloat())
    }
}
