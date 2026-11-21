package com.motioncontroller.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 真实会话切片 golden（B1，2026-11-05 补）：证明端上重采样/特征在真实录制数据上
 * 仍与训练侧参考实现（`Motion-ml/dataset/preprocess.py` 等）一致。
 *
 * 数据由 `tools/gen_golden.py` 生成（`app/src/test/resources/golden_real_session.json`）：
 *  - 会话 `WALKING_20260809_180043`（166Hz 老录制器，18% 行共享时间戳已按行序展开）；
 *  - 12s 切片（1252 行原始采样），含一次 799ms 真实停顿；
 *  - `gps_speed` 按端上约定：m/s → km/h，无 2s 内定位的帧置 0；
 *  - 期望重采样 552 行、两段各 270/282 行，窗口数 [3, 3]。
 */
class RealSessionGoldenTest {

    private val golden: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("golden_real_session.json")
            ?: error("golden_real_session.json 不在测试 classpath 上（应由 tools/gen_golden.py 生成）")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun JsonArray.toMatrix(): Array<DoubleArray> = Array(size) { i ->
        val row = this[i].jsonArray
        DoubleArray(row.size) { j ->
            row[j].jsonPrimitive.doubleOrNull ?: error("golden 数值解析失败: row=$i col=$j")
        }
    }

