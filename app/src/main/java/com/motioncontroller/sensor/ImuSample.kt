package com.motioncontroller.sensor

/**
 * 一个 IMU 采样点：以**加速度计事件**为主时钟触发，
 * 陀螺仪 / 旋转矢量按「零阶保持」对齐到同一时刻。
 *
 * @property eventTimeNanos 该采样点自带的单调时钟时间（`SensorEvent.timestamp`，ns，自 boot）
 */
data class ImuSample(
    val eventTimeNanos: Long,
    val accX: Float,
    val accY: Float,
    val accZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val roll: Float,
    val pitch: Float,
    val yaw: Float,
) {
    /**
     * 转成管线使用的 10 维原始特征：[gps_speed(km/h), acc3, gyro3, roll, pitch, yaw]
     * （顺序见 AGENTS.md 4.1）。
     */
    fun toRawValues(speedKmh: Double): DoubleArray = doubleArrayOf(
        speedKmh,
        accX.toDouble(), accY.toDouble(), accZ.toDouble(),
        gyroX.toDouble(), gyroY.toDouble(), gyroZ.toDouble(),
        roll.toDouble(), pitch.toDouble(), yaw.toDouble(),
    )
}

/**
 * 把三路传感器事件合并成一条 IMU 采样序列（纯 Kotlin，可 JVM 单测）。
 *
 * 只在加速度计事件上产出一个采样；陀螺仪/旋转矢量零阶保持到该时刻。
 * 数据尚未到达时（例如刚启动、设备没有旋转矢量传感器）对应通道保持 0。
 */
class SensorSampleAssembler {

    private val gyroValues = DoubleArray(3)
    private val rotationValues = DoubleArray(3)

    /** 记录陀螺仪事件（不产生采样点）。 */
    fun onGyroscope(values: FloatArray) {
        if (values.size < 3) return
        gyroValues[0] = values[0].toDouble()
        gyroValues[1] = values[1].toDouble()
        gyroValues[2] = values[2].toDouble()
    }

    /** 记录旋转矢量换算后的欧拉角（度）。 */
    fun onRotation(roll: Float, pitch: Float, yaw: Float) {
        rotationValues[0] = roll.toDouble()
        rotationValues[1] = pitch.toDouble()
        rotationValues[2] = yaw.toDouble()
    }

    /** 记录加速度计事件并产出一个采样点。 */
    fun onAccelerometer(eventTimeNanos: Long, values: FloatArray): ImuSample {
        val ax = if (values.isNotEmpty()) values[0] else 0f
        val ay = if (values.size > 1) values[1] else 0f
        val az = if (values.size > 2) values[2] else 0f
        return ImuSample(
            eventTimeNanos = eventTimeNanos,
            accX = ax,
            accY = ay,
            accZ = az,
            gyroX = gyroValues[0].toFloat(),
            gyroY = gyroValues[1].toFloat(),
            gyroZ = gyroValues[2].toFloat(),
            roll = rotationValues[0].toFloat(),
            pitch = rotationValues[1].toFloat(),
            yaw = rotationValues[2].toFloat(),
        )
    }

    /** 清空零阶保持缓存。 */
    fun reset() {
        gyroValues.fill(0.0)
        rotationValues.fill(0.0)
    }
}
