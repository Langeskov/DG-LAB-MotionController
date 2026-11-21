package com.motioncontroller.mapping

import com.motioncontroller.ml.MotionClass
import com.motioncontroller.ml.MotionPrediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人工接管（MANUAL OVERRIDE）的领域层行为：
 *  - 只改变「选择哪个 classBase」，映射公式/安全钳制/slew 完全不变；
 *  - 模型继续推理但不覆盖人工选择；
 *  - 安全机制结束会话（reset）后人工选择不延续。
 */
class MotionIntensityMapperOverrideTest {

    private fun pred(
        index: Int,
        confidence: Float = 1f,
        gyro: Float = 1f,
        accStd: Float = 1f,
    ): MotionPrediction {
        val probs = FloatArray(MotionClass.entries.size) { 0f }
        probs[index] = confidence
        return MotionPrediction(
            probs = probs,
            classIndex = index,
            timestampMs = 0L,
            gyroMean = gyro,
            accStd = accStd,
        )
    }

    private fun mapper() = MotionIntensityMapper(
        MappingConfig(
            classBase = listOf(0f, 10f, 20f, 30f, 40f),
            manualLimit = 40f,
            safetyMax = 60,
            switchWindows = 1,
            slewPerSecond = 20f,
            slewDownPerSecond = 60f,
        ),
    )

    @Test
    fun `人工接管覆盖模型类别但模型仍在推理`() {
        val m = mapper()
        m.onPrediction(pred(MotionClass.CAR.ordinal))
        assertEquals(MotionClass.CAR.ordinal, m.modelStableClassIndex)
        assertEquals(MotionClass.CAR.ordinal, m.effectiveClassIndex)
        assertEquals(30f, m.targetRaw, 1e-3f)

        assertTrue(m.setManualClass(MotionClass.WALKING.ordinal))
        assertEquals(MotionClass.CAR.ordinal, m.modelStableClassIndex) // 模型结果保留
        assertEquals(MotionClass.WALKING.ordinal, m.effectiveClassIndex) // 控制用人工选择
        assertEquals(10f, m.targetRaw, 1e-3f)

        // 模型继续推理（EMA 需要两个窗口才会把最好的类别翻到 TRAIN）也不能覆盖人工选择
        m.onPrediction(pred(MotionClass.TRAIN.ordinal))
        m.onPrediction(pred(MotionClass.TRAIN.ordinal))
        assertEquals(MotionClass.WALKING.ordinal, m.effectiveClassIndex)
        assertEquals(10f, m.targetRaw, 1e-3f)
        assertEquals(MotionClass.TRAIN.ordinal, m.modelStableClassIndex)
    }

    @Test
    fun `人工接管仍然叠加手动偏移并受安全上限钳制`() {
        val m = mapper()
        m.setManualClass(MotionClass.CYCLING.ordinal) // base 20
        m.setManualOffset(40f)
        assertEquals(60f, m.targetRaw, 1e-3f) // 20+40 = 60，正好上限
        m.updateConfig(m.config.copy(safetyMax = 35))
        assertEquals(35f, m.targetRaw, 1e-3f)
    }

    @Test
    fun `人工接管切换不会产生阶跃（仍受 slew 限制）`() {
        val m = mapper()
        m.setManualClass(MotionClass.CYCLING.ordinal)
        repeat(20) { m.step(200) }
        assertEquals(20, m.step(200)) // 已收敛到 20
        m.setManualClass(MotionClass.CAR.ordinal) // target 30
        // 一帧 200ms 最多上升 4（20/s）
        assertEquals(24, m.step(200))
        assertTrue(m.step(200) <= 28)

        // 切回低目标：下降按 slewDown 60/s，200ms 最多降 12
        m.setManualClass(MotionClass.STATIONARY.ordinal) // target 0
        val after = m.step(200)
        assertTrue("下降不得低于 0 也不得瞬时归零: $after", after <= 16 && after >= 0)
    }

    @Test
    fun `非法类别被拒绝且保持原选择`() {
        val m = mapper()
        m.setManualClass(MotionClass.CAR.ordinal)
        assertFalse(m.setManualClass(-1))
        assertFalse(m.setManualClass(MotionClass.entries.size))
        assertEquals(MotionClass.CAR.ordinal, m.manualClassIndex)
    }

    @Test
    fun `归还自动后按模型类别映射`() {
        val m = mapper()
        m.onPrediction(pred(MotionClass.CAR.ordinal))
        m.setManualClass(MotionClass.WALKING.ordinal)
        assertEquals(10f, m.targetRaw, 1e-3f)

        assertTrue(m.setManualClass(null))
        assertNull(m.manualClassIndex)
        assertEquals(MotionClass.CAR.ordinal, m.effectiveClassIndex)
        assertEquals(30f, m.targetRaw, 1e-3f)
    }

    @Test
    fun `安全机制结束会话时清空人工接管`() {
        val m = mapper()
        m.onPrediction(pred(MotionClass.CAR.ordinal))
        m.setManualClass(MotionClass.TRAIN.ordinal)
        m.reset()
        assertNull("reset 后人工选择不得延续到下一次控制会话", m.manualClassIndex)
        assertEquals(-1, m.effectiveClassIndex)
        assertEquals(0f, m.targetRaw, 1e-3f)
    }

    @Test
    fun `forceZero 保留人工选择（arm 时复用同一会话选择）`() {
        val m = mapper()
        m.setManualClass(MotionClass.CYCLING.ordinal)
        m.forceZero()
        assertEquals(MotionClass.CYCLING.ordinal, m.manualClassIndex)
        assertEquals(0f, m.targetRaw, 1e-3f)
    }

    @Test
    fun `REST 矛盾消除只影响模型类别不覆盖人工接管`() {
        val m = mapper()
        m.setManualClass(MotionClass.CAR.ordinal)
        // 几乎静止的窗口 + 模型说 CAR → 模型稳定类别被修正为 STATIONARY
        repeat(2) { m.onPrediction(pred(MotionClass.CAR.ordinal, gyro = 0.001f, accStd = 0.001f)) }
        assertEquals(MotionClass.STATIONARY.ordinal, m.modelStableClassIndex)
        assertTrue(m.restOverrideActive)
        assertEquals("人工接管必须保持 CAR", MotionClass.CAR.ordinal, m.effectiveClassIndex)
        assertEquals(30f, m.targetRaw, 1e-3f)
    }
}
