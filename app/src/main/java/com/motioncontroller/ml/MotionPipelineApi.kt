package com.motioncontroller.ml

import kotlinx.coroutines.flow.StateFlow

/**
 * 运动类别。
 *
 * 顺序必须与训练侧 LABEL_MAP 完全一致（Motion-ml/dataset/session_dataset.py）：
 * 0=STATIONARY, 1=WALKING, 2=CYCLING, 3=CAR, 4=TRAIN。
 * 不要重排、不要在中间插入新值。
 */
enum class MotionClass(val labelCn: String) {
    STATIONARY("静止"),
    WALKING("步行"),
    CYCLING("骑行"),
    CAR("汽车"),
    TRAIN("火车"),
    ;

    companion object {
        fun of(index: Int): MotionClass? = entries.getOrNull(index)
    }
}

/** 一次窗口推理结果（probs 为 softmax 后的 5 类概率，顺序同 [MotionClass]） */
data class MotionPrediction(
    val probs: FloatArray,
    val classIndex: Int,
    /**
     * 窗口末端的**传感器时间**（`SystemClock.elapsedRealtime` 基准，ms，自 boot）。
     * 2026-10-31 audit：不再用回调墙钟（`System.currentTimeMillis()`），避免排队延迟把窗口
     * "变新"；与 IMU 事件时间轴一致。
     */
    val timestampMs: Long,
    /** 窗口内陀螺仪模长均值（rad/s）；用于「几乎静止」判定，默认 1f = 非静止 */
    val gyroMean: Float = 1f,
    /** 窗口内加速度模长标准差（m/s²）；用于「几乎静止」判定，默认 1f = 非静止 */
    val accStd: Float = 1f,
) {
    val confidence: Float get() = probs.getOrElse(classIndex) { 0f }
    val motionClass: MotionClass? get() = MotionClass.of(classIndex)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MotionPrediction) return false
        return probs.contentEquals(other.probs) &&
            classIndex == other.classIndex &&
            timestampMs == other.timestampMs &&
            gyroMean == other.gyroMean &&
            accStd == other.accStd
    }

    override fun hashCode(): Int {
        var result = probs.contentHashCode()
        result = 31 * result + classIndex
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + gyroMean.hashCode()
        result = 31 * result + accStd.hashCode()
        return result
    }
}

/** 推理管线状态（UI 展示用） */
data class MotionPipelineStatus(
    val running: Boolean = false,
    val modelLoaded: Boolean = false,
    /** scaler.json 是否为真实训练参数；false 时推理结果不可信 */
    val scalerValid: Boolean = false,
    /** 是否有**新鲜**的 GNSS 速度（2s 内）；false 时 gps_speed 特征已按 0 处理 */
    val gpsAvailable: Boolean = false,
    /**
     * 最近一次有效 GNSS 速度（km/h）。只有 [gpsAvailable] 为 true 时才代表实时速度；
     * 过期后该值被保留用于「STALE」展示，**不得当作实时速度渲染**。
     */
    val gpsSpeedKmh: Float = 0f,
    /** 最近一次有效定位的年龄（ms）；从未获得定位为 -1。 */
    val gpsFixAgeMs: Long = -1L,
    /** 本次管线生命周期内是否曾经获得过有效定位（用于区分 UNAVAILABLE 与 STALE）。 */
    val gpsEverFix: Boolean = false,
    /** 陀螺仪是否可用；false 时陀螺相关特征/静止判定退化（2026-10-31 audit L5） */
    val gyroAvailable: Boolean = true,
    /** 旋转矢量（roll/pitch/yaw）是否可用；false 时姿态派生特征退化为 0（audit L5） */
    val rotationAvailable: Boolean = true,
    /** 当前窗口缓冲帧数（0..150） */
    val bufferedFrames: Int = 0,
    val lastInferenceMs: Int = 0,
    val lastError: String? = null,
)

/**
 * 端侧运动识别管线：
 * IMU/GNSS → 50Hz 重采样 → 150 帧滑窗 → 24 维特征 → StandardScaler → ONNX → softmax。
 *
 * 生命周期由 ControllerService 管理；实现内部使用后台线程/协程。
 * start() 之后每完成一个窗口（stride=50 帧，即约 1s）回调一次 [onResult]。
 */
interface MotionPipelineApi {

    val status: StateFlow<MotionPipelineStatus>

    /** 启动传感器采集与推理循环。重复调用应先 stop()。 */
    fun start(onResult: (MotionPrediction) -> Unit)

    fun stop()

    /** 清空窗口/重采样缓冲（例如急停后恢复时） */
    fun reset()
}
