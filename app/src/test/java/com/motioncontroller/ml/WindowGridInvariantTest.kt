package com.motioncontroller.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 50Hz 网格不变量（2026-10-31 audit HIGH）单测。
 *
 * 150/50/250 对频域步长 10 的整除关系是「最近 250 帧算特征再取最后 150 行」能对齐
 * preprocess.py 的 FFT 起点（绝对帧号 % 10 == 0）的前提；配置错误必须尽早抛异常，
 * 而不是静默掉准确率。
 */
class WindowGridInvariantTest {

    private fun assertRejected(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what 应抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun defaultConfigurationIsGridCompatible() {
        SlidingWindowBuffer.requireGridCompatible()
        // 默认值本身（契约）
        assertEquals(150, SlidingWindowBuffer.DEFAULT_WINDOW_SIZE)
        assertEquals(50, SlidingWindowBuffer.DEFAULT_STRIDE)
        assertEquals(250, SlidingWindowBuffer.DEFAULT_HISTORY_FRAMES)
        assertEquals(10, FeatureExtractor.DEFAULT_SPECTRAL_STRIDE)
        assertEquals(100, FeatureExtractor.DEFAULT_SPECTRAL_WINDOW)
    }

    @Test
    fun rejectsMisalignedWindowConfiguration() {
        assertRejected("stride=51（不是 10 的整数倍）") {
            SlidingWindowBuffer.requireGridCompatible(stride = 51)
        }
        assertRejected("historyFrames=251（不是 10 的整数倍）") {
            SlidingWindowBuffer.requireGridCompatible(historyFrames = 251)
        }
        assertRejected("windowSize=155（不是 10 的整数倍）") {
            SlidingWindowBuffer.requireGridCompatible(windowSize = 155)
        }
        assertRejected("windowSize=90（小于 spectralWindow=100）") {
            SlidingWindowBuffer.requireGridCompatible(windowSize = 90)
        }
        assertRejected("historyFrames=100（小于 windowSize）") {
            SlidingWindowBuffer.requireGridCompatible(historyFrames = 100)
        }
        assertRejected("windowSize=0") {
            SlidingWindowBuffer.requireGridCompatible(windowSize = 0)
        }
    }

    @Test
    fun acceptsOtherAlignedConfigurations() {
        // 只要满足整除关系，其它对齐组合也应合法（为将来改窗口留余地）
        SlidingWindowBuffer.requireGridCompatible(
            windowSize = 200,
            stride = 40,
            historyFrames = 300,
            spectralStride = 10,
            spectralWindow = 100,
        )
    }
}
