package com.motioncontroller.sensor

/**
 * GNSS 速度策略（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 与训练侧对齐（`Motion-ml/dataset/session_dataset.py`：GNSS 按 `align_asof` 前向填充，
 * 超过 `max_gap_ms=2000` 的缺口视为缺失 → `NaN` → 特征列填 0）：
 *  - 定位年龄用 `Location.elapsedRealtimeNanos`（自 boot 单调时钟）计算，**不用**接收时刻的
 *    墙钟（墙钟会被 NTP 跳变污染，且 GPS 事件可能延迟投递）；
 *  - 超过 [MAX_FIX_AGE_MS] 没有新定位 → 速度立即按 0 输出，避免"冻结 10s 后突然掉 0"
 *    造成的 `speed_delta` 阶跃（训练里 2s 缺口就已经是 0）；
 *  - 非有限值（NaN/Inf）/负数速度一律丢弃，不参与特征。
 */
object GnssPolicy {

    /** 定位失效阈值（ms）：与训练 `max_gap_ms=2000` 对齐。 */
    const val MAX_FIX_AGE_MS = 2_000L

    /** 请求定位更新间隔（ms）：特征只需速度这一列，1Hz 足够。 */
    const val UPDATE_INTERVAL_MS = 1_000L

    /** m/s → km/h。 */
    const val MPS_TO_KMH = 3.6

    /** 纳秒 / 毫秒。 */
    private const val NANOS_PER_MS = 1_000_000L

    /**
     * 校验一次定位速度。
     *
     * @param speed  `Location.speed`（m/s）
     * @param hasSpeed `Location.hasSpeed()`
     * @return 有限且非负的速度（m/s）；无速度字段 / NaN / Inf / 负数 → null（该定位不更新速度）
     */
    fun sanitizeSpeedMps(speed: Float, hasSpeed: Boolean): Double? {
        if (!hasSpeed) return null
        val v = speed.toDouble()
        if (!v.isFinite()) return null
        if (v < 0.0) return null
        return v
    }

    /** 定位年龄（ms）；[lastFixElapsedNanos] 非正时视为无限旧。 */
    fun ageMs(lastFixElapsedNanos: Long, nowElapsedNanos: Long): Long {
        if (lastFixElapsedNanos <= 0L) return Long.MAX_VALUE
        val age = nowElapsedNanos - lastFixElapsedNanos
        if (age < 0L) return 0L // 时钟异常（不应发生）：按最新处理，避免误判失效
        return age / NANOS_PER_MS
    }

    /** 定位是否仍然新鲜（年龄不超过 [maxAgeMs]）。 */
    fun isFresh(
        lastFixElapsedNanos: Long,
        nowElapsedNanos: Long,
        maxAgeMs: Long = MAX_FIX_AGE_MS,
    ): Boolean = ageMs(lastFixElapsedNanos, nowElapsedNanos) <= maxAgeMs
}
