package com.motioncontroller.ui.model

import com.motioncontroller.dglab.DglabDevice
import com.motioncontroller.dglab.DglabState
import com.motioncontroller.mapping.MappingConfig
import com.motioncontroller.ml.MotionClass
import com.motioncontroller.service.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputModelTest {

    private val mapping = MappingConfig(
        classBase = listOf(0f, 10f, 20f, 30f, 40f),
        manualLimit = 40f,
        safetyMax = 60,
    )

    private fun linked(intensityA: Int? = 22, estimateA: Int = 18, targetA: Int = 25): DglabState =
        DglabState(
            relayRunning = true,
            appClientIds = listOf("client-1"),
            devices = listOf(DglabDevice(slotId = "slot-1", name = "coyote", type = "COYOTE_030")),
            activeSlotId = "slot-1",
            targetIntensityA = targetA,
            estimateIntensityA = estimateA,
            reportedIntensityA = intensityA,
        )

    @Test
    fun `AUTO 模式展示 base offset 计算值与钳制值`() {
        val state = UiState(
            serviceRunning = true,
            armed = true,
            mapping = mapping,
            manualOffset = 12f,
            stableClass = MotionClass.CYCLING.ordinal,
            effectiveClass = MotionClass.CYCLING.ordinal,
            targetIntensity = 30,
            link = linked(),
        )
        val out = OutputModel.derive(state)
        assertEquals(MappingSource.AUTO, out.source)
        assertEquals("CYCLING", out.classCode)
        assertEquals(20, out.baseIntensity)
        assertEquals(12, out.manualOffset)
        assertEquals(32, out.computedTarget)
        assertEquals(32, out.clampedTarget) // 32 < safetyMax 60
        assertEquals(30, out.appliedIntensity)
        assertEquals(25, out.sentTargetA)
        assertEquals(18, out.sentEstimateA)
        assertEquals(22, out.appReportedA)
    }

    @Test
    fun `人工接管时映射来源与类别来自用户选择`() {
        val state = UiState(
            serviceRunning = true,
            armed = true,
            mapping = mapping,
            manualOffset = 40f,
            stableClass = MotionClass.CAR.ordinal,
            effectiveClass = MotionClass.TRAIN.ordinal,
            manualClass = MotionClass.TRAIN.ordinal,
            targetIntensity = 60,
            link = linked(intensityA = null),
        )
        val out = OutputModel.derive(state)
        assertEquals(MappingSource.MANUAL, out.source)
        assertEquals("TRAIN", out.classCode)
        assertEquals(40, out.baseIntensity)
        assertEquals(80, out.computedTarget)
        assertEquals(60, out.clampedTarget) // safetyMax 钳制
        assertNull(out.appReportedA)
        assertTrue(out.outputActive)
    }

    @Test
    fun `负偏移的计算值可以为负但钳制目标不会`() {
        val state = UiState(
            serviceRunning = true,
            armed = true,
            mapping = mapping.copy(classBase = listOf(0f, 10f, 20f, 30f, 40f)),
            manualOffset = -15f,
            stableClass = MotionClass.WALKING.ordinal,
            effectiveClass = MotionClass.WALKING.ordinal,
            targetIntensity = 0,
            link = linked(),
        )
        val out = OutputModel.derive(state)
        assertEquals(10, out.baseIntensity)
        assertEquals(-15, out.manualOffset)
        assertEquals(-5, out.computedTarget)
        assertEquals(0, out.clampedTarget)
    }

    @Test
    fun `无识别结果时 base 与目标都是 0 且来源仍明确`() {
        val state = UiState(mapping = mapping, effectiveClass = -1)
        val out = OutputModel.derive(state)
        assertEquals(MappingSource.AUTO, out.source)
        assertEquals(0, out.baseIntensity)
        assertEquals(0, out.computedTarget)
        assertEquals(0, out.clampedTarget)
        assertEquals("—", out.classCode)
    }
}
