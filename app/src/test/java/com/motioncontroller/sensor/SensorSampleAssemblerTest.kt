package com.motioncontroller.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

/** `SensorSampleAssembler`（加速度计主时钟 + 陀螺仪/姿态零阶保持）单测。 */
class SensorSampleAssemblerTest {

    @Test
    fun accelerometerProducesSampleWithZeroOrderHold() {
        val assembler = SensorSampleAssembler()
        // 陀螺仪/姿态先到
        assembler.onGyroscope(floatArrayOf(0.1f, 0.2f, 0.3f))
        assembler.onRotation(roll = 10f, pitch = -5f, yaw = 90f)

        val sample = assembler.onAccelerometer(123_456_789L, floatArrayOf(1f, 2f, 9.8f))
        assertEquals(123_456_789L, sample.eventTimeNanos)
        assertEquals(1f, sample.accX, 0f)
        assertEquals(2f, sample.accY, 0f)
        assertEquals(9.8f, sample.accZ, 0f)
        assertEquals(0.1f, sample.gyroX, 1e-6f)
        assertEquals(0.2f, sample.gyroY, 1e-6f)
        assertEquals(0.3f, sample.gyroZ, 1e-6f)
        assertEquals(10f, sample.roll, 1e-6f)
        assertEquals(-5f, sample.pitch, 1e-6f)
        assertEquals(90f, sample.yaw, 1e-6f)
    }

    @Test
    fun laterGyroscopeEventIsHeldForNextSample() {
        val assembler = SensorSampleAssembler()
        val first = assembler.onAccelerometer(1L, floatArrayOf(0f, 0f, 9.8f))
        assertEquals(0f, first.gyroX, 0f) // 还没有陀螺仪数据 → 0

        assembler.onGyroscope(floatArrayOf(1f, 2f, 3f))
        val second = assembler.onAccelerometer(2L, floatArrayOf(0f, 0f, 9.8f))
        assertEquals(1f, second.gyroX, 0f)
        assertEquals(3f, second.gyroZ, 0f)
    }

    @Test
    fun resetClearsHeldValues() {
        val assembler = SensorSampleAssembler()
        assembler.onGyroscope(floatArrayOf(1f, 2f, 3f))
        assembler.onRotation(1f, 2f, 3f)
        assembler.reset()
        val sample = assembler.onAccelerometer(1L, floatArrayOf(0f, 0f, 9.8f))
        assertEquals(0f, sample.gyroX, 0f)
        assertEquals(0f, sample.roll, 0f)
    }

    @Test
    fun rawValuesFollowContractOrder() {
        val sample = ImuSample(
            eventTimeNanos = 1L,
            accX = 1f, accY = 2f, accZ = 3f,
            gyroX = 4f, gyroY = 5f, gyroZ = 6f,
            roll = 7f, pitch = 8f, yaw = 9f,
        )
        val values = sample.toRawValues(speedKmh = 36.0)
        assertEquals(
            listOf(36.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0),
            values.toList(),
        )
        assertEquals(10, values.size)
    }
}
