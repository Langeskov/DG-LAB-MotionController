package com.motioncontroller.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * `Resampler` 的 golden vector 校验 + 分段语义单测。
 *
 * golden（`tools/gen_golden.py::make_irregular`）：
 *  - `resample.raw`: 160 行 11 列（timestamp + 10 原始列），两段不规则采样，
 *    段间停顿 500ms（> 200ms 断段阈值）；
 *  - `resample.expected`: 每段独立重采样到 50Hz 后拼接的参考结果。
 */
class ResamplerTest {

    private val golden: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("golden_features.json")
            ?: error("golden_features.json 不在测试 classpath 上（应在 app/src/test/resources/）")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun JsonArray.toMatrix(): Array<DoubleArray> = Array(size) { i ->
        val row = this[i].jsonArray
        DoubleArray(row.size) { j ->
            row[j].jsonPrimitive.doubleOrNull ?: error("golden 数值解析失败: row=$i col=$j")
        }
    }

    @Test
    fun matchesGoldenResample() {
        val resample = golden["resample"]!!.jsonObject
        val raw = resample["raw"]!!.jsonArray.toMatrix()
        val expected = resample["expected"]!!.jsonArray.toMatrix()
        assertEquals(160, raw.size)
        assertEquals(11, raw[0].size)

        // 原始时间戳必须单调（真实端上时间轴单调；golden 也保证这一点）
        for (i in 1 until raw.size) {
            assertTrue("raw 时间戳必须单调递增: i=$i", raw[i][0] > raw[i - 1][0])
        }

        val samples = raw.map { TimedFrame(it[0], it.copyOfRange(1, 11)) }
        val actual = Resampler.resample(samples)

        assertEquals("重采样后行数", expected.size, actual.size)
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
        println("Resampler golden max abs error = $maxError at $maxAt")
        assertTrue("与 golden 最大绝对误差 $maxError （位置 $maxAt）超过 1e-5", maxError <= 1e-5)
    }

