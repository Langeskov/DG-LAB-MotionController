package com.motioncontroller.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * `FeatureExtractor` 的 golden vector 校验。
 *
 * `app/src/test/resources/golden_features.json` 由 `tools/gen_golden.py`
 * 用 Python/numpy/pandas 参考实现（`Motion-ml/dataset/preprocess.py`）生成：
 *  - `featureNames`: 24 维特征名（顺序契约）
 *  - `features.raw`: [260,10] 原始列
 *  - `features.expected`: [260,24] 参考特征
 */
class FeatureExtractorTest {

    private val golden: JsonObject by lazy { loadResource("golden_features.json") }

    private fun loadResource(name: String): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("$name 不在测试 classpath 上（应由 tools/gen_golden.py 生成）")
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun JsonArray.toMatrix(): Array<DoubleArray> = Array(size) { i ->
        val row = this[i].jsonArray
        DoubleArray(row.size) { j ->
            row[j].jsonPrimitive.doubleOrNull
                ?: error("golden 数值解析失败: row=$i col=$j")
        }
    }

    /** 24 维特征名与顺序必须与契约（AGENTS.md 4.2）一致。 */
    @Test
    fun featureNamesMatchContract() {
        val names = golden["featureNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(FeatureExtractor.FEATURE_DIM, names.size)
        assertEquals(FeatureExtractor.FEATURE_NAMES, names)
    }

    /** 原始 10 维列名顺序（AGENTS.md 4.1）。 */
    @Test
    fun rawColumnOrderMatchesContract() {
        assertEquals(
            listOf(
                "gps_speed",
                "acc_x", "acc_y", "acc_z",
                "gyro_x", "gyro_y", "gyro_z",
                "roll", "pitch", "yaw",
            ),
            FeatureExtractor.RAW_NAMES,
        )
        assertEquals(10, FeatureExtractor.RAW_DIM)
    }

    /** 全量 260 帧 × 24 维与 Python 参考实现逐元素比较。 */
    @Test
    fun matchesGoldenVector() {
        val features = golden["features"]!!.jsonObject
        val raw = features["raw"]!!.jsonArray.toMatrix()
        val expected = features["expected"]!!.jsonArray.toMatrix()
        assertEquals(260, raw.size)
        assertEquals(260, expected.size)

        val extractor = FeatureExtractor()
        val actual = extractor.transform(raw)
        assertEquals("行数必须与输入一致", expected.size, actual.size)

        var maxError = 0.0
        var maxAt = ""
        val perColumnMax = DoubleArray(FeatureExtractor.FEATURE_DIM)
        for (i in expected.indices) {
            assertEquals(FeatureExtractor.FEATURE_DIM, actual[i].size)
            for (j in 0 until FeatureExtractor.FEATURE_DIM) {
                val err = abs(actual[i][j] - expected[i][j])
                if (err > perColumnMax[j]) perColumnMax[j] = err
                if (err > maxError) {
                    maxError = err
                    maxAt = "frame=$i col=$j(${FeatureExtractor.FEATURE_NAMES[j]}) " +
                        "expected=${expected[i][j]} actual=${actual[i][j]}"
                }
            }
        }
        println("FeatureExtractor golden max abs error = $maxError at $maxAt")
        for (j in 0 until FeatureExtractor.FEATURE_DIM) {
            println("  col[$j] ${FeatureExtractor.FEATURE_NAMES[j]} maxErr=${perColumnMax[j]}")
        }
        assertTrue(
            "与 golden 最大绝对误差 $maxError （位置 $maxAt）超过 1e-5",
            maxError <= 1e-5,
        )
    }

    /** 常量输入下，派生列应给出可预期的值（不依赖 golden 的健全性检查）。 */
    @Test
    fun constantInputProducesExpectedDerivedColumns() {
        val n = 120
        val raw = Array(n) {
            doubleArrayOf(36.0, 0.0, 0.0, 9.80665, 0.0, 0.0, 0.0, 10.0, 20.0, 30.0)
        }
        val out = FeatureExtractor().transform(raw)
        for (i in 0 until n) {
            val row = out[i]
            assertEquals(36.0, row[0], 1e-12) // gps_speed 原样保留
            assertEquals(9.80665, row[7], 1e-9) // acc_magnitude
            assertEquals(0.0, row[8], 1e-12) // gyro_magnitude
            assertEquals(0.0, row[9], 1e-12) // speed_delta 首帧及常量输入均为 0
            assertEquals(9.80665, row[10], 1e-9) // acc_rolling_mean
            assertEquals(0.0, row[11], 1e-9) // acc_rolling_std（常量）
            assertEquals(0.0, row[17], 1e-12) // vibration_score
            assertEquals(10.0, row[14], 1e-9) // roll_rolling_mean（原始 roll 丢弃但派生保留）
            assertEquals(20.0, row[16], 1e-9) // pitch_rolling_mean
            // 常量信号去均值后全 0 → 频谱全 0 → 频带占比 0、centroid 0（dom_freq 由 argmax 决定）
            for (j in 18..22) assertEquals(0.0, row[j], 1e-9)
            assertTrue("dom_freq 应在 [0, fs/2] 内，实际 ${row[23]}", row[23] in 0.0..25.0)
        }
    }

    /** 样本数 < spectral_window 时频域列全 0（preprocess.py 的行为）。 */
    @Test
    fun tooFewSamplesGivesZeroSpectral() {
        val raw = Array(5) { i ->
            doubleArrayOf(0.0, i.toDouble(), 0.0, 9.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val out = FeatureExtractor().transform(raw)
        for (i in out.indices) for (j in 18..23) assertEquals(0.0, out[i][j], 0.0)
    }

    /** 空输入不应崩溃。 */
    @Test
    fun emptyInputIsSupported() {
        assertNotNull(FeatureExtractor().transform(emptyArray()))
        assertTrue(FeatureExtractor().transform(emptyArray()).isEmpty())
    }

    /** 错误列数应抛异常（契约保护）。 */
    @Test
    fun wrongRawDimThrows() {
        try {
            FeatureExtractor().transform(arrayOf(DoubleArray(9)))
            fail("应抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("10"))
        }
    }

    /** 频率/滚动参数必须与训练一致。 */
    @Test
    fun defaultsMatchTrainingConfig() {
        assertEquals(10, FeatureExtractor.DEFAULT_ROLLING_WINDOW)
        assertEquals(50.0, FeatureExtractor.DEFAULT_FS, 0.0)
        assertEquals(100, FeatureExtractor.DEFAULT_SPECTRAL_WINDOW)
        assertEquals(10, FeatureExtractor.DEFAULT_SPECTRAL_STRIDE)
        assertArrayEquals(doubleArrayOf(0.5, 2.0, 5.0, 10.0), FeatureExtractor.BAND_LO, 0.0)
        assertArrayEquals(doubleArrayOf(2.0, 5.0, 10.0, 25.0), FeatureExtractor.BAND_HI, 0.0)
    }

    /** 2026-10-31 audit L6：任意一行的列数错误都要立刻抛清晰异常（不只校验第 0 行）。 */
    @Test
    fun wrongRawDimInAnyRowThrowsWithRowIndex() {
        val raw = Array(4) { DoubleArray(FeatureExtractor.RAW_DIM) }
        raw[2] = DoubleArray(9)
        try {
            FeatureExtractor().transform(raw)
            fail("第 2 行列数错误应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("错误信息应包含行号：${e.message}", e.message!!.contains("第 2 行"))
            assertTrue("错误信息应包含期望维度：${e.message}", e.message!!.contains("10"))
        }
    }

    /**
     * 频域常量表缓存（Hann/freqs/twiddle）不改变结果：
     *  - 同一 extractor 反复 transform、以及不同 extractor 之间，特征必须逐位一致；
     *  - 缓存的表与重新构造的表逐元素一致（防止懒加载逻辑改错公式）。
     */
    @Test
    fun cachedSpectralTablesProduceIdenticalFeatures() {
        val features = golden["features"]!!.jsonObject
        val raw = features["raw"]!!.jsonArray.toMatrix()

        val extractor = FeatureExtractor()
        val first = extractor.transform(raw)
        val second = extractor.transform(raw) // 复用懒加载表
        val fresh = FeatureExtractor().transform(raw) // 另一个实例（表也是独立构建）
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertArrayEquals("同一实例第 $i 行应完全一致", first[i], second[i], 0.0)
            assertArrayEquals("新实例第 $i 行应完全一致", first[i], fresh[i], 0.0)
        }

        // 缓存的表 == 重新构造的表（公式/尺寸）
        val a = FeatureExtractor.buildSpectralTables(
            FeatureExtractor.DEFAULT_SPECTRAL_WINDOW,
            FeatureExtractor.DEFAULT_FS,
        )
        val b = FeatureExtractor.buildSpectralTables(
            FeatureExtractor.DEFAULT_SPECTRAL_WINDOW,
            FeatureExtractor.DEFAULT_FS,
        )
        assertEquals(FeatureExtractor.DEFAULT_SPECTRAL_WINDOW / 2 + 1, a.bins)
        assertEquals(FeatureExtractor.DEFAULT_SPECTRAL_WINDOW, a.hann.size)
        assertEquals(a.bins, a.freqs.size)
        assertEquals(a.bins * FeatureExtractor.DEFAULT_SPECTRAL_WINDOW, a.cosTab.size)
        assertArrayEquals(a.hann, b.hann, 0.0)
        assertArrayEquals(a.freqs, b.freqs, 0.0)
        assertArrayEquals(a.cosTab, b.cosTab, 0.0)
        assertArrayEquals(a.sinTab, b.sinTab, 0.0)
    }

    /**
     * GNSS 掉点回归（B2，2026-11-05 补）：gps_speed 在第 150 帧从 50.4 km/h 阶跃到 0
     * （模拟定位过期 → 速度归零），要求：
     *  - 特征无非有限值（NaN/Inf 会静默污染 scaler 与模型输入）；
     *  - speed_delta 只在掉点帧出现一次 -50.4 阶跃，之后回到 0（不能持续/放大）；
     *  - 24 维与 Python 参考实现一致。
     */
    @Test
    fun gnssDropoutStepHasNoNaNAndMatchesReference() {
        val g = loadResource("golden_gnss_dropout.json")
        val meta = g["meta"]!!.jsonObject
        assertEquals(300, meta["frames"]!!.jsonPrimitive.int)
        assertEquals(150, meta["stepAtFrame"]!!.jsonPrimitive.int)

        val raw = g["raw"]!!.jsonArray.toMatrix()
        val expected = g["expected"]!!.jsonArray.toMatrix()
        assertEquals(300, raw.size)
        assertEquals(300, expected.size)
        assertEquals(FeatureExtractor.FEATURE_DIM, expected[0].size)
        assertEquals(50.4, raw[149][0], 1e-12)
        assertEquals(0.0, raw[150][0], 0.0)

        val actual = FeatureExtractor().transform(raw)
        var maxError = 0.0
        for (i in actual.indices) {
            for (j in 0 until FeatureExtractor.FEATURE_DIM) {
                assertTrue(
                    "frame=$i col=$j(${FeatureExtractor.FEATURE_NAMES[j]}) 出现非有限值 ${actual[i][j]}",
                    actual[i][j].isFinite(),
                )
                maxError = maxOf(maxError, abs(actual[i][j] - expected[i][j]))
            }
        }
        assertTrue("GNSS 掉点 golden 最大误差 $maxError 超过 1e-5", maxError <= 1e-5)

        // speed_delta：掉点前恒 0，掉点帧 -50.4，之后恒 0
        for (i in 0 until 149) assertEquals("frame=$i", 0.0, actual[i][9], 1e-12)
        assertEquals("掉点帧 speed_delta", -50.4, actual[150][9], 1e-12)
        for (i in 151 until actual.size) assertEquals("frame=$i", 0.0, actual[i][9], 1e-12)
    }
}
