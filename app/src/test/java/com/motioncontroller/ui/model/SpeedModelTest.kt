package com.motioncontroller.ui.model

import com.motioncontroller.ml.MotionPipelineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedModelTest {

    @Test
    fun `新鲜 GNSS 数据是 LIVE`() {
        val snapshot = SpeedModel.derive(
            MotionPipelineStatus(
                running = true,
                gpsAvailable = true,
                gpsSpeedKmh = 3.94f,
                gpsFixAgeMs = 400L,
                gpsEverFix = true,
            ),
        )
        assertEquals(SpeedStatus.LIVE, snapshot.status)
        assertTrue(snapshot.isLive)
        assertEquals("3.9", snapshot.primaryText)
        assertEquals("km/h", snapshot.unitText)
    }

    @Test
    fun `过期数据只能标 STALE 且仍标记非实时`() {
        val snapshot = SpeedModel.derive(
            MotionPipelineStatus(
                running = true,
                gpsAvailable = false,
                gpsSpeedKmh = 12.0f,
                gpsFixAgeMs = 2_400L,
                gpsEverFix = true,
            ),
        )
        assertEquals(SpeedStatus.STALE, snapshot.status)
        assertFalse(snapshot.isLive)
        assertEquals("12.0", snapshot.primaryText)
        assertTrue(snapshot.detail.contains("STALE"))
    }

    @Test
    fun `从未获得定位或管线停止时是 UNAVAILABLE 且不显示数值`() {
        val never = SpeedModel.derive(MotionPipelineStatus(running = true, gpsEverFix = false))
        assertEquals(SpeedStatus.UNAVAILABLE, never.status)
        assertEquals("--", never.primaryText)

        val stopped = SpeedModel.derive(
            MotionPipelineStatus(
                running = false,
                gpsAvailable = true,
                gpsSpeedKmh = 5f,
                gpsEverFix = true,
            ),
        )
        assertEquals(SpeedStatus.UNAVAILABLE, stopped.status)
        assertEquals("--", stopped.primaryText)
    }

    @Test
    fun `速度格式化过滤非有限值和负值`() {
        assertEquals("0.0", formatSpeedKmh(Float.NaN))
        assertEquals("0.0", formatSpeedKmh(-3f))
        assertEquals("3.9", formatSpeedKmh(3.94f))
        assertEquals("n/a", formatAgeMs(-1L))
        assertEquals("450ms", formatAgeMs(450L))
        assertEquals("2.4s", formatAgeMs(2_400L))
    }
}