    /** 两段之间（>200ms）不允许跨段插值：网格起点必须重新锚定到新段首个样本。 */
    @Test
    fun doesNotInterpolateAcrossGap() {
        val a = (0..10).map { TimedFrame(it * 10.0, DoubleArray(10) { c -> c.toDouble() + it }) }
        // 停顿 500ms 后新段
        val b = (0..5).map { TimedFrame(600.0 + it * 10.0, DoubleArray(10) { c -> 100.0 + c + it }) }

        val out = Resampler.resample(a + b)

        // 段 1: t0=0, t_last=100 → n = floor(1.0*50)+1 = 51 → 0,20,...,1000? 不，dur=100ms
        //   实际 0..100ms，步长 20ms → 0,20,40,60,80,100 → n = floor(0.1*50)+1 = 6
        val seg1Times = out.takeWhile { it.timeMs < 600.0 }.map { it.timeMs }
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), seg1Times)

        // 段 2: t0=600, t_last=650 → n = floor(0.05*50)+1 = 3 → 600,620,640
        val seg2 = out.drop(seg1Times.size)
        assertEquals(listOf(600.0, 620.0, 640.0), seg2.map { it.timeMs })

        // 段 1 起始值来自段 1 首样本，段 2 起始值来自段 2 首样本（不是跨段插值）
        assertEquals(0.0, out.first().values[0], 1e-12)
        assertEquals(100.0, seg2.first().values[0], 1e-12)

        // 段内线性插值正确：t=20 → 第 2 个原始样本（值 +2）
        assertEquals(2.0, out[1].values[0], 1e-12)
        // 段 2 内 t=620 → 第 151 个原始样本 idx=2 → 100+2
        assertEquals(102.0, seg2[1].values[0], 1e-12)
    }

    /** 段内只有 1 个样本时原样透传（参考实现行为）。 */
    @Test
    fun singleSampleSegmentPassesThrough() {
        val samples = listOf(
            TimedFrame(0.0, DoubleArray(10) { 1.0 }),
            TimedFrame(1000.0, DoubleArray(10) { 2.0 }),
        )
        val out = Resampler.resample(samples)
        assertEquals(2, out.size)
        assertEquals(0.0, out[0].timeMs, 0.0)
        assertEquals(1000.0, out[1].timeMs, 0.0)
        assertEquals(1.0, out[0].values[0], 0.0)
        assertEquals(2.0, out[1].values[0], 0.0)
    }

    /** 不规则采样但无断段时：网格步长稳定 20ms，端点 clamp。 */
    @Test
    fun unevenSamplesProduceUniformGrid() {
        val times = doubleArrayOf(0.0, 3.0, 41.0, 42.0, 90.0, 103.0)
        val samples = times.map { t -> TimedFrame(t, DoubleArray(10) { t }) }
        val out = Resampler.resample(samples)
        assertEquals(6, out.size) // dur=103ms → floor(5.15)+1 = 6
        for (k in out.indices) {
            assertEquals(k * 20.0, out[k].timeMs, 1e-9)
            // 每列都是对 t 自身的线性插值 → 值 == 网格时间（np.interp 在单调数据上的性质）
            assertEquals(out[k].timeMs, out[k].values[0], 1e-9)
        }
    }

    /** 输入不足 2 帧时原样返回。 */
    @Test
    fun tooFewSamplesReturnedAsIs() {
        assertEquals(0, Resampler.resample(emptyList()).size)
        val one = listOf(TimedFrame(5.0, DoubleArray(10)))
        assertEquals(1, Resampler.resample(one).size)
    }

    /** 流式重采样与批量重采样在单调输入上逐帧一致。 */
    @Test
    fun streamingMatchesBatchOnMonotonicInput() {
        val times = doubleArrayOf(0.0, 8.0, 33.0, 41.0, 66.0, 74.0, 99.0, 130.0, 158.0, 191.0)
        val samples = times.mapIndexed { i, t ->
            TimedFrame(t, DoubleArray(10) { c -> i * 10.0 + c })
        }
        val batch = Resampler.resample(samples)

        val streaming = StreamingResampler()
        val streamed = ArrayList<TimedFrame>()
        for (s in samples) streamed.addAll(streaming.accept(s.timeMs, s.values))

        assertEquals(batch.size, streamed.size)
        for (i in batch.indices) {
            assertEquals(batch[i].timeMs, streamed[i].timeMs, 1e-9)
            for (c in 0 until 10) {
                assertEquals("frame $i col $c", batch[i].values[c], streamed[i].values[c], 1e-9)
            }
        }
    }

    /** 流式重采样与批量重采样在「含 >200ms 停顿」的输入上也逐帧一致。 */
    @Test
    fun streamingMatchesBatchWithGap() {
        val times = doubleArrayOf(
            0.0, 8.0, 33.0, 41.0, 66.0, 74.0, 99.0, 130.0, 158.0, 191.0, // 段 1
            500.0, 508.0, 533.0, 541.0, 566.0, 574.0, 599.0, // 段 2（停顿 309ms）
        )
        val samples = times.mapIndexed { i, t ->
            TimedFrame(t, DoubleArray(10) { c -> i * 10.0 + c })
        }
        val batch = Resampler.resample(samples)
        assertEquals(15, batch.size) // 段 1: 0..180（10 帧）；段 2: 500..580（5 帧）

        val streaming = StreamingResampler()
        val streamed = ArrayList<TimedFrame>()
        for (s in samples) streamed.addAll(streaming.accept(s.timeMs, s.values))

        assertEquals(batch.size, streamed.size)
        for (i in batch.indices) {
            assertEquals(batch[i].timeMs, streamed[i].timeMs, 1e-9)
            for (c in 0 until 10) {
                assertEquals("frame $i col $c", batch[i].values[c], streamed[i].values[c], 1e-9)
            }
        }
    }

    /**
     * 大间隙回归（邮件提醒的测试缺口）：约 200 帧、中间断 5 秒的数据走重采样，
     * 间隙必须原样保留，不能被线性插值「补」出虚构帧。
     *
     * 构造（规整 50Hz，便于手算期望）：
     *  - 段 1：100 帧，t=1000..2980ms，值恒为 1.0；
     *  - 停顿 5000ms（远大于 MAX_GAP_MS=200ms，会被切成两段）；
     *  - 段 2：100 帧，t=7980..9960ms，值恒为 100.0。
     *
     * 若实现错误地整段插值：行数 = floor((9960-1000)/1000*50)+1 = 449，且间隙里全是
     * 1.0~100.0 的虚构中间值；正确实现只有两段各自的 100 行网格。
     */
    @Test
    fun largeGapIsPreservedByBatchResample() {
        fun frame(t: Double, v: Double) = TimedFrame(t, DoubleArray(10) { v })
        val pre = (0 until 100).map { frame(1000.0 + it * 20.0, 1.0) }
        val post = (0 until 100).map { frame(7980.0 + it * 20.0, 100.0) }
        val samples = pre + post
        assertEquals(200, samples.size)
        assertEquals(5000.0, post.first().timeMs - pre.last().timeMs, 0.0)

        val out = Resampler.resample(samples)

        // 1) 行数不被插值撑大：两段各 n = floor(1.98*50)+1 = 100 行，共 200 行（错误实现约 449 行）
        assertEquals("跨间隙插值会把行数从 200 撑到约 449", 200, out.size)

        // 2) 间隙仍在：相邻输出帧最大间隔 == 原始 5000ms（50Hz 规整输入下网格恰好落在末样本上）
        val maxInterval = (1 until out.size).maxOf { out[it].timeMs - out[it - 1].timeMs }
        assertEquals("最大间隔应等于原始停顿", 5000.0, maxInterval, 1e-9)
        assertTrue("间隙内不应出现任何输出帧", out.none { it.timeMs > 2980.0 && it.timeMs < 7980.0 })

        // 3) 段内严格等间隔 20ms，且两段网格各自锚定在段首样本
        val seg1 = out.take(100)
        val seg2 = out.drop(100)
        assertEquals(1000.0, seg1.first().timeMs, 1e-9)
        assertEquals(7980.0, seg2.first().timeMs, 1e-9)
        for (seg in listOf(seg1, seg2)) {
            for (i in 1 until seg.size) {
                assertEquals("段内网格必须等间隔", 20.0, seg[i].timeMs - seg[i - 1].timeMs, 1e-9)
            }
        }

        // 4) 没有跨段数值污染：段 1 恒 1.0，段 2 恒 100.0
        assertTrue("段 1 被段 2 的值污染（疑似跨段插值）", seg1.all { f -> f.values.all { it == 1.0 } })
        assertTrue("段 2 被段 1 的值污染（疑似跨段插值）", seg2.all { f -> f.values.all { it == 100.0 } })
    }

    /** 同一份 5s 大间隙数据走流式实现（端上真实路径）：行数/间隙/段索引与批量语义一致。 */
    @Test
    fun largeGapIsPreservedByStreamingResampler() {
        fun frame(t: Double, v: Double) = TimedFrame(t, DoubleArray(10) { v })
        val samples = (0 until 100).map { frame(1000.0 + it * 20.0, 1.0) } +
            (0 until 100).map { frame(7980.0 + it * 20.0, 100.0) }

        val streaming = StreamingResampler()
        val out = ArrayList<TimedFrame>()
        for (s in samples) out.addAll(streaming.accept(s.timeMs, s.values))

        assertEquals(200, out.size)
        assertEquals("断段应让 segmentIndex +1（管线据此不跨段切窗）", 1L, streaming.segmentIndex)
        assertEquals("emittedFrames 与实际输出帧数一致", out.size.toLong(), streaming.emittedFrames)

        val maxInterval = (1 until out.size).maxOf { out[it].timeMs - out[it - 1].timeMs }
        assertEquals("最大间隔应等于原始停顿", 5000.0, maxInterval, 1e-9)
        assertTrue("间隙内不应出现任何输出帧", out.none { it.timeMs > 2980.0 && it.timeMs < 7980.0 })
        assertEquals(7980.0, out[100].timeMs, 1e-9)
        assertEquals(100.0, out[100].values[0], 1e-12)
        assertTrue("段 1 被段 2 的值污染", out.take(100).all { f -> f.values.all { it == 1.0 } })
        assertTrue("段 2 被段 1 的值污染", out.drop(100).all { f -> f.values.all { it == 100.0 } })
    }

    /** 流式重采样在断段处重新锚定，且不会补出跨段插值帧。 */
    @Test
    fun streamingReanchorsOnGap() {
        val streaming = StreamingResampler()
        val out = ArrayList<TimedFrame>()
        for (i in 0..10) out.addAll(streaming.accept(i * 10.0, DoubleArray(10) { i.toDouble() }))
        val beforeGap = out.size
        // 500ms 停顿
        out.addAll(streaming.accept(600.0, DoubleArray(10) { 777.0 }))
        out.addAll(streaming.accept(620.0, DoubleArray(10) { 778.0 }))

        assertEquals(600.0, out[beforeGap].timeMs, 1e-9)
        assertEquals(777.0, out[beforeGap].values[0], 1e-12)
        assertEquals(620.0, out[beforeGap + 1].timeMs, 1e-9)
        assertTrue("断段后不应产生 100..580 之间的补帧", out.none { it.timeMs in 100.1..599.9 })
        assertEquals("断段应递增 segmentIndex（供管线不跨段切窗）", 1L, streaming.segmentIndex)
    }

    /** 非单调时间戳不应崩溃（保持网格，仅刷新最新值）。 */
    @Test
    fun streamingToleratesNonMonotonicTime() {
        val streaming = StreamingResampler()
        streaming.accept(100.0, DoubleArray(10) { 1.0 })
        streaming.accept(100.0, DoubleArray(10) { 2.0 })
        streaming.accept(90.0, DoubleArray(10) { 3.0 })
        val frames = streaming.accept(120.0, DoubleArray(10) { 4.0 })
        assertTrue(frames.isNotEmpty())
        assertEquals(120.0, frames.last().timeMs, 1e-9)
    }
}
