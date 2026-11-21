package com.motioncontroller.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GnssPolicy`（GNSS 速度/时效策略，纯 Kotlin）单测。
 *
 * 契约来自 2026-10-31 audit #1：与训练 `max_gap_ms=2000 + nan→0` 对齐；年龄用
 * `elapsedRealtimeNanos`；非有限/负速度丢弃；过期立即输出 0。
 */
class GnssPolicyTest {

    @Test
    fun constantsMatchTrainingGapPolicy() {
        assertEquals(2000L, GnssPolicy.MAX_FIX_AGE_MS)
        assertEquals(1000L, GnssPolicy.UPDATE_INTERVAL_MS)
        assertEquals(3.6, GnssPolicy.MPS_TO_KMH, 0.0)
    }

    @Test
    fun sanitizeSpeedRejectsInvalidValues() {
        assertNull("无 speed 字段", GnssPolicy.sanitizeSpeedMps(0f, hasSpeed = false))
        assertNull("NaN", GnssPolicy.sanitizeSpeedMps(Float.NaN, hasSpeed = true))
        assertNull("+Inf", GnssPolicy.sanitizeSpeedMps(Float.POSITIVE_INFINITY, hasSpeed = true))
        assertNull("-Inf", GnssPolicy.sanitizeSpeedMps(Float.NEGATIVE_INFINITY, hasSpeed = true))
        assertNull("负速度", GnssPolicy.sanitizeSpeedMps(-1.5f, hasSpeed = true))

        assertEquals(0.0, GnssPolicy.sanitizeSpeedMps(0f, hasSpeed = true))
        assertEquals(12.5, GnssPolicy.sanitizeSpeedMps(12.5f, hasSpeed = true))
    }

    @Test
    fun stalenessUsesMonotonicAgeAndTwoSecondThreshold() {
        val fix = 10_000_000_000L // 10s（ns，任意基准）
        assertTrue("刚定位时新鲜", GnssPolicy.isFresh(fix, fix + 0))
        assertTrue("恰好 2000ms 仍新鲜", GnssPolicy.isFresh(fix, fix + 2_000L * 1_000_000L))
        assertFalse("2001ms 过期", GnssPolicy.isFresh(fix, fix + 2_001L * 1_000_000L))
        assertFalse("未知定位时间视为过期", GnssPolicy.isFresh(0L, fix))
    }

    @Test
    fun ageMsHandlesClockAnomalies() {
        val fix = 5_000_000_000L
        assertEquals(1500L, GnssPolicy.ageMs(fix, fix + 1_500L * 1_000_000L))
        assertEquals(0L, GnssPolicy.ageMs(fix, fix - 1L)) // now 早于 fix：按 0 处理，不误判失效
        assertEquals(Long.MAX_VALUE, GnssPolicy.ageMs(0L, fix))
    }
}
