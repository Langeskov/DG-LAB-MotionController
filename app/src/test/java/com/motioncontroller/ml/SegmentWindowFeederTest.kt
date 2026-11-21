package com.motioncontroller.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SegmentWindowFeeder] 单测：固定「重采样断段 → 滑窗复位」胶水的语义。
 *
 * 契约（从 `MotionPipeline.handleSample` 抽取，必须逐行等价）：
 *  - >200ms 停顿由 [StreamingResampler] 切成新段；
 *  - 段号变化且滑窗 `totalFrames > 0` → **先** `reset()` 再 push 新段帧（不跨段切窗）；
 *  - 恰好 200ms 不算断段（与 [Resampler.MAX_GAP_MS] 的 `>` 判定一致），200.001ms 算断段；
 *  - 断段前未满窗的帧被丢弃，新段必须重新攒满 150 帧才出窗，之后步长 50；
 *  - `reset()` 同时清空重采样器与滑窗。
 *
 * 信号自造：规整 50Hz（每 20ms 一个原始采样）时流式重采样每次 accept 恰好产出 1 帧，
 * 且网格点落在原始采样上（列 0 的值 = 该帧 id，无插值误差）。不依赖 golden 文件。
 */
class SegmentWindowFeederTest {

    @Test
    fun noGapEmitsWindowAt150ThenEvery50() {
        val h = Harness()
        h.feed(0.0, 400, firstId = 1)

        // 首次出窗在累计 150 帧，之后每 +50 帧一次
        assertEquals(listOf(150L, 200L, 250L, 300L, 350L, 400L), h.emittedAtTotalFrames)
        // 前两个窗口只有窗口本身（150/200 行），之后缓冲增长到历史上限 250 行
        assertEquals(listOf(150, 200, 250, 250, 250, 250), h.windows.map { it.size })

        assertEquals("无断段时 segmentIndex 不应变化", 0L, h.feeder.segmentIndex)
        assertEquals(150, h.feeder.bufferedFrames)
        assertEquals("400 帧后真实缓冲应封顶在历史上限", 250, h.feeder.bufferedRawFrames)

        val last = h.windows.last()
        assertEquals("窗口末尾 = 最新帧", 400.0, last.last()[0], 0.0)
        assertEquals("最近 250 帧 = 151..400", 151.0, last.first()[0], 0.0)
        h.windows.forEachIndexed { idx, window ->
            assertEquals("window $idx 末尾应为最新帧", (idx * 50 + 150).toDouble(), window.last()[0], 0.0)
            for (i in 1 until window.size) {
                assertEquals("window $idx 内帧 id 必须连续（不得跳帧）", 1.0, window[i][0] - window[i - 1][0], 0.0)
            }
        }
    }

    @Test
    fun gapDiscardsPartialWindowAndRestartsWarmUp() {
        val h = Harness()
        assertEquals(0, h.feed(0.0, 100, firstId = 1))
        assertEquals(100L, h.feeder.totalFrames)
        assertEquals(100, h.feeder.bufferedFrames)
        assertEquals(0L, h.feeder.segmentIndex)
        assertTrue(h.windows.isEmpty())

        // 断段：距上一采样 3020ms（> 200ms）
        assertEquals("断段的那个采样本身不足以出窗", 0, h.accept(5000.0, 1000))
        assertEquals(1L, h.feeder.segmentIndex)
        assertTrue("断段且缓冲非空时必须复位滑窗", h.feeder.lastAcceptResetWindow)
        assertEquals("断段前未满窗的 100 帧被丢弃", 1L, h.feeder.totalFrames)
        assertEquals(1, h.feeder.bufferedFrames)
        assertTrue(h.windows.isEmpty())

        // 新段重新攒：149 帧时仍未出窗
        assertEquals(0, h.feed(5020.0, 148, firstId = 1001))
        assertEquals(149L, h.feeder.totalFrames)
        assertTrue(h.windows.isEmpty())
        assertEquals("第 150 帧才出窗", 1, h.accept(5020.0 + 148 * 20.0, 1149))

        assertEquals(1, h.windows.size)
        assertEquals(listOf(150L), h.emittedAtTotalFrames)
        val window = h.windows.single()
        assertEquals("新段窗口应从新段首帧重新攒满", 150, window.size)
        assertEquals("窗口第一行 = 新段首帧", 1000.0, window.first()[0], 0.0)
        assertEquals("窗口最后一帧 = 最新帧", 1149.0, window.last()[0], 0.0)
        assertTrue(
            "断段前的帧不得出现在新段窗口里",
            window.all { row -> row[0] >= 1000.0 },
        )
    }

