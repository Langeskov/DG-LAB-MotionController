package com.motioncontroller.ml

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 24 维运动特征提取器（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * 严格复刻训练侧 `Motion-ml/dataset/preprocess.py::FeatureExtractor`
 * （rolling_window=10, fs=50.0, spectral_window=100, spectral_stride=10）：
 *
 * ```
 * 输入 [N,10] 原始列（顺序见 AGENTS.md 4.1）：
 *   gps_speed(km/h), acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z, roll, pitch, yaw
 * 输出 [N,24]（顺序见 AGENTS.md 4.2）：
 *   gps_speed, acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z,
 *   acc_magnitude, gyro_magnitude, speed_delta,
 *   acc_rolling_mean, acc_rolling_std, gyro_rolling_mean, gyro_rolling_std,
 *   roll_rolling_mean, roll_rolling_std, pitch_rolling_mean, vibration_score,
 *   acc_bp_0.5_2, acc_bp_2_5, acc_bp_5_10, acc_bp_10_25, acc_centroid, acc_dom_freq
 * ```
 *
 * 注意：原始 roll/pitch/yaw 被丢弃（模型契约），但 roll/pitch 的**滚动派生特征保留**。
 *
 * pandas/numpy 语义对齐点：
 *  - `rolling(window=10, min_periods=1)`：窗口不足时用现有帧；
 *  - `std` 默认 ddof=1，窗口只有 1 帧时为 NaN → 0；
 *  - `speed_delta = gps_speed.diff().fillna(0)`，首帧为 0；
 *  - `vibration_score = acc_rolling_std / acc_rolling_mean`，分母 0 → NaN → 0；
 *  - 频域每 `spectral_stride` 帧取一次 `spectral_window` 帧 FFT（朴素 DFT，等价 rfft），
 *    去均值 + Hann 窗 + 功率谱 + `total=sum+1e-12`，再 `np.interp` 回逐帧（两端 clamp）。
 */
