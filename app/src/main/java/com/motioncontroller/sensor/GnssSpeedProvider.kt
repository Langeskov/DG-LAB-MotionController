package com.motioncontroller.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * GNSS 速度快照（UI/管线共用）。纯数据，无 Android 依赖，便于 JVM 测试展示逻辑。
 *
 * @param speedKmh [available] 为 true 时是实时速度；否则是最近一次有效速度（仅供 STALE 展示）。
 * @param fixAgeMs 最近一次有效定位的年龄；-1 表示本次生命周期内从未获得定位。
 * @param everFix 是否曾经获得过有效定位（区分 UNAVAILABLE / STALE）。
 */
data class GnssSpeedSnapshot(
    val speedKmh: Double = 0.0,
    val fixAgeMs: Long = -1L,
    val available: Boolean = false,
    val everFix: Boolean = false,
)

/**
 * GNSS 速度提供者：`LocationManager.GPS_PROVIDER` + `requestLocationUpdates`。
 *
 * 行为约定（AGENTS.md 4.3 / 第 8 节 + 2026-10-31 audit）：
 *  - 输出 m/s 速度；无权限 / 无信号 / 定位过期 → `available=false, speed=0`；
 *  - **过期判定与训练对齐**（[GnssPolicy.MAX_FIX_AGE_MS] = 2s，训练 `max_gap_ms=2000`）：
 *    定位年龄用 `Location.elapsedRealtimeNanos` 计算，超时后速度立即按 0 输出（旧实现冻结 10s
 *    再掉 0，会制造 `speed_delta` 阶跃）；非有限/负速度直接丢弃；
 *  - `gpsAvailable` 会随过期**自动**变 false：50Hz 采样路径按需检查（[speedKmh]/[isAvailable]）
 *    + 定位线程上的 500ms 看门狗，不依赖 provider disable/stop；
 *  - 回调注册在**独立 HandlerThread**（不占主线程，重采样/入窗也随之后移），stop 时 quit + join；
 *  - 任何权限/状态异常都 try/catch，**不能让 App 崩溃**（无 GNSS 也能跑，只是准确率下降）。
 *
 * 线程：`LocationListener` 回调在 [locationThread] 上；字段 @Volatile 供管线线程读取。
 */
class GnssSpeedProvider(private val context: Context) {

    companion object {
        private const val TAG = "Sensor"

        /** 请求间隔（ms）：特征只需速度这一列，1Hz 足够。 */
        const val UPDATE_INTERVAL_MS = GnssPolicy.UPDATE_INTERVAL_MS

        /** 最近一次定位超过该时长视为失效（ms）；与训练 `max_gap_ms=2000` 对齐。 */
        const val STALE_MS = GnssPolicy.MAX_FIX_AGE_MS

        /** m/s → km/h。 */
        const val MPS_TO_KMH = GnssPolicy.MPS_TO_KMH

        /** 过期看门狗周期（ms）：保证没有新定位时 gpsAvailable 也会降级。 */
        private const val AVAILABILITY_CHECK_MS = 500L

        /** stop() 时等待定位线程退出的上限（ms）。 */
        private const val THREAD_JOIN_TIMEOUT_MS = 500L
    }

    private val locationManager: LocationManager? =
        runCatching { context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }.getOrNull()

    /** 是否已注册监听。 */
    @Volatile
    var started: Boolean = false
        private set

    /** 是否有近期有效速度（供 UI 显示"卫星信号弱"）；过期后自动 false。 */
    @Volatile
    var available: Boolean = false
        private set

    /** 最近一次有效速度（m/s）。 */
    @Volatile
    private var speedMps: Double = 0.0

    /** 最近一次有效速度（km/h，过期后保留，用于 UI 的 STALE 展示；不代表实时速度）。 */
    @Volatile
    private var lastValidSpeedKmh: Double = 0.0

    /** 本次生命周期内是否获得过有效定位。 */
    @Volatile
    var everFix: Boolean = false
        private set

    /** 最近一次有效定位的单调时钟时间（ns，自 boot）；来自 Location.elapsedRealtimeNanos。 */
    @Volatile
    private var lastFixElapsedNanos: Long = 0L

    /** 可用性变化回调（在定位线程/主线程触发，实现方需线程安全）。 */
    @Volatile
    var onAvailabilityChanged: ((Boolean) -> Unit)? = null

    /** 速度快照回调：有效定位到达 / 过期降级 / 启停时触发（定位线程，实现方需线程安全）。 */
    @Volatile
    var onSpeedUpdated: ((GnssSpeedSnapshot) -> Unit)? = null

    private val availabilityLock = Any()

    /** 回调注册的专用线程（避免 1Hz 定位回调占主线程）。 */
    private var locationThread: HandlerThread? = null
    private var handler: Handler? = null

    /** 过期看门狗：无新定位时也要把 gpsAvailable 拉下来。 */
    private val availabilityWatchdog = object : Runnable {
        override fun run() {
            refreshAvailability()
            handler?.postDelayed(this, AVAILABILITY_CHECK_MS)
        }
    }

    /** 当前速度（m/s）；不可用时为 0。 */
    val speedMpsOrZero: Double
        get() = if (isAvailable()) speedMps else 0.0