    private fun JsonObject.expectedResample(): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val resample = this["resample"]!!.jsonObject
        return resample["raw"]!!.jsonArray.toMatrix() to resample["expected"]!!.jsonArray.toMatrix()
    }

    /** 真实切片的重采样：逐元素与参考实现一致，且 799ms 真实停顿原样保留。 */
    @Test
    fun resampleMatchesGoldenOnRealSession() {
        val (raw, expected) = golden.expectedResample()
        assertEquals("真实切片原始行数", 1252, raw.size)
        assertEquals("真实切片重采样行数", 552, expected.size)
        assertEquals(11, raw[0].size)
        assertEquals(11, expected[0].size)

        // 真实数据时间戳必须严格单调（重复时间戳已由 session_loader 按行序展开）
        for (i in 1 until raw.size) {
            assertTrue("真实切片时间戳必须单调递增: i=$i", raw[i][0] > raw[i - 1][0])
        }

        val samples = raw.map { TimedFrame(it[0], it.copyOfRange(1, 11)) }
        val actual = Resampler.resample(samples)
        assertEquals("重采样行数", expected.size, actual.size)

        var maxError = 0.0
        var maxAt = ""
        for (i in expected.indices) {
            val errT = abs(actual[i].timeMs - expected[i][0])
            if (errT > maxError) {
                maxError = errT
                maxAt = "frame=$i time expected=${expected[i][0]} actual=${actual[i].timeMs}"
            }
            for (c in 0 until 10) {
                val err = abs(actual[i].values[c] - expected[i][c + 1])
                if (err > maxError) {
                    maxError = err
                    maxAt = "frame=$i col=$c expected=${expected[i][c + 1]} actual=${actual[i].values[c]}"
                }
            }
        }
        println("真实切片 resample max abs error = $maxError at $maxAt")
        assertTrue("真实切片 resample 与参考实现最大误差 $maxError（位置 $maxAt）超过 1e-5", maxError <= 1e-5)

        // 真实停顿（799ms）必须保留：输出里间隙内没有帧，最大间隔 ≈ 原始停顿 + 网格量化（≤20ms）
        val gapIndex = (1 until raw.size).first { raw[it][0] - raw[it - 1][0] > Resampler.MAX_GAP_MS }
        val rawGap = raw[gapIndex][0] - raw[gapIndex - 1][0]
        assertEquals("切片应只含一次真实停顿", 1, (1 until raw.size).count { raw[it][0] - raw[it - 1][0] > Resampler.MAX_GAP_MS })
        assertTrue("真实停顿应 > 200ms，实际 $rawGap", rawGap > Resampler.MAX_GAP_MS)
        val outGapIndex = (1 until actual.size)
            .first { actual[it].timeMs - actual[it - 1].timeMs > Resampler.MAX_GAP_MS }
        val outGap = actual[outGapIndex].timeMs - actual[outGapIndex - 1].timeMs
        assertTrue("输出最大间隔 $outGap 应 ≈ 原始停顿 $rawGap（允许网格量化 +20ms）", outGap in rawGap..(rawGap + 20.0 + 1e-9))
        assertTrue(
            "真实停顿区间 (${raw[gapIndex - 1][0]}, ${raw[gapIndex][0]}) 内不应有输出帧",
            actual.none { it.timeMs > raw[gapIndex - 1][0] && it.timeMs < raw[gapIndex][0] },
        )

        // 分段长度必须与参考一致：270 / 282
        assertEquals("段 1 行数", 270, outGapIndex)
        assertEquals("段 2 行数", 282, actual.size - outGapIndex)
    }

    /** 真实切片的 24 维特征与参考实现逐元素一致，且全部有限。 */
    @Test
    fun featuresMatchGoldenOnRealSession() {
        val features = golden["features"]!!.jsonObject
        val raw = features["raw"]!!.jsonArray.toMatrix()
        val expected = features["expected"]!!.jsonArray.toMatrix()
        assertEquals("真实切片 50Hz 行数", 552, raw.size)
        assertEquals(10, raw[0].size)
        assertEquals(552, expected.size)
        assertEquals(FeatureExtractor.FEATURE_DIM, expected[0].size)

        val actual = FeatureExtractor().transform(raw)
        assertEquals(expected.size, actual.size)

        var maxError = 0.0
        var maxAt = ""
        for (i in expected.indices) {
            for (j in 0 until FeatureExtractor.FEATURE_DIM) {
                assertTrue(
                    "frame=$i col=$j(${FeatureExtractor.FEATURE_NAMES[j]}) 出现非有限值 ${actual[i][j]}",
                    actual[i][j].isFinite(),
                )
                val err = abs(actual[i][j] - expected[i][j])
                if (err > maxError) {
                    maxError = err
                    maxAt = "frame=$i col=$j(${FeatureExtractor.FEATURE_NAMES[j]}) " +
                        "expected=${expected[i][j]} actual=${actual[i][j]}"
                }
            }
        }
        println("真实切片 features max abs error = $maxError at $maxAt")
        assertTrue("真实切片 features 与参考实现最大误差 $maxError（位置 $maxAt）超过 1e-5", maxError <= 1e-5)
    }

    /** 真实切片的分段长度与离线窗口数不变量（B4-lite）：270/282 → 每段 3 个窗口，共 6 个。 */
    @Test
    fun segmentLengthsAndWindowCountsMatchReference() {
        val (_, expectedResampled) = golden.expectedResample()
        val times = expectedResampled.map { it[0] }

        val lengths = ArrayList<Int>()
        var start = 0
        for (i in 1 until times.size) {
            if (times[i] - times[i - 1] > Resampler.MAX_GAP_MS) {
                lengths.add(i - start)
                start = i
            }
        }
        lengths.add(times.size - start)
        assertEquals("重采样分段长度", listOf(270, 282), lengths)

        val windows = lengths.map { n -> if (n < 150) 0 else (n - 150) / 50 + 1 }
        assertEquals("每段离线窗口数", listOf(3, 3), windows)
        assertEquals("总窗口数", 6, windows.sum())

        val goldenLengths = golden["resampledSegmentLengths"]!!.jsonArray.map { it.jsonPrimitive.int }
        val goldenWindows = golden["windowsPerSegment"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals("golden 段长与重算一致", goldenLengths, lengths)
        assertEquals("golden 窗口数与重算一致", goldenWindows, windows)

        // fixture 元数据守护：换会话/换切片时测试会明确报错，而不是悄悄换基准
        val session = golden["session"]!!.jsonObject
        assertEquals("WALKING_20260809_180043", session["id"]!!.jsonPrimitive.content)
        assertEquals("WALKING", session["label"]!!.jsonPrimitive.content)
    }
}
