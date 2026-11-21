package com.motioncontroller.ml

import kotlin.math.floor

/** 一帧带时间的原始 10 维传感器数据（时间单位：毫秒，单调递增）。 */
class TimedFrame(
    @JvmField val timeMs: Double,
    @JvmField val values: DoubleArray,
) {
    override fun toString(): String = "TimedFrame(t=$timeMs, v=${values.contentToString()})"
}

/**
 * 50 Hz 重采样（纯 Kotlin，无 Android 依赖）。
 *
 * 逐行复刻 `Motion-ml/dataset/session_dataset.py::resample_frame` + `_resample_uniform`：
 *  - 相邻样本间隔 > [MAX_GAP_MS]（200ms）视为新段，**跨段不插值**；
 *  - 段内 `n = floor(dur_s * target_hz) + 1`，网格 `t0 + k * (1000/target_hz)`；
 *  - 每个数值列做 `np.interp`（单调线性插值，两端 clamp）；
 *  - 段内样本 < 2 或 `n < 2` 时原样透传该段（与参考实现一致）。
 *
 * 端上真实时间轴单调递增（停顿只体现为间隔变大），所以插值用二分即可。
 */
object Resampler {

    /** 目标采样率（Hz）。 */
    const val TARGET_HZ = 50.0

    /** 超过此间隔视为新段（ms）。 */
    const val MAX_GAP_MS = 200.0

    /**
     * 批量重采样（离线语义，用于单测/golden 对齐）。
     *
     * @param samples 按时间排序的原始采样（时间单位 ms）
     */
    fun resample(
        samples: List<TimedFrame>,
        targetHz: Double = TARGET_HZ,
        maxGapMs: Double = MAX_GAP_MS,
    ): List<TimedFrame> {
        if (samples.size < 2 || targetHz <= 0.0) return samples

        val stepMs = 1000.0 / targetHz
        val out = ArrayList<TimedFrame>(samples.size * 2)

        var segStart = 0
        while (segStart < samples.size) {
            var segEnd = segStart + 1
            while (segEnd < samples.size && samples[segEnd].timeMs - samples[segEnd - 1].timeMs <= maxGapMs) {
                segEnd++
            }
            appendSegment(samples, segStart, segEnd, targetHz, stepMs, out)
            segStart = segEnd
        }
        return out
    }

    private fun appendSegment(
        samples: List<TimedFrame>,
        from: Int,
        until: Int,
        targetHz: Double,
        stepMs: Double,
        out: MutableList<TimedFrame>,
    ) {
        val count = until - from
        if (count < 2) {
            // 段内样本太少，不足以插值 → 原样透传（参考实现同样处理）
            for (i in from until until) out.add(samples[i])
            return
        }
        val t0 = samples[from].timeMs
        val tLast = samples[until - 1].timeMs
        val n = floor((tLast - t0) / 1000.0 * targetHz).toInt() + 1
        if (n < 2) {
            for (i in from until until) out.add(samples[i])
            return
        }

        val xp = DoubleArray(count) { samples[from + it].timeMs }
        val dim = samples[from].values.size
        val columns = Array(dim) { c -> DoubleArray(count) { i -> samples[from + i].values[c] } }

        for (k in 0 until n) {
            val t = t0 + k * stepMs
            val values = DoubleArray(dim) { c -> interp(t, xp, columns[c]) }
            out.add(TimedFrame(t, values))
        }
    }

    /** `np.interp(x, xp, fp)`：xp 单调递增，两端 clamp。 */
    fun interp(x: Double, xp: DoubleArray, fp: DoubleArray): Double {
        val last = xp.size - 1
        if (x <= xp[0]) return fp[0]
        if (x >= xp[last]) return fp[last]
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xp[mid] <= x) lo = mid else hi = mid
        }
        val dx = xp[lo + 1] - xp[lo]
        if (dx == 0.0) return fp[lo]
        return fp[lo] + (fp[lo + 1] - fp[lo]) * ((x - xp[lo]) / dx)
    }
}

