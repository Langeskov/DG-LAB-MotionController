package com.motioncontroller.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SlidingWindowBuffer`（推理切窗计数，纯逻辑）单测。
 *
 * 契约（AGENTS.md 4.1 / MotionPipelineApi）：
 *  - 窗口 150 帧、步长 50 帧；
 *  - 缓冲不足 150 帧时不产出窗口，`bufferedFrames` 递增到 150；
 *  - 首次产窗在累计 150 帧，之后每 +50 帧一次；
 *  - 返回的行数 = `min(缓冲帧数, historyFrames)`，末尾是最新帧。
 */
class SlidingWindowBufferTest {

    private fun frameId(i: Int) = doubleArrayOf(0.0, i.toDouble(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    @Test
    fun noWindowUntilWarmUp() {
        val buffer = SlidingWindowBuffer()
        for (i in 0 until 149) {
            assertNull("第 ${i + 1} 帧不应产窗", buffer.push(frameId(i)))
            assertEquals(i + 1, buffer.bufferedFrames)
            assertEquals(i + 1, buffer.bufferedRawFrames)
        }
        assertTrue(!buffer.hasFullWindow)
        assertEquals(150, SlidingWindowBuffer.DEFAULT_WINDOW_SIZE)
        assertEquals(50, SlidingWindowBuffer.DEFAULT_STRIDE)
        assertEquals(250, SlidingWindowBuffer.DEFAULT_HISTORY_FRAMES)
    }

    @Test
    fun firstWindowAt150ThenEvery50() {
        val buffer = SlidingWindowBuffer()
        val emittedAt = ArrayList<Long>()
        var lastWindow: Array<DoubleArray>? = null
        for (i in 1..1000) {
            val out = buffer.push(frameId(i))
            if (out != null) {
                emittedAt.add(buffer.totalFrames)
                lastWindow = out
                assertEquals("产窗后计数应清零", 0, buffer.framesSinceLastWindow)
            }
        }
        // 150, 200, 250, ..., 1000
        val expected = ArrayList<Long>()
        var f = 150L
        while (f <= 1000L) {
            expected.add(f)
            f += 50
        }
        assertEquals(expected, emittedAt)

        // 窗口内容：末尾是最新帧
        assertNotNull(lastWindow)
        val win = lastWindow!!
        assertEquals(250, win.size)
        assertEquals(1000.0, win.last()[1], 0.0)
        assertEquals(751.0, win.first()[1], 0.0) // 最近 250 帧 = 751..1000
        assertEquals(150, buffer.bufferedFrames) // 对外展示封顶 150
        assertEquals(250, buffer.bufferedRawFrames)
    }

    @Test
    fun windowRowsGrowUntilHistoryCap() {
        val buffer = SlidingWindowBuffer(windowSize = 10, stride = 5, historyFrames = 20)
        val sizes = ArrayList<Int>()
        for (i in 1..60) {
            val out = buffer.push(frameId(i)) ?: continue
            sizes.add(out.size)
        }
        // 首次产窗（第 10 帧）只有 10 行，之后增长到 history cap 20
        assertEquals(listOf(10, 15, 20, 20, 20, 20, 20, 20, 20, 20, 20), sizes)
    }

    @Test
    fun resetClearsEverything() {
        val buffer = SlidingWindowBuffer()
        for (i in 0 until 200) buffer.push(frameId(i))
        assertEquals(250 - 50, buffer.bufferedRawFrames) // 200 帧全部保留（< cap）
        buffer.reset()
        assertEquals(0, buffer.bufferedFrames)
        assertEquals(0, buffer.bufferedRawFrames)
        assertEquals(0L, buffer.totalFrames)
        assertEquals(0, buffer.framesSinceLastWindow)
        assertTrue(!buffer.hasFullWindow)
        // 重置后需要重新攒满 150 帧
        var emitted = 0
        for (i in 0 until 149) if (buffer.push(frameId(i)) != null) emitted++
        assertEquals(0, emitted)
        assertNotNull(buffer.push(frameId(149)))
    }

    @Test
    fun inputRowsAreCopied() {
        val buffer = SlidingWindowBuffer(windowSize = 1, stride = 1, historyFrames = 1)
        val mutable = frameId(7)
        val out = buffer.push(mutable)
        mutable[1] = 999.0
        assertNotNull(out)
        assertEquals(7.0, out!!.last()[1], 0.0)
    }

    @Test
    fun rejectsInvalidConfiguration() {
        try {
            SlidingWindowBuffer(windowSize = 0)
            throw AssertionError("windowSize=0 应抛异常")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        try {
            SlidingWindowBuffer(windowSize = 150, stride = 0)
            throw AssertionError("stride=0 应抛异常")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        try {
            SlidingWindowBuffer(windowSize = 150, stride = 50, historyFrames = 100)
            throw AssertionError("historyFrames < windowSize 应抛异常")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
