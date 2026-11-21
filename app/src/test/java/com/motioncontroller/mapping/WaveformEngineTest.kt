package com.motioncontroller.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformEngineTest {

    private fun engine(config: WaveConfig, startAtMs: Long = 1_000L): WaveformEngine =
        WaveformEngine().apply {
            setConfig(config)
            if (config.enabled) beginSession(startAtMs)
        }

    // ───────────────────────── 包络数学 ─────────────────────────

    @Test
    fun `深度为 0 或 FLAT 时包络恒 1（不调制）`() {
        for (shape in WaveShape.entries) {
            for (phase in listOf(0f, 0.25f, 0.5f, 0.75f)) {
                assertEquals(1f, WaveformMath.gainOf(shape, phase, 0), 1e-6f)
            }
        }
        for (phase in listOf(0f, 0.25f, 0.5f, 0.75f)) {
            assertEquals(1f, WaveformMath.gainOf(WaveShape.FLAT, phase, 100), 1e-6f)
        }
    }

    @Test
    fun `深度 100 时包络落在 0 到 1 且方波有明确通断`() {
        assertEquals(1f, WaveformMath.gainOf(WaveShape.SQUARE, 0.1f, 100), 1e-6f)
        assertEquals(0f, WaveformMath.gainOf(WaveShape.SQUARE, 0.6f, 100), 1e-6f)
        // 锯齿起点 0、终点接近 1
        assertEquals(0f, WaveformMath.gainOf(WaveShape.SAWTOOTH, 0f, 100), 1e-6f)
        assertTrue(WaveformMath.gainOf(WaveShape.SAWTOOTH, 0.99f, 100) > 0.98f)
        // 正弦中点 0.25 相位为峰值附近
        assertTrue(WaveformMath.gainOf(WaveShape.SINE, 0.25f, 100) > 0.98f)
        assertTrue(WaveformMath.gainOf(WaveShape.SINE, 0.75f, 100) < 0.02f)
    }

    @Test
    fun `深度 50 时包络只向下调制到 base 的 50`() {
        assertEquals(0.5f, WaveformMath.gainOf(WaveShape.SQUARE, 0.6f, 50), 1e-6f)
        assertEquals(1f, WaveformMath.gainOf(WaveShape.SQUARE, 0.1f, 50), 1e-6f)
        // 阵列：前 35% 通、其余断
        assertEquals(1f, WaveformMath.gainOf(WaveShape.BURST, 0.2f, 50), 1e-6f)
        assertEquals(0.5f, WaveformMath.gainOf(WaveShape.BURST, 0.5f, 50), 1e-6f)
    }

    @Test
    fun `相位越界与非法值被规整而不是产生 NaN`() {
        assertEquals(
            WaveformMath.gainOf(WaveShape.SINE, 0.25f, 80),
            WaveformMath.gainOf(WaveShape.SINE, 1.25f, 80),
            1e-6f,
        )
        assertEquals(
            WaveformMath.gainOf(WaveShape.SINE, 0.25f, 80),
            WaveformMath.gainOf(WaveShape.SINE, -0.75f, 80),
            1e-6f,
        )
        val gain = WaveformMath.gainOf(WaveShape.SINE, Float.NaN, 80)
        assertTrue(gain in 0f..1f)
    }

    // ───────────────────────── 配置净化 ─────────────────────────

    @Test
    fun `配置越界被钳制 非有限频率回退默认`() {
        val safe = WaveConfig(
            enabled = true,
            frequencyHz = 99f,
            depthPercent = 500,
            durationSec = -7,
        ).sanitized()
        assertEquals(WaveConfig.MAX_FREQUENCY_HZ, safe.frequencyHz, 1e-6f)
        assertEquals(100, safe.depthPercent)
        assertEquals(0, safe.durationSec)

        val nan = WaveConfig(frequencyHz = Float.NaN).sanitized()
        assertEquals(WaveConfig.DEFAULT_FREQUENCY_HZ, nan.frequencyHz, 1e-6f)
    }

    // ───────────────────────── 会话 / 调制 ─────────────────────────

    @Test
    fun `未启用时输出原样 无调制`() {
        val e = engine(WaveConfig(enabled = false))
        val tick = e.tick(40, 2_000L)
        assertEquals(40, tick.output)
        assertEquals(1f, tick.gain, 1e-6f)
        assertFalse(tick.active)
    }

    @Test
    fun `调制输出只向下并四舍五入到整数`() {
        // 方波 depth=50：前半周期输出 base，后半周期输出 base/2
        val e = engine(
            WaveConfig(enabled = true, shape = WaveShape.SQUARE, frequencyHz = 1f, depthPercent = 50),
            startAtMs = 1_000L,
        )
        assertEquals(40, e.tick(40, 1_100L).output) // 相位 0.1 → 通
        assertEquals(20, e.tick(40, 1_600L).output) // 相位 0.6 → 断（50%）
        val tick = e.tick(41, 1_600L)
        assertEquals(21, tick.output) // 41 × 0.5 + 0.5 → 21
    }

    @Test
    fun `持续时间到期返回 expired 一次并自动结束会话`() {
        val e = engine(
            WaveConfig(enabled = true, durationSec = 10),
            startAtMs = 1_000L,
        )
        val mid = e.tick(30, 5_000L)
        assertFalse(mid.expired)
        assertTrue(mid.active)
        assertEquals(6, e.remainingSec(5_000L))

        val end = e.tick(30, 11_000L)
        assertTrue(end.expired)
        assertFalse(end.active)
        assertFalse(e.isSessionActive)
        // 到期后输出恢复原样
        assertEquals(30, e.tick(30, 11_100L).output)
    }

    @Test
    fun `持续时间 0 表示无限 不会到期`() {
        val e = engine(
            WaveConfig(enabled = true, durationSec = 0),
            startAtMs = 1_000L,
        )
        val tick = e.tick(30, 999_000L)
        assertFalse(tick.expired)
        assertTrue(tick.active)
        assertEquals(0, e.remainingSec(999_000L))
    }

    @Test
    fun `关闭开关立即结束会话 重新启用由 tick 自动开始`() {
        val e = engine(
            WaveConfig(enabled = true, shape = WaveShape.SQUARE, frequencyHz = 1f, depthPercent = 100),
            startAtMs = 1_000L,
        )
        assertTrue(e.isSessionActive)
        e.setConfig(e.config.copy(enabled = false))
        assertFalse(e.isSessionActive)

        e.setConfig(e.config.copy(enabled = true))
        assertFalse(e.isSessionActive) // 未自动开始，等待控制循环 tick
        val tick = e.tick(50, 50_000L) // tick 自动开启会话（计时从 50_000 起）
        assertTrue(tick.active)
        assertEquals(50, tick.output) // 相位 0 → 方波通
        assertEquals(0, e.tick(50, 50_500L).output) // 相位 0.5 → 断
    }

    @Test
    fun `负输入被钳制为 0 不产生负输出`() {
        val e = engine(
            WaveConfig(enabled = true, shape = WaveShape.SQUARE, frequencyHz = 1f, depthPercent = 100),
            startAtMs = 1_000L,
        )
        assertEquals(0, e.tick(-5, 1_100L).output)
    }

    @Test
    fun `到期锁定后 tick 不自动重开 重新启用开关或 beginSession 才解锁`() {
        val e = engine(
            WaveConfig(enabled = true, durationSec = 5),
            startAtMs = 1_000L,
        )
        assertTrue(e.tick(30, 6_000L).expired)
        // 锁定：tick 不自动重开会话（防止到期瞬间又跑一轮）
        val after = e.tick(30, 7_000L)
        assertFalse(after.active)
        assertFalse(after.expired)
        assertFalse(e.isSessionActive)

        // 解锁路径 1：重新启用开关
        e.setConfig(e.config.copy(enabled = false))
        e.setConfig(e.config.copy(enabled = true))
        assertTrue(e.tick(30, 8_000L).active)

        // 解锁路径 2：显式 beginSession（重新 START）
        e.tick(30, 20_000L).expired // 5s 后再次到期锁定
        assertFalse(e.isSessionActive)
        e.beginSession(30_000L)
        assertTrue(e.isSessionActive)
        assertTrue(e.tick(30, 30_100L).active)
    }

    @Test
    fun `endSession 后剩余时间归零 会话重开后计时重新开始`() {
        val e = engine(
            WaveConfig(enabled = true, durationSec = 10),
            startAtMs = 1_000L,
        )
        assertEquals(8, e.remainingSec(3_000L))
        e.endSession()
        assertEquals(0, e.remainingSec(3_000L))
        // 重新 START（beginSession）→ 计时从头再来（duration 是每段 START 会话的时长，审计 S1/S5）
        e.beginSession(50_000L)
        assertEquals(10, e.remainingSec(50_000L))
    }
}