/**
 * 流式 50 Hz 重采样器：每来一个原始采样就吐出可以直接送入 [FeatureExtractor] 的 50Hz 帧。
 *
 * 与 [Resampler.resample] 的离线语义逐帧等价：
 *  - 首个采样建立段起点，网格锚定在段起点；
 *  - 相邻采样间隔 > [Resampler.MAX_GAP_MS] → 新段从当前采样重新锚定网格（不跨段插值）；
 *  - 段内每个网格点用相邻两个采样线性插值（`np.interp` 公式）。
 *
 * 与批量实现的唯一差异：末尾不会有「补一帧」行为（离线实现也不需要补，
 * 因为网格点只到 `t_last` 为止，见 `n = floor(dur*fs)+1`）；单样本段不会产出网格帧。
 */
class StreamingResampler(
    private val targetHz: Double = Resampler.TARGET_HZ,
    private val maxGapMs: Double = Resampler.MAX_GAP_MS,
) {
    private val stepMs = 1000.0 / targetHz
    private var hasPrevious = false
    private var prevTimeMs = 0.0
    private var prevValues = DoubleArray(0)
    private var nextGridMs = 0.0

    /** 累计产出的 50Hz 帧数。 */
    var emittedFrames: Long = 0L
        private set

    /** 当前连续段编号（>200ms 间隔时 +1）。调用方可据此实现"不跨段切窗"。 */
    var segmentIndex: Long = 0L
        private set

    /** 清空状态（急停/重启时调用）。 */
    fun reset() {
        hasPrevious = false
        prevTimeMs = 0.0
        prevValues = DoubleArray(0)
        nextGridMs = 0.0
        emittedFrames = 0L
        segmentIndex = 0L
    }

    /**
     * 送入一个原始采样，返回本次产生的 50Hz 帧（通常 0~2 帧；断段时为 0~1 帧）。
     */
    fun accept(timeMs: Double, values: DoubleArray): List<TimedFrame> {
        if (!hasPrevious) {
            hasPrevious = true
            prevTimeMs = timeMs
            prevValues = values.copyOf()
            nextGridMs = timeMs + stepMs
            emittedFrames++
            return listOf(TimedFrame(timeMs, values.copyOf()))
        }

        if (timeMs - prevTimeMs > maxGapMs) {
            // 断段：不跨段插值，新段网格从当前采样重新锚定
            prevTimeMs = timeMs
            prevValues = values.copyOf()
            nextGridMs = timeMs + stepMs
            emittedFrames++
            segmentIndex++
            return listOf(TimedFrame(timeMs, values.copyOf()))
        }

        if (timeMs <= prevTimeMs) {
            // 非单调/重复时间戳：保持网格不动，仅刷新最新值
            prevValues = values.copyOf()
            return emptyList()
        }

        val dt = timeMs - prevTimeMs
        val dim = values.size
        val result = ArrayList<TimedFrame>(4)
        var guard = 0
        while (nextGridMs <= timeMs && guard < MAX_FRAMES_PER_SAMPLE) {
            val frac = (nextGridMs - prevTimeMs) / dt
            val frame = DoubleArray(dim) { c ->
                val a = if (c < prevValues.size) prevValues[c] else 0.0
                val b = if (c < values.size) values[c] else 0.0
                a + (b - a) * frac
            }
            result.add(TimedFrame(nextGridMs, frame))
            emittedFrames++
            nextGridMs += stepMs
            guard++
        }
        if (guard >= MAX_FRAMES_PER_SAMPLE) {
            // 异常长的事件间隔（不应发生，>200ms 已断段）：跳过残留网格，避免补帧风暴
            nextGridMs = timeMs + stepMs
        }

        prevTimeMs = timeMs
        prevValues = values.copyOf()
        return result
    }

    private companion object {
        const val MAX_FRAMES_PER_SAMPLE = 64
    }
}
