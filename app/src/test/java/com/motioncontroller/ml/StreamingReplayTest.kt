package com.motioncontroller.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流式重放 golden 测试：证明文档化的「最近 ≤250 帧算特征，再取最后 150 行」路径
 * 与 preprocess.py 语义一致（2026-10-31 audit HIGH）。
 *
 * 用 `golden_features.json` 的 260 行原始数据逐行喂 [SlidingWindowBuffer]（与端上
 * MotionPipeline 完全相同的入窗方式），在 totalFrames=150/200/250 各产出一个窗口：
 *  - 窗口产出的绝对帧范围、bufferStart % 10 == 0（频域网格对齐）必须符合契约；
 *  - 窗口内容必须就是最后 150 帧；
 *  - 非频域 24 维中的 0..17 列只依赖当前/历史帧，应与整段离线 golden 完全一致；
 *  - 频域列（18..23）在 `bufferRow <= n - spectralWindow` 区间内不依赖"未来帧"，
 *    也应与离线 golden 一致；之后的区间端上按设计 clamp（无 lookahead），不在本断言范围。
 */
class StreamingReplayTest {

    private val golden: JsonObject by lazy { loadGolden() }

    private fun loadGolden(): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("golden_features.json")
            ?: error("golden_features.json 不在测试 classpath 上（应在 app/src/test/resources/）")
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun JsonArray.toMatrix(): Array<DoubleArray> = Array(size) { i ->
        val row = this[i].jsonArray
        DoubleArray(row.size) { j ->
            row[j].jsonPrimitive.doubleOrNull ?: error("golden 数值解析失败: row=$i col=$j")
        }
    }

    private fun assertRowEquals(message: String, rowIndex: Int, expected: DoubleArray, actual: DoubleArray) {
        assertEquals("$message 列数不一致", expected.size, actual.size)
        for (c in expected.indices) {
            assertEquals("$message frame=$rowIndex col=$c", expected[c], actual[c], 1e-5)
        }
    }

    @Test
    fun streamingReplayMatchesGoldenWhereNoLookaheadIsNeeded() {
        val featuresJson = golden["features"]!!.jsonObject
        val raw = featuresJson["raw"]!!.jsonArray.toMatrix()
        val expected = featuresJson["expected"]!!.jsonArray.toMatrix()
        assertEquals(260, raw.size)

        val extractor = FeatureExtractor()
        val buffer = SlidingWindowBuffer() // 150 / 50 / 250（端上默认契约）
        val windows = ArrayList<Pair<Long, Array<DoubleArray>>>()
        for (i in raw.indices) {
            val out = buffer.push(raw[i]) ?: continue
            windows.add(buffer.totalFrames to out)
        }

        // 首次产窗在 150 帧，之后每 50 帧一次；缓冲未满历史上限时行数随窗口增长
        assertEquals(listOf(150L, 200L, 250L), windows.map { it.first })
        assertEquals(listOf(150, 200, 250), windows.map { it.second.size })

        var checkedSpectralRows = 0
        for ((totalFrames, rows) in windows) {
            val n = rows.size
            val bufferStartAbs = totalFrames - n
            assertEquals(
                "bufferStart($bufferStartAbs) 必须落在 50Hz 频域网格（%10==0）上",
                0L,
                bufferStartAbs % FeatureExtractor.DEFAULT_SPECTRAL_STRIDE,
            )
            assertEquals(minOf(totalFrames, SlidingWindowBuffer.DEFAULT_HISTORY_FRAMES.toLong()), n.toLong())

            // 端上路径：transform(最近 ≤250 帧) → 取最后 150 行作为模型输入
            val allFeatures = extractor.transform(rows)
            assertEquals(n, allFeatures.size)
            val modelWindow = allFeatures.copyOfRange(n - SlidingWindowBuffer.DEFAULT_WINDOW_SIZE, n)
            assertEquals(SlidingWindowBuffer.DEFAULT_WINDOW_SIZE, modelWindow.size)
            val windowStartAbs = totalFrames - SlidingWindowBuffer.DEFAULT_WINDOW_SIZE

            for (r in modelWindow.indices) {
                val abs = (windowStartAbs + r).toInt()
                val bufferRow = (abs - bufferStartAbs).toInt()
                // 行身份：第 1 列是 acc_x，必须等于该绝对帧的原始值
                assertEquals("modelWindow[$r] 应对应绝对帧 $abs", raw[abs][1], modelWindow[r][1], 0.0)
                // 时域列 0..17：逐元素与离线 golden 一致
                assertRowEquals(
                    "streaming total=$totalFrames abs=$abs 时域特征",
                    abs,
                    expected[abs].copyOfRange(0, 18),
                    modelWindow[r].copyOfRange(0, 18),
                )
                // 频域列：buffer 行号 <= n-spectralWindow 时不需要未来帧，与离线一致
                if (bufferRow <= n - FeatureExtractor.DEFAULT_SPECTRAL_WINDOW) {
                    assertRowEquals(
                        "streaming total=$totalFrames abs=$abs 频域特征",
                        abs,
                        expected[abs].copyOfRange(18, 24),
                        modelWindow[r].copyOfRange(18, 24),
                    )
                    checkedSpectralRows++
                }
            }
        }
        // 至少覆盖了窗口起点附近的频域对齐（150/200/250 三个窗口都应有一部分可对齐行）
        assertEquals(51 + 51 + 51, checkedSpectralRows)
    }
}
