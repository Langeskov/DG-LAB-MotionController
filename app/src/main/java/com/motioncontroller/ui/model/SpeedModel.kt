package com.motioncontroller.ui.model

import com.motioncontroller.ml.MotionPipelineStatus

/** 速度显示状态。数据过期时**不得**把最后一次有效速度伪装成实时速度。 */
enum class SpeedStatus { LIVE, STALE, UNAVAILABLE }

data class SpeedSnapshot(
    val status: SpeedStatus,
    /** 展示数值（km/h）；STALE 时是最后一次有效值（必须配 STALE 标识）；UNAVAILABLE 时无意义 */
    val displayKmh: Float,
    val detail: String,
) {
    val isLive: Boolean get() = status == SpeedStatus.LIVE
    val hasValue: Boolean get() = status != SpeedStatus.UNAVAILABLE
    /** 主仪表数字；UNAVAILABLE 时 UI 显示 "--" 而不是 0。 */
    val primaryText: String
        get() = if (status == SpeedStatus.UNAVAILABLE) "--" else formatSpeedKmh(displayKmh)

    val unitText: String get() = "km/h"
}

object SpeedModel {

    /** 由管线状态派生速度展示（GNSS 2s 过期策略已在采集层实现，这里只做展示语义）。 */
    fun derive(status: MotionPipelineStatus): SpeedSnapshot {
        val available = status.running && status.gpsAvailable
        return when {
            available -> SpeedSnapshot(
                status = SpeedStatus.LIVE,
                displayKmh = status.gpsSpeedKmh.coerceAtLeast(0f),
                detail = "GNSS LIVE",
            )
            status.running && status.gpsEverFix -> SpeedSnapshot(
                status = SpeedStatus.STALE,
                displayKmh = status.gpsSpeedKmh.coerceAtLeast(0f),
                detail = "STALE FIX",
            )
            else -> SpeedSnapshot(
                status = SpeedStatus.UNAVAILABLE,
                displayKmh = 0f,
                detail = "GNSS UNAVAILABLE",
            )
        }
    }
}

/** 速度显示：一位小数（汽车仪表盘习惯），NaN/负值按 0 处理。 */
fun formatSpeedKmh(kmh: Float): String {
    if (!kmh.isFinite()) return "0.0"
    return "%.1f".format(kmh.coerceAtLeast(0f))
}

/** 数据年龄文案（工程诊断用）。 */
fun formatAgeMs(ageMs: Long): String = when {
    ageMs < 0L -> "n/a"
    ageMs < 1000L -> "${ageMs}ms"
    else -> "%.1fs".format(ageMs / 1000f)
}
