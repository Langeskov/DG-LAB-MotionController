package com.motioncontroller.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * IMU 采集器：注册 `TYPE_ACCELEROMETER` / `TYPE_GYROSCOPE` / `TYPE_ROTATION_VECTOR`，
 * `SENSOR_DELAY_GAME`，以**加速度计事件为主时钟**产出一条 10 维采样。
 *
 * 时间基准：只用 `SensorEvent.timestamp`（ns，自 boot，与
 * `SystemClock.elapsedRealtimeNanos()` 同基准）。**不要**用 `System.currentTimeMillis()`
 * 给样本打时间戳（粗粒度时钟会把多个采样压到同一毫秒）。
 *
 * 线程（2026-10-31 audit）：SensorEventListener 注册在**独立 HandlerThread** 上，
 * 回调（以及下游重采样/入窗，因为 [onSample] 在回调线程同步调用）不占用主线程/UI 线程。
 * [start]/[stop] 建议在主线程调用；stop 会 unregister + quitSafely + join，避免线程泄漏。
 */
class ImuCollector(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "Sensor"

        /** stop() 时等待传感器线程退出的上限（ms）。 */
        private const val THREAD_JOIN_TIMEOUT_MS = 500L
    }

    private val sensorManager: SensorManager? =
        runCatching { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }.getOrNull()

    private val accelerometer: Sensor? =
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }.getOrNull()
    private val gyroscope: Sensor? =
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }.getOrNull()
    private val rotationVector: Sensor? =
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }.getOrNull()

    private val assembler = SensorSampleAssembler()

    /** 每产出一条 IMU 采样（加速度计为主时钟）回调一次；在传感器线程上调用。 */
    @Volatile
    var onSample: ((ImuSample) -> Unit)? = null

    /** 设备是否有加速度计（没有则整条管线不可用）。 */
    val hasAccelerometer: Boolean get() = accelerometer != null

    val hasGyroscope: Boolean get() = gyroscope != null

    val hasRotationVector: Boolean get() = rotationVector != null

    private var registered = false

    /** 传感器回调专用线程；start 创建、stop quit+join。 */
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // 复用数组，避免每个 sensor 事件分配
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** 开始采集。重复调用安全。 */
    fun start() {
        val manager = sensorManager
        if (manager == null) {
            Log.w(TAG, "SensorManager unavailable")
            return
        }
        if (registered) return
        val acc = accelerometer ?: run {
            Log.w(TAG, "no accelerometer, IMU pipeline disabled")
            return
        }
        if (gyroscope == null) Log.w(TAG, "no gyroscope: gyro features degrade to 0")
        if (rotationVector == null) Log.w(TAG, "no rotation vector: roll/pitch/yaw degrade to 0")

        val thread = HandlerThread("IMU-sensor").apply { start() }
        val handler = Handler(thread.looper)
        assembler.reset()

        val accOk = registerOn(manager, acc, handler)
        if (!accOk) {
            handler.removeCallbacksAndMessages(null)
            runCatching { thread.quitSafely() }
            Log.w(TAG, "register accelerometer failed, IMU pipeline disabled")
            return
        }
        gyroscope?.let { registerOn(manager, it, handler) }
        rotationVector?.let { registerOn(manager, it, handler) }

        sensorThread = thread
        sensorHandler = handler
        registered = true
        Log.i(TAG, "IMU started acc=${acc.name} gyro=${gyroscope != null} rot=${rotationVector != null}")
    }

    /** 停止采集。重复调用安全；会等待传感器线程退出。 */
    fun stop() {
        val wasRegistered = registered
        registered = false
        if (wasRegistered) {
            runCatching { sensorManager?.unregisterListener(this) }
        }
        sensorHandler?.removeCallbacksAndMessages(null)
        sensorHandler = null
        val thread = sensorThread
        sensorThread = null
        if (thread != null) {
            runCatching { thread.quitSafely() }
            runCatching { thread.join(THREAD_JOIN_TIMEOUT_MS) }
        }
        // 不在这里 assembler.reset()：若 join 超时回调可能仍在跑，reset 会与回调竞争。
        // [start] 会在注册 listener 之前（新线程、无回调）重置状态。
        if (wasRegistered) Log.i(TAG, "IMU stopped")
    }

    /** 在传感器线程上注册一路 listener；返回是否注册成功。 */
    private fun registerOn(manager: SensorManager, sensor: Sensor, handler: Handler): Boolean =
        runCatching {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        }.onFailure { t ->
            Log.w(TAG, "register ${sensor.name} failed: ${t.message}")
        }.getOrDefault(false)

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val values = event.values
                val sample = assembler.onAccelerometer(event.timestamp, values)
                onSample?.invoke(sample)
            }

            Sensor.TYPE_GYROSCOPE -> assembler.onGyroscope(event.values)

            Sensor.TYPE_ROTATION_VECTOR -> {
                // rotation vector → 旋转矩阵 → 欧拉角（弧度）→ 度
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                assembler.onRotation(roll, pitch, yaw)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不处理精度变化
    }
}