    @Test
    fun consecutiveGapsWithShortMiddleSegmentNeverCrossSegments() {
        val h = Harness()
        assertEquals(0, h.feed(0.0, 100, firstId = 1)) // 段 1：100 帧（未满窗）
        assertEquals(0, h.accept(5000.0, 2000)) // 断段 1 → 段 2
        assertEquals(0, h.feed(5020.0, 30, firstId = 2001)) // 段 2：31 帧（短段）
        assertEquals(0, h.accept(10000.0, 3000)) // 断段 2 → 段 3
        assertEquals(0, h.feed(10020.0, 20, firstId = 3001)) // 段 3：21 帧

        assertEquals("短段 + 连续断段都不得提前/跨段出窗", 0, h.windows.size)
        assertEquals(2L, h.feeder.segmentIndex)
        assertEquals("段 3 从断段帧重新计数", 21L, h.feeder.totalFrames)
        assertEquals(21, h.feeder.bufferedFrames)
        assertEquals(21, h.feeder.bufferedRawFrames)

        // 段 3 攒满 150 帧才出窗（21 + 129 = 150）
        assertEquals(1, h.feed(10420.0, 129, firstId = 3021))
        assertEquals(1, h.windows.size)
        assertEquals(listOf(150L), h.emittedAtTotalFrames)
        val window = h.windows.single()
        assertEquals(150, window.size)
        assertEquals("窗口第一行 = 段 3 首帧", 3000.0, window.first()[0], 0.0)
        assertEquals("窗口最后一帧 = 最新帧", 3149.0, window.last()[0], 0.0)
        assertTrue(
            "窗口不得混入段 1/段 2 的帧",
            window.all { row -> row[0] >= 3000.0 },
        )
    }

    @Test
    fun exactly200msStaysInSameSegmentButJustOverStartsNewOne() {
        // 恰好 200ms：Resampler 用 `> maxGapMs` 判定，仍是同一段（不清窗，网格补帧）
        val same = Harness()
        same.feed(0.0, 100, firstId = 1)
        assertEquals(0, same.accept(2180.0, 101))
        assertEquals("恰好 200ms 不得断段", 0L, same.feeder.segmentIndex)
        assertFalse("恰好 200ms 不得复位滑窗", same.feeder.lastAcceptResetWindow)
        // 1980ms → 2180ms 的 200ms 间隔内，网格 2000/2020/.../2180 共 10 帧追加
        assertEquals(110L, same.feeder.totalFrames)
        assertEquals(110, same.feeder.bufferedFrames)
        assertTrue(same.windows.isEmpty())

        // 200.001ms：断段 → 滑窗复位，只保留新段首帧
        val gap = Harness()
        gap.feed(0.0, 100, firstId = 1)
        assertEquals(0, gap.accept(2180.001, 101))
        assertEquals("200.001ms 必须断段", 1L, gap.feeder.segmentIndex)
        assertTrue("断段且缓冲非空时必须复位滑窗", gap.feeder.lastAcceptResetWindow)
        assertEquals("断段后滑窗只含新段首帧", 1L, gap.feeder.totalFrames)
        assertEquals(1, gap.feeder.bufferedFrames)
    }

    @Test
    fun resetClearsResamplerAndWindow() {
        val h = Harness()
        h.feed(0.0, 200, firstId = 1)
        assertTrue("200 帧应已出窗", h.windows.isNotEmpty())

        h.feeder.reset()
        assertEquals(0L, h.feeder.totalFrames)
        assertEquals(0, h.feeder.bufferedFrames)
        assertEquals(0, h.feeder.bufferedRawFrames)
        assertEquals(0L, h.feeder.segmentIndex)
        assertFalse(h.feeder.lastAcceptResetWindow)

        // 重置后必须重新攒满 150 帧才出窗
        h.windows.clear()
        h.emittedAtTotalFrames.clear()
        assertEquals(0, h.feed(0.0, 149, firstId = 500))
        assertTrue(h.windows.isEmpty())
        assertEquals(1, h.accept(149 * 20.0, 649))
        assertEquals(listOf(150L), h.emittedAtTotalFrames)
        assertEquals(150, h.windows.single().size)
    }

    /** 造 50Hz 规整信号 + 收集出窗的测试夹具（值列 0 = 帧 id，其余列 0）。 */
    private class Harness {
        val feeder = SegmentWindowFeeder()
        val windows = ArrayList<Array<DoubleArray>>()
        val emittedAtTotalFrames = ArrayList<Long>()

        /** 送入一个原始采样，返回本次产出的窗口数。 */
        fun accept(timeMs: Double, id: Int): Int {
            val frames = feeder.accept(timeMs, frameValues(id))
            var emitted = 0
            for (frame in frames) {
                val rows = feeder.push(frame)
                if (rows != null) {
                    windows.add(rows)
                    emittedAtTotalFrames.add(feeder.totalFrames)
                    emitted++
                }
            }
            return emitted
        }

        /** 从 [startTimeMs] 起每 20ms 一个原始采样，共 [count] 个。返回本次产出的窗口数。 */
        fun feed(startTimeMs: Double, count: Int, firstId: Int = 0): Int {
            var emitted = 0
            for (k in 0 until count) {
                emitted += accept(startTimeMs + k * 20.0, firstId + k)
            }
            return emitted
        }
    }
}

/** 10 维原始帧，列 0 = id（便于识别帧来源），其余列 0。 */
private fun frameValues(id: Int): DoubleArray = DoubleArray(10) { id.toDouble() }

