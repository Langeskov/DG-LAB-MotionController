package com.motioncontroller.mapping

import com.motioncontroller.ml.MotionClass
import com.motioncontroller.ml.MotionPrediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionIntensityMapperTest {

    private fun pred(
        index: Int,
        confidence: Float = 1f,
        gyro: Float = 1f,
        accStd: Float = 1f,
    ): MotionPrediction {
        val probs = FloatArray(MotionClass.entries.size) { 0f }
        probs[index] = confidence
        val norm = probs.sum().coerceAtLeast(1e-6f)
        for (i in probs.indices) probs[i] /= norm
        return MotionPrediction(
            probs = probs,
            classIndex = index,
            timestampMs = 0L,
            gyroMean = gyro,
            accStd = accStd,
        )
    }

    @Test
    fun `需要连续两个窗口才切换稳定类别`() {
        val mapper = MotionIntensityMapper(MappingConfig(switchWindows = 2))
        assertEquals(-1, mapper.onPrediction(pred(MotionClass.CAR.ordinal)))
        assertEquals(-1, mapper.stableClassIndex)
        assertEquals(MotionClass.CAR.ordinal, mapper.onPrediction(pred(MotionClass.CAR.ordinal)))
        assertEquals(MotionClass.CAR.ordinal, mapper.stableClassIndex)
    }

    @Test
    fun `类别基准与手动偏移叠加并钳制安全上限`() {
        val mapper = MotionIntensityMapper(
            MappingConfig(classBase = listOf(0f, 10f, 20f, 30f, 40f), safetyMax = 60, switchWindows = 1),
        )
        mapper.onPrediction(pred(MotionClass.CYCLING.ordinal))
        assertEquals(20f, mapper.targetRaw, 1e-3f)

        mapper.setManualOffset(10f)
        assertEquals(30f, mapper.targetRaw, 1e-3f)

        mapper.setManualOffset(40f) // manualLimit 默认 40
        assertEquals(40f, mapper.manualOffset, 1e-3f)
        // 30 + 40 = 70，但 safetyMax=60 会钳制
        assertEquals(60f, mapper.targetRaw, 1e-3f)

        mapper.updateConfig(mapper.config.copy(safetyMax = 35))
        assertEquals(35f, mapper.targetRaw, 1e-3f)
    }

    @Test
    fun `强度按 slew 速率逼近目标`() {
        val mapper = MotionIntensityMapper(
            MappingConfig(classBase = listOf(0f, 10f, 20f, 30f, 40f), safetyMax = 60, switchWindows = 1, slewPerSecond = 20f),
        )
        mapper.onPrediction(pred(MotionClass.CAR.ordinal)) // target 30
        // 200ms 最多 4
        assertEquals(4, mapper.step(200))
        assertEquals(8, mapper.step(200))
        // 足够长时间后到达 30
        var last = 0
        repeat(50) { last = mapper.step(200) }
        assertEquals(30, last)
    }

    @Test
    fun `reset 后归零且不再输出强度`() {
        val mapper = MotionIntensityMapper(MappingConfig(switchWindows = 1))
        mapper.onPrediction(pred(MotionClass.TRAIN.ordinal))
        assertTrue(mapper.targetRaw > 0f)
        mapper.reset()
        assertEquals(-1, mapper.stableClassIndex)
        assertEquals(0f, mapper.targetRaw, 1e-3f)
        assertEquals(0, mapper.step(1000))
    }

    @Test
    fun `类别序号与训练标签顺序一致`() {
        assertEquals(
            listOf("STATIONARY", "WALKING", "CYCLING", "CAR", "TRAIN"),
            MotionClass.entries.map { it.name },
        )
    }

    @Test
    fun `几乎静止时 CAR 被矛盾消除为 STATIONARY`() {
        val mapper = MotionIntensityMapper(MappingConfig())
        val quietCar = pred(MotionClass.CAR.ordinal, gyro = 0.001f, accStd = 0.005f)
        mapper.onPrediction(quietCar)
        // 第一个安静窗口：还没达到 restWindows，仍未稳定
        assertEquals(-1, mapper.stableClassIndex)
        mapper.onPrediction(quietCar)
        assertEquals(MotionClass.STATIONARY.ordinal, mapper.stableClassIndex)
        assertTrue(mapper.restOverrideActive)
        assertEquals(0f, mapper.targetRaw, 1e-3f)
    }

    @Test
    fun `安静窗口的 TRAIN 不会被矛盾消除`() {
        val mapper = MotionIntensityMapper(MappingConfig())
        val quietTrain = pred(MotionClass.TRAIN.ordinal, gyro = 0.001f, accStd = 0.03f)
        mapper.onPrediction(quietTrain)
        mapper.onPrediction(quietTrain)
        // 火车车厢本身和静止一样安静，不能靠能量否定
        assertEquals(MotionClass.TRAIN.ordinal, mapper.stableClassIndex)
        assertTrue(!mapper.restOverrideActive)
    }

    @Test
    fun `真正运动的 CAR 不会被矛盾消除`() {
        val mapper = MotionIntensityMapper(MappingConfig())
        val movingCar = pred(MotionClass.CAR.ordinal, gyro = 0.08f, accStd = 0.2f)
        mapper.onPrediction(movingCar)
        mapper.onPrediction(movingCar)
        assertEquals(MotionClass.CAR.ordinal, mapper.stableClassIndex)
        assertTrue(!mapper.restOverrideActive)
    }

    @Test
    fun `下降与归零按 slewDownPerSecond 更快`() {
        val mapper = MotionIntensityMapper(
            MappingConfig(
                classBase = listOf(0f, 10f, 20f, 30f, 40f),
                safetyMax = 60,
                switchWindows = 1,
                slewPerSecond = 20f,
                slewDownPerSecond = 60f,
            ),
        )
        mapper.onPrediction(pred(MotionClass.CAR.ordinal)) // target 30
        var last = 0
        repeat(20) { last = mapper.step(200) }
        assertEquals(30, last)

        // EMA 需要 2 个窗口才把 STATIONARY 推到最大概率
        mapper.onPrediction(pred(MotionClass.STATIONARY.ordinal))
        mapper.onPrediction(pred(MotionClass.STATIONARY.ordinal))
        assertEquals(MotionClass.STATIONARY.ordinal, mapper.stableClassIndex)
        assertEquals(0f, mapper.targetRaw, 1e-3f)
        // 下降 60/s：200ms 最多降 12 → 30 → 18
        assertEquals(18, mapper.step(200))
        assertEquals(6, mapper.step(200))
        assertEquals(0, mapper.step(200))
    }

    @Test
    fun `dt 被钳制在 0 到 200ms 之间`() {
        val mapper = MotionIntensityMapper(
            MappingConfig(switchWindows = 1, slewPerSecond = 20f, slewDownPerSecond = 60f),
        )
        mapper.onPrediction(pred(MotionClass.CAR.ordinal)) // target 30
        // 10s 的 dt 会被钳制到 200ms：上升 20/s → 最多 +4
        assertEquals(4, mapper.step(10_000))
        // 负数 dt（时钟回拨等异常）不产生任何变化
        assertEquals(4, mapper.step(-500))
        assertEquals(8, mapper.step(200))
    }

    @Test
    fun `默认下降速度 60 且与上升速度独立`() {
        assertEquals(60f, MappingConfig.Default.slewDownPerSecond, 0f)
        assertEquals(20f, MappingConfig.Default.slewPerSecond, 0f)
    }

    @Test
    fun `updateConfig 会清洗非法配置`() {
        val mapper = MotionIntensityMapper()
        mapper.updateConfig(
            MappingConfig(
                classBase = listOf(1f, 2f), // 维度错误 → 默认
                manualLimit = -5f, // → 0
                safetyMax = 9999, // → 200
                switchWindows = 0, // → 1
                probEma = Float.NaN, // → 默认
                slewPerSecond = -10f, // → 最小 1
                slewDownPerSecond = Float.POSITIVE_INFINITY, // → 默认 60
                restWindows = 0, // → 1
                restOverrideClasses = setOf(99), // 非法类别 → 空
            ),
        )
        val cfg = mapper.config
        assertEquals(MappingConfig.Default.classBase, cfg.classBase)
        assertEquals(0f, cfg.manualLimit, 1e-3f)
        assertEquals(200, cfg.safetyMax)
        assertEquals(1, cfg.switchWindows)
        assertEquals(MappingConfig.Default.probEma, cfg.probEma, 1e-3f)
        assertEquals(1f, cfg.slewPerSecond, 1e-3f)
        assertEquals(60f, cfg.slewDownPerSecond, 1e-3f)
        assertEquals(1, cfg.restWindows)
        assertTrue(cfg.restOverrideClasses.isEmpty())
    }
}