    /** 当前速度（km/h）；不可用时为 0。这是特征列 `gps_speed` 的取值。 */
    val speedKmh: Double
        get() = speedMpsOrZero * MPS_TO_KMH

    /** 当前速度快照（available 已做 2s 过期判定）。 */
    fun snapshot(): GnssSpeedSnapshot {
        refreshAvailability()
        return buildSnapshot()
    }

    private fun buildSnapshot(): GnssSpeedSnapshot {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val ageMs = if (lastFixElapsedNanos <= 0L) {
            -1L
        } else {
            ((nowNanos - lastFixElapsedNanos) / 1_000_000L).coerceAtLeast(0L)
        }
        return GnssSpeedSnapshot(
            speedKmh = if (available) speedMps * MPS_TO_KMH else lastValidSpeedKmh,
            fixAgeMs = ageMs,
            available = available,
            everFix = everFix,
        )
    }

    private fun notifySpeedUpdated() {
        val cb = onSpeedUpdated ?: return
        runCatching { cb(buildSnapshot()) }
    }

    /** 有效速度的可用性（带过期判断；过期时会把 available 降级并通知）。 */
    fun isAvailable(): Boolean {
        refreshAvailability()
        return available
    }

    /** 按单调时钟检查定位是否过期；过期则速度归零 + available=false（并通知一次）。 */
    private fun refreshAvailability() {
        if (!started || !available) return
        if (!GnssPolicy.isFresh(lastFixElapsedNanos, SystemClock.elapsedRealtimeNanos(), STALE_MS)) {
            speedMps = 0.0
            setAvailable(false)
            notifySpeedUpdated()
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // 年龄用 elapsedRealtimeNanos（单调时钟）判断；墙钟会被 NTP 跳变污染
            val fixNanos = if (location.elapsedRealtimeNanos > 0L) {
                location.elapsedRealtimeNanos
            } else {
                SystemClock.elapsedRealtimeNanos()
            }
            if (!GnssPolicy.isFresh(fixNanos, SystemClock.elapsedRealtimeNanos(), STALE_MS)) {
                // 延迟投递的旧定位：不刷新速度，也不把 available 拉回 true
                refreshAvailability()
                return
            }
            // 无速度字段 / NaN / Inf / 负数 → 丢弃该定位（不更新速度）
            val speed = GnssPolicy.sanitizeSpeedMps(location.speed, location.hasSpeed()) ?: return
            speedMps = speed
            lastValidSpeedKmh = speed * MPS_TO_KMH
            everFix = true
            lastFixElapsedNanos = fixNanos
            setAvailable(true)
            notifySpeedUpdated()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) {
            speedMps = 0.0
            lastFixElapsedNanos = 0L
            setAvailable(false)
            notifySpeedUpdated()
        }
    }

    /**
     * 开始请求定位更新。无权限 / provider 不可用时静默降级（available=false），不抛异常。
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        // 新管线会话：速度状态从零开始（避免上一次会话的 STALE 值被当成当前值展示）
        speedMps = 0.0
        lastValidSpeedKmh = 0.0
        everFix = false
        lastFixElapsedNanos = 0L
        val manager = locationManager ?: run {
            Log.w(TAG, "LocationManager unavailable, gps_speed=0")
            notifySpeedUpdated()
            return
        }
        val providerAvailable = runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        if (!providerAvailable) {
            Log.w(TAG, "GPS provider disabled, gps_speed=0")
            notifySpeedUpdated()
            return
        }

        val thread = HandlerThread("GNSS-speed").apply { start() }
        val handler = Handler(thread.looper)
        val ok = runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                0f,
                listener,
                handler.looper,
            )
        }.onFailure { t ->
            Log.w(TAG, "requestLocationUpdates failed: ${t.javaClass.simpleName}: ${t.message}")
        }.isSuccess
        if (!ok) {
            handler.removeCallbacksAndMessages(null)
            runCatching { thread.quitSafely() }
            setAvailable(false)
            notifySpeedUpdated()
            return
        }

        locationThread = thread
        this.handler = handler
        started = true
        handler.postDelayed(availabilityWatchdog, AVAILABILITY_CHECK_MS)
        notifySpeedUpdated()
        Log.i(TAG, "GNSS speed provider started")
    }

    /** 停止请求定位更新；定位线程 quit + join，避免泄漏。 */
    fun stop() {
        val manager = locationManager
        if (manager != null) {
            runCatching { manager.removeUpdates(listener) }
        }
        handler?.removeCallbacksAndMessages(null)
        handler = null
        val thread = locationThread
        locationThread = null
        if (thread != null) {
            runCatching { thread.quitSafely() }
            runCatching { thread.join(THREAD_JOIN_TIMEOUT_MS) }
        }
        started = false
        setAvailable(false)
        speedMps = 0.0
        lastValidSpeedKmh = 0.0
        everFix = false
        lastFixElapsedNanos = 0L
        notifySpeedUpdated()
        Log.i(TAG, "GNSS speed provider stopped")
    }

    private fun setAvailable(value: Boolean) {
        val changed = synchronized(availabilityLock) {
            if (available == value) {
                false
            } else {
                available = value
                true
            }
        }
        if (changed) runCatching { onAvailabilityChanged?.invoke(value) }
    }
}