class FeatureExtractor(
    private val rollingWindow: Int = DEFAULT_ROLLING_WINDOW,
    private val fs: Double = DEFAULT_FS,
    private val spectralWindow: Int = DEFAULT_SPECTRAL_WINDOW,
    private val spectralStride: Int = DEFAULT_SPECTRAL_STRIDE,
) {

    init {
        require(fs > 0.0) { "fs 必须 > 0，收到 $fs" }
        require(rollingWindow > 0) { "rollingWindow 必须 > 0，收到 $rollingWindow" }
        require(spectralWindow > 1) { "spectralWindow 必须 > 1，收到 $spectralWindow" }
        require(spectralStride > 0) { "spectralStride 必须 > 0，收到 $spectralStride" }
    }

    /**
     * 频域常量表（Hann 窗 / 频率 / twiddle 因子），只依赖 [spectralWindow] 与 [fs]。
     * 2026-10-31 audit：旧实现每次 transform 都重算这些三角函数表；现在懒加载一次并复用。
     * 表内容按设计只读，任何调用方都不得修改。
     */
    private val spectralTables: SpectralTables by lazy {
        buildSpectralTables(spectralWindow, fs)
    }

    /** 总特征维度（24）。 */
    val featureDim: Int get() = FEATURE_DIM

    /**
     * 对 [N,10] 原始特征矩阵提取 [N,24] 特征。
     *
     * @param raw 每行 10 列，顺序固定为 [RAW_COLS]；不会被修改。
     * @return 每行 24 列，顺序固定为 [FEATURE_NAMES]。
     */
    fun transform(raw: Array<DoubleArray>): Array<DoubleArray> {
        val n = raw.size
        val out = Array(n) { DoubleArray(FEATURE_DIM) }
        if (n == 0) return out
        // 校验**所有**行的列数，而不是只看第 0 行：坏行拖到后面只会得到 IndexOutOfBounds，
        // 错误信息也不清楚（2026-10-31 audit L6）。
        for (i in 0 until n) {
            require(raw[i].size == RAW_DIM) {
                "原始特征维度必须是 $RAW_DIM，第 $i 行收到 ${raw[i].size}"
            }
        }

        val gpsSpeed = DoubleArray(n)
        val accX = DoubleArray(n)
        val accY = DoubleArray(n)
        val accZ = DoubleArray(n)
        val gyroX = DoubleArray(n)
        val gyroY = DoubleArray(n)
        val gyroZ = DoubleArray(n)
        val roll = DoubleArray(n)
        val pitch = DoubleArray(n)
        for (i in 0 until n) {
            val row = raw[i]
            gpsSpeed[i] = row[RAW_GPS_SPEED]
            accX[i] = row[RAW_ACC_X]
            accY[i] = row[RAW_ACC_Y]
            accZ[i] = row[RAW_ACC_Z]
            gyroX[i] = row[RAW_GYRO_X]
            gyroY[i] = row[RAW_GYRO_Y]
            gyroZ[i] = row[RAW_GYRO_Z]
            roll[i] = row[RAW_ROLL]
            pitch[i] = row[RAW_PITCH]
        }

        // ── 1. 模长 ────────────────────────────────
        val accMag = DoubleArray(n)
        val gyroMag = DoubleArray(n)
        for (i in 0 until n) {
            accMag[i] = sqrt(accX[i] * accX[i] + accY[i] * accY[i] + accZ[i] * accZ[i])
            gyroMag[i] = sqrt(gyroX[i] * gyroX[i] + gyroY[i] * gyroY[i] + gyroZ[i] * gyroZ[i])
        }

        // ── 2. 滚动统计（min_periods=1, ddof=1） ─────────────────
        val accMean = DoubleArray(n)
        val accStd = DoubleArray(n)
        val gyroMean = DoubleArray(n)
        val gyroStd = DoubleArray(n)
        val rollMean = DoubleArray(n)
        val rollStd = DoubleArray(n)
        val pitchMean = DoubleArray(n)
        rollingStats(accMag, accMean, accStd)
        rollingStats(gyroMag, gyroMean, gyroStd)
        rollingStats(roll, rollMean, rollStd)
        rollingStats(pitch, pitchMean, null)

        // ── 3. 逐列写入 ─────────────────────────────
        for (i in 0 until n) {
            val row = out[i]
            row[F_GPS_SPEED] = gpsSpeed[i]
            row[F_ACC_X] = accX[i]
            row[F_ACC_Y] = accY[i]
            row[F_ACC_Z] = accZ[i]
            row[F_GYRO_X] = gyroX[i]
            row[F_GYRO_Y] = gyroY[i]
            row[F_GYRO_Z] = gyroZ[i]
            row[F_ACC_MAGNITUDE] = accMag[i]
            row[F_GYRO_MAGNITUDE] = gyroMag[i]
            row[F_SPEED_DELTA] = if (i == 0) 0.0 else gpsSpeed[i] - gpsSpeed[i - 1]
            row[F_ACC_ROLLING_MEAN] = accMean[i]
            row[F_ACC_ROLLING_STD] = accStd[i]
            row[F_GYRO_ROLLING_MEAN] = gyroMean[i]
            row[F_GYRO_ROLLING_STD] = gyroStd[i]
            row[F_ROLL_ROLLING_MEAN] = rollMean[i]
            row[F_ROLL_ROLLING_STD] = rollStd[i]
            row[F_PITCH_ROLLING_MEAN] = pitchMean[i]
            // pandas: acc_rolling_mean.replace(0, NaN) → 除法得 NaN → fillna(0)
            row[F_VIBRATION_SCORE] = if (accMean[i] == 0.0) 0.0 else accStd[i] / accMean[i]
        }

        // ── 4. 频域（写入 acc_bp_*/centroid/dom_freq） ────────────
        addSpectral(accMag, out)
        return out
    }

    /**
     * pandas `rolling(window=w, min_periods=1)` 的 mean / std(ddof=1)。
     * [outStd] 为 null 时只算均值。单帧窗口的 std 视为 NaN → 0。
     */
    private fun rollingStats(x: DoubleArray, outMean: DoubleArray, outStd: DoubleArray?) {
        val w = rollingWindow
        for (i in x.indices) {
            val from = if (i - w + 1 > 0) i - w + 1 else 0
            val count = i - from + 1
            var sum = 0.0
            for (j in from..i) sum += x[j]
            val mean = sum / count
            outMean[i] = mean
            if (outStd != null) {
                if (count < 2) {
                    outStd[i] = 0.0
                } else {
                    var ss = 0.0
                    for (j in from..i) {
                        val d = x[j] - mean
                        ss += d * d
                    }
                    outStd[i] = sqrt(ss / (count - 1))
                }
            }
        }
    }

    /**
     * 频域特征：对 acc_magnitude 每 [spectralStride] 帧做一次 [spectralWindow] 帧的
     * rfft（这里用朴素 DFT），再 `np.interp` 插值回逐帧（两端 clamp）。
     */
    private fun addSpectral(accMag: DoubleArray, out: Array<DoubleArray>) {
        val n = accMag.size
        val w = spectralWindow
        if (n < w) {
            // 样本太少，不足以做一次完整 FFT → 全部置 0（与 preprocess.py 一致）
            for (i in 0 until n) {
                for (c in 0 until SPECTRAL_DIM) out[i][F_BP_0_5_2 + c] = 0.0
            }
            return
        }

        // 窗口起点：0, step, 2*step, ...；不足时补最后一窗 n-w
        val starts = ArrayList<Int>(n / spectralStride + 2)
        var s = 0
        while (s <= n - w) {
            starts.add(s)
            s += spectralStride
        }
        if (starts[starts.size - 1] != n - w) starts.add(n - w)
        val windowCount = starts.size

        val tables = spectralTables
        val hann = tables.hann
        val bins = tables.bins
        val freqs = tables.freqs
        val cosTab = tables.cosTab
        val sinTab = tables.sinTab

        val bandValues = Array(BAND_COUNT) { DoubleArray(windowCount) }
        val centroidValues = DoubleArray(windowCount)
        val domValues = DoubleArray(windowCount)
        val buf = DoubleArray(w)
        val power = DoubleArray(bins)

        for (wi in 0 until windowCount) {
            val st = starts[wi]
            var mean = 0.0
            for (k in 0 until w) mean += accMag[st + k]
            mean /= w
            for (k in 0 until w) buf[k] = (accMag[st + k] - mean) * hann[k]

            for (b in 0 until bins) {
                val base = b * w
                var re = 0.0
                var im = 0.0
                for (k in 0 until w) {
                    val v = buf[k]
                    re += v * cosTab[base + k]
                    im += v * sinTab[base + k]
                }
                power[b] = re * re + im * im
            }

            var total = 1e-12 // total = sum(spec) + 1e-12
            for (b in 0 until bins) total += power[b]

            for (bi in 0 until BAND_COUNT) {
                val lo = BAND_LO[bi]
                val hi = BAND_HI[bi]
                var sum = 0.0
                for (b in 0 until bins) {
                    val f = freqs[b]
                    if (f >= lo && f < hi) sum += power[b]
                }
                bandValues[bi][wi] = sum / total
            }

            var weighted = 0.0
            for (b in 0 until bins) weighted += power[b] * freqs[b]
            centroidValues[wi] = weighted / total

            var maxBin = 0
            for (b in 1 until bins) if (power[b] > power[maxBin]) maxBin = b
            domValues[wi] = freqs[maxBin]
        }

        // np.interp(frame_idx, starts, values)：两端 clamp
        val startD = DoubleArray(windowCount) { starts[it].toDouble() }
        for (i in 0 until n) {
            val frame = i.toDouble()
            for (bi in 0 until BAND_COUNT) {
                out[i][F_BP_0_5_2 + bi] = interpClamped(frame, startD, bandValues[bi])
            }
            out[i][F_ACC_CENTROID] = interpClamped(frame, startD, centroidValues)
            out[i][F_ACC_DOM_FREQ] = interpClamped(frame, startD, domValues)
        }
    }

    /** `np.interp(x, xp, fp)`（xp 单调递增）——线性插值 + 两端取端点值。 */
    private fun interpClamped(x: Double, xp: DoubleArray, fp: DoubleArray): Double {
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

    companion object {
        const val DEFAULT_ROLLING_WINDOW = 10
        const val DEFAULT_FS = 50.0
        const val DEFAULT_SPECTRAL_WINDOW = 100
        const val DEFAULT_SPECTRAL_STRIDE = 10

        /** 原始输入维度（10）。 */
        const val RAW_DIM = 10

        /** 输出特征维度（24）。 */
        const val FEATURE_DIM = 24

        /** 频带数量（4）。 */
        const val BAND_COUNT = 4

        /** 频域派生列数量（4 频带 + centroid + dom_freq）。 */
        const val SPECTRAL_DIM = BAND_COUNT + 2

        /** 频带下限（Hz）：`[0.5,2) [2,5) [5,10) [10,25)`。 */
        val BAND_LO = doubleArrayOf(0.5, 2.0, 5.0, 10.0)

        /** 频带上限（Hz）。 */
        val BAND_HI = doubleArrayOf(2.0, 5.0, 10.0, 25.0)

        /**
         * 构造频域常量表（Hann 窗 / rfft 频率 / twiddle 因子）。
         *
         * 纯函数，只依赖 [spectralWindow] 与 [fs]；[FeatureExtractor] 用 lazy 缓存一份。
         * `internal` 仅供同模块单测校验"缓存表 == 现算表"。
         */
        internal fun buildSpectralTables(spectralWindow: Int, fs: Double): SpectralTables {
            require(spectralWindow > 1) { "spectralWindow 必须 > 1，收到 $spectralWindow" }
            require(fs > 0.0) { "fs 必须 > 0，收到 $fs" }
            val w = spectralWindow
            val hann = DoubleArray(w) { 0.5 - 0.5 * cos(2.0 * PI * it / (w - 1)) }
            val bins = w / 2 + 1
            val freqs = DoubleArray(bins) { it * fs / w }
            // 预计算 twiddle 表（-2πi*k*n / w），避免每个窗口重复三角函数
            val cosTab = DoubleArray(bins * w)
            val sinTab = DoubleArray(bins * w)
            for (b in 0 until bins) {
                val base = b * w
                for (k in 0 until w) {
                    val ang = -2.0 * PI * b * k / w
                    cosTab[base + k] = cos(ang)
                    sinTab[base + k] = sin(ang)
                }
            }
            return SpectralTables(hann, freqs, cosTab, sinTab, bins)
        }

        /** 原始 10 维列名（顺序不可改，见 AGENTS.md 4.1）。 */
        val RAW_NAMES: List<String> = listOf(
            "gps_speed",
            "acc_x", "acc_y", "acc_z",
            "gyro_x", "gyro_y", "gyro_z",
            "roll", "pitch", "yaw",
        )

        /** 24 维特征名（顺序不可改，见 AGENTS.md 4.2）。 */
        val FEATURE_NAMES: List<String> = listOf(
            "gps_speed",
            "acc_x", "acc_y", "acc_z",
            "gyro_x", "gyro_y", "gyro_z",
            "acc_magnitude", "gyro_magnitude", "speed_delta",
            "acc_rolling_mean", "acc_rolling_std",
            "gyro_rolling_mean", "gyro_rolling_std",
            "roll_rolling_mean", "roll_rolling_std",
            "pitch_rolling_mean", "vibration_score",
            "acc_bp_0.5_2", "acc_bp_2_5", "acc_bp_5_10", "acc_bp_10_25",
            "acc_centroid", "acc_dom_freq",
        )

        // 原始列索引
        private const val RAW_GPS_SPEED = 0
        private const val RAW_ACC_X = 1
        private const val RAW_ACC_Y = 2
        private const val RAW_ACC_Z = 3
        private const val RAW_GYRO_X = 4
        private const val RAW_GYRO_Y = 5
        private const val RAW_GYRO_Z = 6
        private const val RAW_ROLL = 7
        private const val RAW_PITCH = 8

        // 输出列索引
        private const val F_GPS_SPEED = 0
        private const val F_ACC_X = 1
        private const val F_ACC_Y = 2
        private const val F_ACC_Z = 3
        private const val F_GYRO_X = 4
        private const val F_GYRO_Y = 5
        private const val F_GYRO_Z = 6
        private const val F_ACC_MAGNITUDE = 7
        private const val F_GYRO_MAGNITUDE = 8
        private const val F_SPEED_DELTA = 9
        private const val F_ACC_ROLLING_MEAN = 10
        private const val F_ACC_ROLLING_STD = 11
        private const val F_GYRO_ROLLING_MEAN = 12
        private const val F_GYRO_ROLLING_STD = 13
        private const val F_ROLL_ROLLING_MEAN = 14
        private const val F_ROLL_ROLLING_STD = 15
        private const val F_PITCH_ROLLING_MEAN = 16
        private const val F_VIBRATION_SCORE = 17
        private const val F_BP_0_5_2 = 18
        private const val F_ACC_CENTROID = 22
        private const val F_ACC_DOM_FREQ = 23
    }
}

/**
 * 频域常量表（只读）。字段含义见 `FeatureExtractor.buildSpectralTables`。
 *
 * 暴露为 `internal` 便于单测比较"懒缓存表"与"重新构造的表"完全一致。
 */
internal class SpectralTables(
    val hann: DoubleArray,
    val freqs: DoubleArray,
    val cosTab: DoubleArray,
    val sinTab: DoubleArray,
    val bins: Int,
)
