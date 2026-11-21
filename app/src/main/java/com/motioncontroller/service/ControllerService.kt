package com.motioncontroller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.motioncontroller.MainActivity
import com.motioncontroller.R
import com.motioncontroller.dglab.DglabLinkApi
import com.motioncontroller.dglab.DglabState
import com.motioncontroller.dglab.V4Link
import com.motioncontroller.mapping.MappingConfig
import com.motioncontroller.mapping.MappingJson
import com.motioncontroller.mapping.MappingSnapshot
import com.motioncontroller.mapping.MotionIntensityMapper
import com.motioncontroller.mapping.WaveConfig
import com.motioncontroller.mapping.WaveformEngine
import com.motioncontroller.ml.MotionClass
import com.motioncontroller.ml.MotionPipeline
import com.motioncontroller.ml.MotionPrediction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/**
 * 前台服务：编排 V4 中继/控制器 + 运动识别管线 + 强度映射。
 *
 * 生命周期（异步化，UI 线程永不阻塞）：
 *  - onStartCommand → startForeground(specialUse) → [startEngine]（立即返回，真正启停在 IO/引擎线程）；
 *  - 控制循环 100ms：映射 ramp → 下发强度（仅在 armed 且链路可用时）；
 *  - Activity 绑定后读取 [uiState] 并调用控制方法。
 *
 * 并发模型：
 *  - 每次 startEngine/stopEngine 都会生成新的 generation；[desiredEngine] 只保留最新请求，
 *    实际启停由 [reconcileEngines] 在 [engineMutex] 下串行收敛（stop→start 快速切换也安全）；
 *  - 每个引擎实例拥有自己的 [Engine.job]（SupervisorJob）与独立的协程作用域：
 *    link/pipeline 的 collectors、控制循环都跑在该作用域里，stop 时 cancelAndJoin，
 *    不会泄漏协程，旧 link 的收尾事件也不会覆盖新引擎状态（[isActiveEngine] 令牌校验）。
 *
 * 安全：未 armed 绝不下发；急停锁存；APP 断连/服务销毁归零+清任务；
 *       armed 但 >3s 无新推理 → 死手看门狗强制归零并暂停映射（不自动重新 arm）。
 */
class ControllerService : Service() {

    companion object {
        private const val TAG = "Svc"
        private const val MAP_TAG = "Map"
        const val ACTION_START = "com.motioncontroller.action.START"
        const val ACTION_STOP = "com.motioncontroller.action.STOP"
        const val EXTRA_BIND_HOST = "bind_host"
        const val EXTRA_PORT = "port"
        const val CHANNEL_ID = "controller_channel"
        const val NOTIFICATION_ID = 7

        private const val PREFS = "motion_controller"
        private const val PREF_BIND_HOST = "bind_host"
        private const val PREF_PORT = "port"
        /** 持久化的 MappingConfig + manualOffset（见 [MappingJson]），替代旧的只写不读的 safety_max。 */
        private const val PREF_MAPPING_JSON = "mapping_json"
        private const val PREF_TARGET_ID = "target_id"
        /**
         * E-STOP 锁存跨进程持久化：前台服务进程被系统杀掉后重新启动时，必须恢复锁存，
         * 不能因为进程重建就丢失急停安全状态（只能由显式复位流程清除）。
         */
        private const val PREF_ESTOP_LATCHED = "estop_latched"

        /** 控制循环节拍（ms）。 */
        private const val TICK_MS = 100L
        /** 看门狗：armed 状态下超过该时长没有新推理结果 → 强制归零并暂停映射。 */
        private const val PREDICTION_STALE_MS = 3000L
        /** 看门狗日志节流（ms）。 */
        private const val STALE_LOG_INTERVAL_MS = 3000L
        /** 通知最小刷新间隔（ms）；文案变化/过渡（arm/estop）不受限。 */
        private const val NOTIFICATION_MIN_INTERVAL_MS = 1000L
        /** 正常映射时 predictionAgeMs 的 UI 刷新间隔（ms）。 */
        private const val AGE_PUBLISH_INTERVAL_MS = 1000L
        private const val MAX_LOG_LINES = 120
        /** onDestroy / ACTION_STOP 同步收尾的最长等待（ms）。 */
        private const val DESTROY_TIMEOUT_MS = 2500L
        private const val SHUTDOWN_TIMEOUT_MS = 3000L
        /** START/STOP 命令收敛窗口：命令已受理，UI 显示 STARTING/STOPPING，超时后回落到真实状态。 */
        private const val TRANSITION_SETTLE_MS = 450L
        /** 同一控制命令的最小间隔（ms）：防止连续点击重复下发命令。 */
        private const val COMMAND_DEBOUNCE_MS = 500L
        /** 引擎启动失败后的自动重试延迟（ms）；带 generation 守卫，重试仍失败才上报 engineError（问题4）。 */
        private const val ENGINE_START_RETRY_DELAY_MS = 500L
        /** 归还自动控制时，模型结果允许的最大年龄（ms）；超过视为陈旧，拒绝归还。 */
        private const val RETURN_TO_AUTO_MAX_AGE_MS = PREDICTION_STALE_MS
        /** E-STOP 复位：等待 APP 回执停止命令的最长时间（ms）。超时视为复位失败，保持锁存。 */
        private const val ESTOP_ACK_TIMEOUT_MS = 3000L
        /** E-STOP 复位：等待 APP 回执的轮询间隔（ms）。 */
        private const val ESTOP_ACK_POLL_MS = 50L
        /** E-STOP 复位：阶段间的最小视觉停留（ms），让用户看清 RESETTING / SELF-CHECK。 */
        private const val ESTOP_RESET_STAGE_DELAY_MS = 550L
    }

    /** 一次引擎启动请求；generation 单调递增，用于丢弃过期回调/竞态。 */
    private data class EngineSpec(val generation: Long, val bindHost: String, val port: Int)

    /**
     * 一次引擎运行实例。link/pipeline/collectors/控制循环全部归它所有，
     * 停止时统一取消，避免旧引擎的协程泄漏或事件覆盖新状态。
     */
    private inner class Engine(val spec: EngineSpec) {
        val job = SupervisorJob()

        /**
         * 引擎作用域。mapper 访问在 [mapperLock] 下串行化、UiState 用原子 update，
         * 因此不需要单线程 dispatcher；cancelAndJoin 即可保证停止后不再产生回调。
         */
        val scope = CoroutineScope(job + Dispatchers.Default)

        @Volatile
        var link: DglabLinkApi? = null

        @Volatile
        var pipeline: MotionPipeline? = null

        /** APP 连接状态沿，用于检测掉线（每个引擎独立）。 */
        var appWasConnected = false

        /** 上一次看到的 APP clientId 列表：用于识别「半开旧连接未断开、APP 以新 clientId 重连」的新会话。 */
        var appClientIds: List<String> = emptyList()
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val mapper = MotionIntensityMapper()

    /** 本地波形（强度包络合成）引擎；与 [mapper] 共用 [mapperLock]。 */
    private val waveEngine = WaveformEngine()

    /** 保护 mapper（引擎控制循环/推理回调 vs UI 线程的公开方法），保证映射状态不被并发破坏。 */
    private val mapperLock = Any()

    /** 持久化写入串行化（[persistMappingState]）；与 [mapperLock] 无嵌套。 */
    private val persistLock = Any()

    /** 引擎启停串行化（IO 线程）。 */
    private val engineMutex = Mutex()
    private val generationCounter = AtomicLong(0L)

    @Volatile
    private var desiredEngine: EngineSpec? = null

    @Volatile
    private var activeEngine: Engine? = null

    private val serviceLogs = ArrayDeque<String>()
    private val logLock = Any()

    @Volatile
    private var foregroundStarted = false

    /** onDestroy 后拒绝新的引擎请求（防止销毁流程与 UI 调用竞态）。 */
    @Volatile
    private var destroyed = false

    /**
     * MotionPipeline.start()/stop() 通过 HandlerThread（带 Looper）执行：
     * SensorManager.registerListener 要求调用线程有 Looper，不能直接在 Dispatchers.IO 上调。
     */
    private lateinit var engineThread: HandlerThread
    private lateinit var engineHandler: Handler
    private lateinit var engineDispatcher: CoroutineDispatcher

    // ───────────── 死手看门狗 / 通知节流 ─────────────
    @Volatile
    private var lastPredictionAtElapsedMs = 0L
    private var lastStaleLogAtMs = 0L
    /** 正常映射时 predictionAgeMs 最多 1s 更新一次，避免 10Hz 的无谓 UI 重组。 */
    private var lastAgePublishAtMs = 0L
    private var lastNotificationText = ""
    private var lastNotificationAtMs = 0L

    // ───────────── 控制命令防抖 ─────────────
    private val commandLock = Any()
    private var lastCommandName = ""
    private var lastCommandAtMs = 0L

    // ───────────── E-STOP 复位流程 ─────────────
    /** 复位流程串行化：同一时刻只允许一个复位在跑。 */
    private val estopResetLock = Any()
    private var estopResetInFlight = false
    /** 复位令牌：新的急停/复位请求会递增，旧流程在每个阶段检查令牌并自行放弃。 */
    private var estopResetToken = 0L

    inner class LocalBinder : Binder() {
        fun service(): ControllerService = this@ControllerService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        engineThread = HandlerThread("svc-engine-looper").apply { start() }
        engineHandler = Handler(engineThread.looper)
        engineDispatcher = engineHandler.asCoroutineDispatcher()
        // 映射配置/手动偏移在服务（进程）创建时从 JSON 恢复，UI 首帧就能看到上次的值
        restoreMappingState()
        // 急停锁存跨进程恢复：进程被杀后重新拉起时，绝不能把锁存当成“已复位”
        restoreEstopLatch()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                val host = intent?.getStringExtra(EXTRA_BIND_HOST)
                    ?: prefs().getString(PREF_BIND_HOST, "127.0.0.1")
                    ?: "127.0.0.1"
                val port = intent?.getIntExtra(EXTRA_PORT, prefs().getInt(PREF_PORT, 9998))
                    ?: prefs().getInt(PREF_PORT, 9998)
                startForegroundSafe(host, port)
                startEngine(host, port)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        log("service destroy")
        // 同步收尾（不调用任何 Main dispatcher：引擎线程/IO 都不依赖主线程，避免死锁）：
        // 取消引擎协程 + 归零/停 relay/pipeline，最长 DESTROY_TIMEOUT_MS。
        runCatching {
            runBlocking {
                runCatching { withTimeout(DESTROY_TIMEOUT_MS) { stopEngineAwait() } }
            }
        }
        foregroundStarted = false
        scope.cancel()
        runCatching { engineHandler.removeCallbacksAndMessages(null) }
        runCatching { engineThread.quitSafely() }
        super.onDestroy()
    }

    // ───────────────────────── 对外控制 API（主线程调用，不做阻塞工作） ─────────────────────────

    /**
     * 请求启动引擎（立即返回，非阻塞）。重复调用幂等。
     * 真正的 relay 启动（≤3s latch）/ 管线启动在 IO/引擎线程执行；期间 UI 状态 engineLoading=true。
     */
    fun startEngine(bindHost: String, port: Int) {
        if (destroyed) {
            log("engine start ignored: service destroyed")
            return
        }
        if (desiredEngine != null) {
            log("engine already started")
            return
        }
        // 引擎重启时也从持久化恢复（进程未重启、但用户可能刚在 UI 改过配置）
        restoreMappingState()
        val spec = EngineSpec(generationCounter.incrementAndGet(), bindHost, port)
        desiredEngine = spec
        prefs().edit().putString(PREF_BIND_HOST, bindHost).putInt(PREF_PORT, port).apply()
        _uiState.update { it.copy(engineLoading = true, bindHost = bindHost, port = port) }
        scope.launch(Dispatchers.IO) {
            runCatching { reconcileEngines() }.onFailure(::onEngineReconcileFailure)
        }
    }

    /**
     * 请求停止引擎（立即返回，非阻塞）。generation 立即失效，回调/旧事件随即被忽略；
     * 实际的 cancelAndJoin + relay/pipeline 停止在 IO 线程完成。
     */
    fun stopEngine() {
        if (destroyed) return
        if (desiredEngine == null && activeEngine == null) {
            log("engine already stopped")
            return
        }
        generationCounter.incrementAndGet()
        desiredEngine = null
        _uiState.update { it.copy(engineLoading = true) }
        scope.launch(Dispatchers.IO) {
            runCatching { reconcileEngines() }.onFailure(::onEngineReconcileFailure)
        }
    }

    /** 开始控制（arming）。会先把映射归零并重置看门狗基线，等待下一个 tick 才可能下发。 */
    fun arm() {
        if (rejectWhenEstopLatched("arm")) return
        if (_uiState.value.estopResetInProgress) {
            log("arm rejected: E-STOP reset in progress")
            return
        }
        if (rejectDuplicateCommand("arm")) return
        val link = activeEngine?.link
        if (link == null || !link.controlAvailable()) {
            log("arm rejected: link not ready")
            return
        }
        synchronized(mapperLock) {
            mapper.forceZero()
            // 波形会话随 START 重新计时/相位（持续时间到期锁定也随之解除）
            waveEngine.beginSession(SystemClock.elapsedRealtime())
        }
        lastPredictionAtElapsedMs = SystemClock.elapsedRealtime()
        lastStaleLogAtMs = 0L
        _uiState.update {
            it.copy(
                armed = true,
                targetIntensity = 0,
                currentIntensity = 0,
                recognitionPaused = false,
                restOverrideActive = false,
                predictionAgeMs = 0L,
                engineError = null,
                controlTransition = ControlTransition.STARTING,
                // 操作员在 PRE-FLIGHT 明确点击 START = 接受“设备停止状态无法由本 App 验证”的风险
                estopDeviceStopUnverified = false,
            )
        }
        scheduleTransitionEnd(ControlTransition.STARTING)
        log("ARMED")
        refreshNotification(force = true)
    }

    fun disarm() {
        if (rejectDuplicateCommand("disarm")) return
        synchronized(mapperLock) { mapper.reset(); waveEngine.endSession() }
        val link = activeEngine?.link
        runCatching {
            link?.setTargetIntensity(0, 0)
            link?.setTargetIntensity(1, 0)
            // A/B 归零必须**一次**优先入队；两次独立 resetIntensity 会自清队列（Phase 3 审计 B1）
            link?.resetAllIntensities()
            // Phase 4A：若实验性设备波形任务存在，STOP 时一并清除（默认无任务时不产生帧）
            link?.clearPulseTasks()
        }
        _uiState.update {
            it.copy(
                armed = false,
                targetIntensity = 0,
                currentIntensity = 0,
                recognitionPaused = false,
                restOverrideActive = false,
                manualClass = null,
                effectiveClass = -1,
                controlTransition = ControlTransition.STOPPING,
                waveActive = false,
                waveGain = 1f,
                waveRemainingSec = 0,
            )
        }
        scheduleTransitionEnd(ControlTransition.STOPPING)
        log("DISARMED")
        refreshNotification(force = true)
    }

    /** 急停：立即归零 + 清任务 + 锁存；需显式 [requestEmergencyReset] 才能恢复。 */
    fun emergencyStop() {
        if (rejectDuplicateCommand("estop")) return
        // 新的急停请求会让任何进行中的复位流程作废（令牌递增）
        synchronized(estopResetLock) { estopResetToken++ }
        synchronized(mapperLock) { mapper.reset(); waveEngine.endSession() }
        runCatching { activeEngine?.link?.emergencyStop() }
        persistEstopLatch(true)
        _uiState.update {
            it.copy(
                armed = false,
                emergencyStopped = true,
                targetIntensity = 0,
                currentIntensity = 0,
                recognitionPaused = false,
                restOverrideActive = false,
                manualClass = null,
                effectiveClass = -1,
                controlTransition = ControlTransition.NONE,
                waveActive = false,
                waveGain = 1f,
                waveRemainingSec = 0,
                // 复位流程状态清零：新的锁存必须重新走复位
                estopResetPhase = EstopResetPhase.NONE,
                estopResetChecks = emptyList(),
                estopResetFailure = null,
                estopResetCompletedAtMs = 0L,
                // 本工程没有 BLE 停止回执：设备是否真的停止无法由 App 验证
                estopDeviceStopUnverified = true,
            )
        }
        log("EMERGENCY STOP（latch persisted; output zero + clear; device confirmation unavailable）")
        refreshNotification(force = true)
    }

    /**
     * 兼容旧接口：**不再**直接清锁存，而是走完整的复位流程
     * （REQUESTED → RESETTING：重发安全帧 + 等 APP 回执 → SELF_CHECK → 通过后才清锁存）。
     * 任何 UI 入口都必须调用这个或 [requestEmergencyReset]。
     */
    fun clearEmergencyStop() {
        log("clearEmergencyStop() is deprecated → requestEmergencyReset()")
        requestEmergencyReset()
    }

    /**
     * 请求复位 E-STOP（异步状态机；不阻塞调用线程）。
     *
     * 安全约束：
     *  - 只有锁存状态可复位；复位期间重复请求会被忽略；
     *  - 必须先通过链路检查（服务/中继/APP/设备在线），否则失败并保持锁存；
     *  - 未收到 APP 回执时重新下发 A/B 归零 + clear（幂等），超时 → 失败保持锁存；
     *  - 启动自检（模型/scaler）失败 → 失败保持锁存；
     *  - 成功后只清锁存，**不启动控制、不发送新的输出指令**；设备停止状态仍标记为未验证。
     */
    fun requestEmergencyReset() {
        if (destroyed) return
        val token = synchronized(estopResetLock) {
            if (!_uiState.value.emergencyStopped) {
                log("estop reset ignored: not latched")
                return
            }
            if (estopResetInFlight) {
                log("estop reset ignored: already in progress")
                return
            }
            estopResetInFlight = true
            ++estopResetToken
        }
        _uiState.update {
            it.copy(
                estopResetPhase = EstopResetPhase.REQUESTED,
                estopResetChecks = emptyList(),
                estopResetFailure = null,
                estopResetCompletedAtMs = 0L,
            )
        }
        log("E-STOP RESET REQUESTED")
        refreshNotification(force = true)
        scope.launch { performEstopReset(token) }
    }

    /**
     * 复位主流程。每个阶段前检查令牌：期间用户再次触发急停/新的复位请求会让本流程静默放弃。
     */
    private suspend fun performEstopReset(token: Long) {
        try {
            fun current(): Boolean = synchronized(estopResetLock) {
                estopResetToken == token && _uiState.value.emergencyStopped
            }

            var link = activeEngine?.link
            var engine = activeEngine
            if (engine == null || link == null) {
                failEstopReset(token, EstopResetFailureCode.SERVICE_OFFLINE)
                return
            }

            // ── 阶段 1：RESETTING（链路检查 + 重发停止命令 + 等待 APP 回执）──
            publishEstopResetPhase(EstopResetPhase.RESETTING, link, engine)
            delay(ESTOP_RESET_STAGE_DELAY_MS)
            if (!current()) return

            val linkFailure = EstopResetPolicy.evaluateLinks(resetSnapshot(engine, link))
            if (linkFailure != null) {
                failEstopReset(token, linkFailure.code, linkFailure.detail)
                return
            }

            if (EstopResetPolicy.needsStopReissue(resetSnapshot(engine, link))) {
                log("estop reset: re-issuing A/B zero + clear (stop command not acknowledged yet)")
                runCatching { link.emergencyStop() }
                val acked = awaitEstopAck(token, link)
                if (!acked) {
                    failEstopReset(
                        token,
                        EstopResetFailureCode.STOP_COMMAND_NOT_ACKED,
                        // 只带真实链路错误；不要把上一次会话的 reason 当成本次失败原因
                        link.state.value.lastError,
                    )
                    return
                }
            }
            if (!current()) return
            if (activeEngine?.link !== link) {
                failEstopReset(token, EstopResetFailureCode.LINK_REPLACED)
                return
            }
            // 重发之后仍不满足“停止帧已入队 + APP 已回执” → 不能继续复位
            resetSnapshot(engine, link).let { afterReissue ->
                val stopFailure = EstopResetPolicy.evaluateLink(afterReissue)
                if (stopFailure != null) {
                    failEstopReset(token, stopFailure.code, stopFailure.detail)
                    return
                }
            }

            // ── 阶段 2：SELF_CHECK（模型 / scaler）──
            publishEstopResetPhase(EstopResetPhase.SELF_CHECK, link, engine)
            delay(ESTOP_RESET_STAGE_DELAY_MS)
            if (!current()) return
            engine = activeEngine
            link = engine?.link
            if (engine == null || link == null) {
                failEstopReset(token, EstopResetFailureCode.SERVICE_OFFLINE)
                return
            }
            val snapshot = resetSnapshot(engine, link)
            val selfCheckFailure = EstopResetPolicy.evaluateSelfCheck(snapshot)
            if (selfCheckFailure != null) {
                failEstopReset(token, selfCheckFailure.code, selfCheckFailure.detail)
                return
            }
            // 自检期间链路可能再次断开（需求：复位过程中设备再次断开）→ 用具体原因中止，
            // 而不是笼统报“未回执”。
            val finalLinkFailure = EstopResetPolicy.evaluateLink(snapshot)
            if (finalLinkFailure != null) {
                failEstopReset(token, finalLinkFailure.code, finalLinkFailure.detail)
                return
            }
            if (!snapshot.appAcked) {
                failEstopReset(token, EstopResetFailureCode.STOP_COMMAND_NOT_ACKED)
                return
            }
            if (!EstopResetPolicy.canClearLatch(snapshot)) {
                failEstopReset(token, EstopResetFailureCode.UNKNOWN)
                return
            }

            // ── 成功：先固化最终检查清单，确认令牌未被新的急停请求覆盖，再清锁存 ──
            val finalChecks = EstopResetPolicy.checks(snapshot)
            synchronized(estopResetLock) {
                if (estopResetToken != token) return // 期间又被急停/新复位：保持锁存
            }
            runCatching { link.clearEmergencyStop() }
            persistEstopLatch(false)
            synchronized(estopResetLock) {
                if (estopResetToken != token) return // 清除期间被新急停覆盖：不再写“已解除”状态
            }
            _uiState.update {
                it.copy(
                    emergencyStopped = false,
                    armed = false,
                    targetIntensity = 0,
                    currentIntensity = 0,
                    recognitionPaused = false,
                    restOverrideActive = false,
                    manualClass = null,
                    effectiveClass = -1,
                    controlTransition = ControlTransition.NONE,
                    waveActive = false,
                    waveGain = 1f,
                    waveRemainingSec = 0,
                    estopResetPhase = EstopResetPhase.NONE,
                    estopResetChecks = finalChecks,
                    estopResetFailure = null,
                    estopResetCompletedAtMs = SystemClock.elapsedRealtime(),
                    // 复位成功 ≠ 设备已确认停止：BLE 无回执，保持 UNVERIFIED
                    estopDeviceStopUnverified = true,
                )
            }
            log(
                "E-STOP RESET COMPLETE：锁存已清除，未启动控制；" +
                    "DEVICE STOP 仍为 UNVERIFIED（BLE 无回执），恢复 OPERATION 后保持 DISARMED，需操作员确认后再 START",
            )
            refreshNotification(force = true)
        } catch (t: Throwable) {
            log("E-STOP reset crashed: ${t.javaClass.simpleName}: ${t.message}")
            failEstopReset(token, EstopResetFailureCode.UNKNOWN, t.message)
        } finally {
            synchronized(estopResetLock) { estopResetInFlight = false }
        }
    }

    /** 轮询等待 APP 回执；期间持续刷新检查清单，超时返回 false（不修改锁存）。 */
    private suspend fun awaitEstopAck(token: Long, link: DglabLinkApi): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ESTOP_ACK_TIMEOUT_MS
        var lastChecks = emptyList<EstopResetCheck>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = synchronized(estopResetLock) {
                estopResetToken == token && _uiState.value.emergencyStopped
            }
            if (!current) return false
            val engine = activeEngine
            if (engine == null) return false
            val checks = EstopResetPolicy.checks(resetSnapshot(engine, link))
            if (checks != lastChecks) {
                lastChecks = checks
                _uiState.update { it.copy(estopResetChecks = checks) }
            }
            if (link.state.value.estopAppAcked) return true
            delay(ESTOP_ACK_POLL_MS)
        }
        return false
    }

    private fun publishEstopResetPhase(phase: EstopResetPhase, link: DglabLinkApi?, engine: Engine?) {
        val checks = if (engine != null && link != null) {
            EstopResetPolicy.checks(resetSnapshot(engine, link))
        } else {
            emptyList()
        }
        _uiState.update {
            it.copy(
                estopResetPhase = phase,
                estopResetChecks = checks,
                estopResetFailure = null,
            )
        }
    }

    private fun failEstopReset(token: Long, code: EstopResetFailureCode, detail: String? = null) {
        val stale = synchronized(estopResetLock) { estopResetToken != token }
        if (stale) {
            log("estop reset abandoned (superseded) before failure: $code")
            return
        }
        val engine = activeEngine
        val link = engine?.link
        val checks = if (engine != null && link != null) {
            EstopResetPolicy.checks(resetSnapshot(engine, link))
        } else {
            emptyList()
        }
        _uiState.update {
            it.copy(
                // 保持 emergencyStopped = true：失败绝不允许绕过锁存
                emergencyStopped = true,
                estopResetPhase = EstopResetPhase.FAILED,
                estopResetChecks = checks,
                estopResetFailure = EstopResetFailure(code, detail),
            )
        }
        log("E-STOP RESET FAILED: $code${detail?.let { " ($it)" } ?: ""} — latch remains engaged")
        refreshNotification(force = true)
    }

    /** 复位决策快照：只读当前引擎/链路/管线状态，不做任何副作用。 */
    private fun resetSnapshot(engine: Engine?, link: DglabLinkApi?): EstopResetLinkSnapshot {
        val linkState = link?.state?.value
        val pipelineStatus = engine?.pipeline?.status?.value
        return EstopResetLinkSnapshot(
            serviceRunning = _uiState.value.serviceRunning && engine != null,
            engineAvailable = engine != null && activeEngine === engine,
            relayRunning = linkState?.relayRunning == true,
            appConnected = linkState?.appConnected == true,
            deviceReady = linkState?.deviceReady == true,
            stopIssued = linkState?.estopIssued == true,
            framesQueued = linkState?.estopFramesQueued == true,
            appAcked = linkState?.estopAppAcked == true,
            ackReason = linkState?.estopAckReason,
            modelLoaded = pipelineStatus?.modelLoaded == true,
            scalerValid = pipelineStatus?.scalerValid == true,
        )
    }

    /**
     * 人工接管：用户显式指定控制类别（只改变选择哪个 classBase，不改映射公式/安全限制）。
     * 接管持续到用户归还（[returnToAuto]）或安全机制终止会话（disarm/急停/断连/引擎重启）。
     */
    fun overrideMotionClass(index: Int) {
        if (rejectWhenEstopLatched("manual override")) return
        val cls = MotionClass.of(index)
        if (cls == null) {
            log("manual override rejected: invalid class $index")
            return
        }
        synchronized(mapperLock) {
            if (!mapper.setManualClass(index)) {
                log("manual override rejected by mapper: $index")
                return
            }
        }
        val st = _uiState.value
        _uiState.update {
            it.copy(
                manualClass = index,
                effectiveClass = index,
                // 人工接管不改模型结果，仅同步一次展示口径
                stableClass = it.stableClass,
                targetIntensity = it.targetIntensity,
                currentIntensity = st.currentIntensity,
            )
        }
        log(
            "MANUAL OVERRIDE → ${cls.name}（base=${mapper.effectiveBase.toInt()} " +
                "offset=${mapper.manualOffset.toInt()} targetRaw=${mapper.targetRaw.toInt()}）",
        )
        refreshNotification(force = true)
    }

    /**
     * 归还自动控制（RETURN TO AUTO）。
     *
     * 安全约束：模型结果必须**新鲜且可用**（管线运行、有稳定类别、未处于看门狗暂停、年龄 ≤3s），
     * 否则拒绝归还并保持人工接管——绝不沿用陈旧模型结果恢复自动控制。
     * 切换前后 targetRaw 的变化仍由 mapper 的 slew 限制，不会产生输出阶跃。
     *
     * @return true 表示已归还自动；false 表示被拒绝（原因已写日志/可由 UI 预先判断）。
     */
    fun returnToAuto(): Boolean {
        if (rejectWhenEstopLatched("return-to-auto")) return false
        if (_uiState.value.manualClass == null) return true
        val st = _uiState.value
        val age = predictionAge(SystemClock.elapsedRealtime())
        val modelValid = st.pipeline.running &&
            st.prediction != null &&
            st.stableClass >= 0 &&
            !st.recognitionPaused &&
            age <= RETURN_TO_AUTO_MAX_AGE_MS
        if (!modelValid) {
            log(
                "return-to-auto rejected: model data invalid/stale " +
                    "(running=${st.pipeline.running} stable=${st.stableClass} paused=${st.recognitionPaused} " +
                    "age=${if (age == Long.MAX_VALUE) "n/a" else "${age}ms"})",
            )
            refreshNotification(force = true)
            return false
        }
        val (oldTarget, newTarget, newClass) = synchronized(mapperLock) {
            val before = mapper.targetRaw
            mapper.setManualClass(null)
            Triple(before, mapper.targetRaw, mapper.effectiveClassIndex)
        }
        _uiState.update {
            it.copy(manualClass = null, effectiveClass = newClass)
        }
        log(
            "RETURN TO AUTO（model stable=${MotionClass.of(st.stableClass)?.name} " +
                "targetRaw ${oldTarget.toInt()}→${newTarget.toInt()}，slew 限速）",
        )
        refreshNotification(force = true)
        return true
    }

    fun setManualOffset(value: Float) {
        if (rejectWhenEstopLatched("manual offset")) return
        synchronized(mapperLock) { mapper.setManualOffset(value) }
        persistMappingState()
        _uiState.update { it.copy(manualOffset = synchronized(mapperLock) { mapper.manualOffset }) }
    }

    fun updateMappingConfig(config: MappingConfig) {
        if (rejectWhenEstopLatched("mapping update")) return
        val (cfg, offset) = synchronized(mapperLock) {
            mapper.updateConfig(config)
            mapper.config to mapper.manualOffset
        }
        persistMappingState()
        _uiState.update { it.copy(mapping = cfg, manualOffset = offset) }
        log("mapping updated: max=${cfg.safetyMax} base=${cfg.classBase}")
    }

    /**
     * 更新本地波形（强度包络合成）配置并持久化。
     *
     * 只改变映射输出的调制方式：不放大输出、不绕过 safetyMax/slew/急停/断连归零；
     * 协议限制（V4 无法切换 DG-LAB APP 波形）与安全约定见 `mapping/Waveform.kt`。
     * 关闭开关立即结束会话；启用后由控制循环在下一次 tick 开始计时/相位。
     */
    fun setWaveConfig(config: WaveConfig) {
        if (rejectWhenEstopLatched("wave config")) return
        val safe = config.sanitized()
        synchronized(mapperLock) { waveEngine.setConfig(safe) }
        persistMappingState()
        _uiState.update { it.copy(wave = safe) }
        log(
            "wave updated: enabled=${safe.enabled} shape=${safe.shape.id} " +
                "f=${safe.frequencyHz}Hz depth=${safe.depthPercent}% duration=${safe.durationSec}s",
        )
    }

    /**
     * E-STOP 锁存期间拒绝普通控制操作（Service 层防御：即使 UI 被绕过/旧入口仍存在，
     * 也不能在锁存状态下启动控制、改映射或改波形）。
     */
    private fun rejectWhenEstopLatched(action: String): Boolean {
        if (!_uiState.value.emergencyStopped) return false
        log("$action rejected: E-STOP latched (reset required)")
        return true
    }

    /**
     * 控制命令防抖：同一命令在 [COMMAND_DEBOUNCE_MS] 内重复调用会被忽略，防止连续点击
     * 造成重复下发（尤其是急停的 A/B 归零 + clear 帧）。
     */
    private fun rejectDuplicateCommand(name: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(commandLock) {
            if (name == lastCommandName && now - lastCommandAtMs < COMMAND_DEBOUNCE_MS) {
                log("command ignored (debounce): $name")
                return true
            }
            lastCommandName = name
            lastCommandAtMs = now
            return false
        }
    }

    /** STARTING/STOPPING 窗口结束：只清掉本次过渡标记，真实状态以 armed/链路为准。 */
    private fun scheduleTransitionEnd(transition: ControlTransition) {
        scope.launch {
            delay(TRANSITION_SETTLE_MS)
            _uiState.update {
                if (it.controlTransition == transition) it.copy(controlTransition = ControlTransition.NONE) else it
            }
        }
    }

    // ───────────────────────── 引擎启停收敛 ─────────────────────────

    /**
     * 把引擎状态收敛到 [desiredEngine]。任何线程可调用（阻塞调用都在独立线程/IO 上），
     * 由 [engineMutex] 串行化。循环处理“收敛过程中又有新请求”的情况。
     */
    private suspend fun reconcileEngines() {
        engineMutex.withLock {
            while (true) {
                val spec = desiredEngine
                val active = activeEngine
                if (spec == null) {
                    if (active == null) {
                        publishEngineLoading(false)
                        return
                    }
                    stopEngineResources(active)
                    activeEngine = null
                    publishStoppedState(active.spec)
                    continue
                }
                if (active != null && active.spec == spec) {
                    publishEngineLoading(false)
                    return
                }
                if (active != null) {
                    stopEngineResources(active)
                    activeEngine = null
                    publishStoppedState(active.spec)
                }
                if (desiredEngine != spec) continue

                // 引擎启动失败自动重试一次（问题4：快速重启时 relay 端口释放竞态偶发 BindException）。
                // generation 守卫：重试等待期间收到 stop/新请求（desiredEngine 换代/服务销毁）即放弃。
                var engine: Engine? = null
                var lastFailure: Throwable? = null
                for (attempt in 0..1) {
                    if (attempt > 0) {
                        log("engine start retry after ${ENGINE_START_RETRY_DELAY_MS}ms (gen=${spec.generation})")
                        delay(ENGINE_START_RETRY_DELAY_MS)
                        if (destroyed || desiredEngine != spec) break
                    }
                    try {
                        engine = createEngine(spec)
                        lastFailure = null
                        break
                    } catch (t: Throwable) {
                        lastFailure = t
                        log("engine start failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }

                val started = engine
                if (started == null) {
                    if (desiredEngine == spec) {
                        desiredEngine = null
                        publishStoppedState(spec)
                    }
                    _uiState.update {
                        it.copy(
                            engineError = "引擎启动失败: " +
                                "${lastFailure?.message ?: lastFailure?.javaClass?.simpleName}",
                        )
                    }
                    publishEngineLoading(false)
                    continue
                }

                if (desiredEngine != spec) {
                    // 启动期间收到 stop/新请求：立即回收刚启动的引擎，再由下一轮处理最新请求
                    stopEngineResources(started)
                    publishStoppedState(spec)
                    continue
                }

                activeEngine = started
                launchEngineJobs(started)
                publishRunningState(started)
                publishEngineLoading(false)
                return
            }
        }
    }

    /** 创建并启动 link+pipeline（阻塞工作全在 IO / 引擎 Looper 线程），失败时自行清理。 */
    private suspend fun createEngine(spec: EngineSpec): Engine {
        val engine = Engine(spec)
        try {
            withContext(Dispatchers.IO) {
                val newLink = V4Link()
                engine.link = newLink
                // 复用上次的 targetId，保证 DG-LAB APP 里手输的地址跨重启/切绑定地址都不变
                newLink.start(spec.bindHost, spec.port, prefs().getString(PREF_TARGET_ID, null))
            }
            withContext(engineDispatcher) {
                val newPipeline = MotionPipeline(this@ControllerService)
                engine.pipeline = newPipeline
                newPipeline.start { prediction -> onEnginePrediction(engine, prediction) }
            }
            return engine
        } catch (t: Throwable) {
            // 部分启动成功也要收干净，避免半启动的 relay 线程泄漏
            runCatching { withContext(Dispatchers.IO) { engine.pipeline?.stop() } }
            runCatching {
                withContext(Dispatchers.IO) {
                    engine.link?.let { link ->
                        runCatching { link.emergencyStop() }
                        runCatching { link.stop() }
                    }
                }
            }
            runCatching { engine.job.cancel() }
            throw t
        }
    }

    /** 在引擎自己的作用域里启动 3 个 collector + 控制循环；全部随 [Engine.job] 一起取消。 */
    private fun launchEngineJobs(engine: Engine) {
        val link = engine.link ?: return
        val pipeline = engine.pipeline ?: return
        engine.scope.launch {
            link.state.collect { st -> if (isActiveEngine(engine)) onLinkState(engine, st) }
        }
        engine.scope.launch {
            link.logLines.collect { lines -> if (isActiveEngine(engine)) pushExternalLogs(lines) }
        }
        engine.scope.launch {
            pipeline.status.collect { st ->
                if (isActiveEngine(engine)) _uiState.update { it.copy(pipeline = st) }
            }
        }
        engine.scope.launch { controlLoop(engine) }
    }

    /** 取消该引擎的全部协程并停 relay/pipeline；返回后该实例不再产生任何状态更新。 */
    private suspend fun stopEngineResources(engine: Engine) {
        // 先取消 collectors/控制循环并等待结束：之后 link/pipeline 的收尾事件不会再写 UiState
        runCatching { engine.job.cancelAndJoin() }
        withContext(Dispatchers.IO) {
            runCatching { engine.link?.emergencyStop() }
            runCatching { engine.pipeline?.stop() } // 内部最多等 300ms
            runCatching { engine.link?.stop() }     // 内部最多等 500ms
        }
        synchronized(mapperLock) { mapper.reset(); waveEngine.endSession() }
        lastPredictionAtElapsedMs = 0L
        lastStaleLogAtMs = 0L
    }

    /** 仅当前 desired 请求对应的引擎才允许写状态（丢弃旧 link/旧 pipeline 的回调）。 */
    private fun isActiveEngine(engine: Engine): Boolean =
        activeEngine === engine && desiredEngine?.generation == engine.spec.generation

    private fun onEngineReconcileFailure(t: Throwable) {
        log("engine reconcile failed: ${t.javaClass.simpleName}: ${t.message}")
        publishEngineLoading(false)
    }

    // ───────────────────────── 状态发布 ─────────────────────────

    private fun publishEngineLoading(loading: Boolean) {
        if (_uiState.value.engineLoading == loading) return
        _uiState.update { it.copy(engineLoading = loading) }
    }

    private fun publishRunningState(engine: Engine) {
        val link = engine.link
        val address = runCatching { link?.clientUrl() ?: "" }.getOrDefault("")
        _uiState.update {
            it.copy(
                serviceRunning = true,
                engineLoading = false,
                engineError = null,
                bindHost = engine.spec.bindHost,
                port = engine.spec.port,
                armed = false,
                targetIntensity = 0,
                currentIntensity = 0,
                mapping = mapper.config,
                manualOffset = mapper.manualOffset,
                address = address,
                qrPayload = buildQrPayload(address),
                recognitionPaused = false,
                restOverrideActive = false,
                predictionAgeMs = 0L,
                manualClass = null,
                effectiveClass = -1,
                controlTransition = ControlTransition.NONE,
                link = link?.state?.value ?: it.link,
                // 急停锁存跨引擎重启保留：必须显式解除，避免“重启中继”绕过安全锁
            )
        }
        lastPredictionAtElapsedMs = 0L
        lastStaleLogAtMs = 0L
        lastAgePublishAtMs = 0L
        // 若急停仍锁存，把新引擎的 link 也同步到锁存态（不会下发任何强度）
        if (_uiState.value.emergencyStopped) {
            runCatching { link?.emergencyStop() }
        }
        refreshNotification(force = true)
    }

    private fun publishStoppedState(spec: EngineSpec?) {
        val previous = _uiState.value
        _uiState.value = UiState(
            serviceRunning = false,
            engineLoading = false,
            bindHost = spec?.bindHost ?: previous.bindHost,
            port = spec?.port ?: previous.port,
            mapping = mapper.config,
            manualOffset = mapper.manualOffset,
            // 波形是用户配置（非引擎会话状态）：STOP 后 UI 保持配置，只复位运行时显示字段（审计 S3）
            wave = synchronized(mapperLock) { waveEngine.config },
            // 急停锁存必须跨引擎停止/重启保留，只能由复位流程显式解除
            emergencyStopped = previous.emergencyStopped,
            estopResetPhase = previous.estopResetPhase,
            estopResetChecks = previous.estopResetChecks,
            estopResetFailure = previous.estopResetFailure,
            estopResetCompletedAtMs = previous.estopResetCompletedAtMs,
            estopDeviceStopUnverified = previous.estopDeviceStopUnverified,
            engineError = previous.engineError,
            logs = snapshotLogs(),
        )
        lastPredictionAtElapsedMs = 0L
        lastStaleLogAtMs = 0L
        lastAgePublishAtMs = 0L
        refreshNotification(force = true)
    }

    // ───────────────────────── 推理 / 链路回调 ─────────────────────────

    /** 推理回调（ML 推理线程）：记录到达时刻，再切到引擎线程处理映射。 */
    private fun onEnginePrediction(engine: Engine, prediction: MotionPrediction) {
        if (!isActiveEngine(engine)) return
        lastPredictionAtElapsedMs = SystemClock.elapsedRealtime()
        engine.scope.launch {
            if (isActiveEngine(engine)) handlePrediction(engine, prediction)
        }
    }

    private fun handlePrediction(engine: Engine, prediction: MotionPrediction) {
        val stable: Int
        val effective: Int
        val manual: Int?
        val rest: Boolean
        val probs: List<Float>
        val target: Float
        synchronized(mapperLock) {
            stable = mapper.onPrediction(prediction)
            effective = mapper.effectiveClassIndex
            manual = mapper.manualClassIndex
            rest = mapper.restOverrideActive
            probs = mapper.smoothedProbs().toList()
            target = mapper.targetRaw
        }
        Log.i(
            MAP_TAG,
            "pred=${prediction.motionClass?.name} stable=$stable effective=$effective manual=$manual " +
                "target=${target.toInt()} rest=$rest gyro=${prediction.gyroMean} accStd=${prediction.accStd}",
        )
        val wasPaused = _uiState.value.recognitionPaused
        _uiState.update {
            it.copy(
                prediction = prediction,
                smoothedProbs = probs,
                stableClass = stable,
                effectiveClass = effective,
                manualClass = manual,
                scalerValid = engine.pipeline?.status?.value?.scalerValid ?: false,
                recognitionPaused = false,
                restOverrideActive = rest,
                predictionAgeMs = 0L,
            )
        }
        if (wasPaused) {
            lastAgePublishAtMs = 0L
            log("watchdog: prediction resumed（已清 paused，正常 2 窗口映射，不自动 re-arm）")
            refreshNotification(force = true)
        }
    }

    private fun onLinkState(engine: Engine, linkState: DglabState) {
        if (!isActiveEngine(engine)) return
        // APP 掉线 → 立即停止下发并归零
        val connected = linkState.appConnected
        val clientIds = linkState.appClientIds
        // 半开旧连接尚未触发 onClose 时，APP 可能以新 clientId 重连：只要 attached 集合变化，
        // 旧会话的 ARMED 状态就不能延续（Phase 3 审计 C1：否则用户没再点 START 也会继续下发）。
        val newSessionWhileConnected =
            connected && engine.appWasConnected && clientIds != engine.appClientIds
        if (engine.appWasConnected && !connected) {
            synchronized(mapperLock) { mapper.reset(); waveEngine.endSession() }
            if (_uiState.value.armed) log("APP disconnected → disarmed")
            _uiState.update {
                it.copy(
                    armed = false,
                    targetIntensity = 0,
                    currentIntensity = 0,
                    recognitionPaused = false,
                    restOverrideActive = false,
                    manualClass = null,
                    effectiveClass = -1,
                    controlTransition = ControlTransition.NONE,
                    waveActive = false,
                    waveGain = 1f,
                    waveRemainingSec = 0,
                )
            }
        } else if (newSessionWhileConnected) {
            synchronized(mapperLock) { mapper.reset(); waveEngine.endSession() }
            if (_uiState.value.armed) {
                log("APP session changed (${engine.appClientIds} → $clientIds) → disarmed（新会话不延续 ARMED）")
            }
            _uiState.update {
                it.copy(
                    armed = false,
                    targetIntensity = 0,
                    currentIntensity = 0,
                    recognitionPaused = false,
                    restOverrideActive = false,
                    manualClass = null,
                    effectiveClass = -1,
                    controlTransition = ControlTransition.NONE,
                    waveActive = false,
                    waveGain = 1f,
                    waveRemainingSec = 0,
                )
            }
        }
        engine.appWasConnected = connected
        engine.appClientIds = clientIds

        // 持久化 targetId：地址在 APP 侧保持不变，避免重启后 APP 拿旧 tid 被 4001 拒绝
        if (linkState.targetId.isNotBlank() &&
            prefs().getString(PREF_TARGET_ID, null) != linkState.targetId
        ) {
            prefs().edit().putString(PREF_TARGET_ID, linkState.targetId).apply()
        }

        val address = if (linkState.targetId.isBlank()) "" else runCatching {
            engine.link?.clientUrl() ?: ""
        }.getOrDefault("")
        _uiState.update {
            it.copy(
                link = linkState,
                emergencyStopped = linkState.emergencyStopped || it.emergencyStopped,
                address = address,
                qrPayload = buildQrPayload(address),
            )
        }
        refreshNotification()
    }

    // ───────────────────────── 控制循环 ─────────────────────────

    private suspend fun controlLoop(engine: Engine) {
        var lastTickAt = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive) {
            delay(TICK_MS)
            if (!isActiveEngine(engine)) break
            val now = SystemClock.elapsedRealtime()
            // 用单调时钟计算 dt，并钳制到 [0, 200]ms：主线程卡顿/后台调度不会造成强度跳变
            val dt = (now - lastTickAt).coerceIn(0L, MappingConfig.MAX_DT_MS)
            lastTickAt = now

            val st = _uiState.value
            if (!st.armed || st.emergencyStopped) continue
            val link = engine.link ?: continue

            val age = predictionAge(now)
            if (age > PREDICTION_STALE_MS) {
                // 死手看门狗：armed 但超过 3s 没有新推理（例如息屏后传感器暂停）→
                // 映射重置、目标/当前归零、recognitionPaused=true；不自动 re-arm。
                // 波形会话**不**结束（duration 是 START 会话的墙钟时长，审计 S1）：
                // 暂停期间输出强制 0、显示为未调制；恢复后同一会话继续，到期自动关闭语义不变。
                synchronized(mapperLock) { mapper.reset() }
                val waveRemainNow = synchronized(mapperLock) { waveEngine.remainingSec(now) }
                if (!_uiState.value.recognitionPaused) {
                    _uiState.update {
                        it.copy(
                            recognitionPaused = true,
                            restOverrideActive = false,
                            stableClass = -1,
                            effectiveClass = -1,
                            manualClass = null,
                            smoothedProbs = List(MotionClass.entries.size) { 0f },
                            targetIntensity = 0,
                            currentIntensity = 0,
                            predictionAgeMs = age,
                            waveActive = false,
                            waveGain = 1f,
                            waveRemainingSec = waveRemainNow,
                        )
                    }
                    log("watchdog: no prediction ${age}ms while armed → mapper reset, output forced 0 (paused)")
                    lastStaleLogAtMs = now
                    refreshNotification(force = true)
                } else if (now - lastStaleLogAtMs >= STALE_LOG_INTERVAL_MS) {
                    lastStaleLogAtMs = now
                    _uiState.update {
                        it.copy(
                            targetIntensity = 0,
                            currentIntensity = 0,
                            predictionAgeMs = age,
                            waveActive = false,
                            waveGain = 1f,
                            waveRemainingSec = waveRemainNow,
                        )
                    }
                    log("watchdog: still paused (no prediction ${age}ms)")
                }
                // 即使 mapper 归零，也显式把链路目标压到 0（防实现侧残留）
                runCatching {
                    link.setTargetIntensity(0, 0)
                    link.setTargetIntensity(1, 0)
                }
                continue
            }

            if (!link.controlAvailable()) continue

            // 映射输出 → 本地波形（强度包络）调制 → 链路目标。
            // 包络只向下调制、不放大；上限不可破（base 已过 safetyMax/slew，链路层 MAX_STEP 再限速），
            // 见 mapping/Waveform.kt。
            var waveExpired = false
            var waveCfgAfter = WaveConfig.Default
            var waveRemaining = 0
            val (base, waveTick) = synchronized(mapperLock) {
                val mapped = mapper.step(dt)
                val tick = waveEngine.tick(mapped, now)
                if (tick.expired) {
                    // 持续时间到期：自动关闭开关。与 tick 同一把锁处理，
                    // 用户并发 setWaveConfig 不会被这次自动关闭覆盖（审计 S2）。
                    waveEngine.setConfig(waveEngine.config.copy(enabled = false))
                    waveExpired = true
                }
                waveCfgAfter = waveEngine.config
                waveRemaining = waveEngine.remainingSec(now)
                mapped to tick
            }
            if (waveExpired) {
                persistMappingState()
                _uiState.update {
                    it.copy(wave = waveCfgAfter, waveActive = false, waveGain = 1f, waveRemainingSec = 0)
                }
                log("wave: duration reached → auto disabled")
            }
            val intensity = waveTick.output
            runCatching {
                link.setTargetIntensity(0, intensity)
                link.setTargetIntensity(1, intensity)
            }
            // predictionAgeMs 最多 1s 刷新一次，避免每 tick 都产生新 UiState（10Hz 重组）
            val publishAge = if (now - lastAgePublishAtMs >= AGE_PUBLISH_INTERVAL_MS) {
                lastAgePublishAtMs = now
                age
            } else {
                _uiState.value.predictionAgeMs
            }
            if (_uiState.value.targetIntensity != intensity ||
                _uiState.value.currentIntensity != intensity ||
                _uiState.value.predictionAgeMs != publishAge ||
                _uiState.value.waveActive != waveTick.active ||
                _uiState.value.waveGain != waveTick.gain ||
                _uiState.value.waveBaseIntensity != base ||
                _uiState.value.waveRemainingSec != waveRemaining
            ) {
                _uiState.update {
                    it.copy(
                        targetIntensity = intensity,
                        currentIntensity = intensity,
                        predictionAgeMs = publishAge,
                        restOverrideActive = mapper.restOverrideActive,
                        waveActive = waveTick.active,
                        waveGain = waveTick.gain,
                        waveBaseIntensity = base,
                        waveRemainingSec = waveRemaining,
                    )
                }
            }
        }
    }

    /** 距最近一次推理到达的时长（ms）；从未收到过返回 Long.MAX_VALUE。 */
    private fun predictionAge(now: Long): Long {
        val last = lastPredictionAtElapsedMs
        return if (last <= 0L) Long.MAX_VALUE else (now - last).coerceAtLeast(0L)
    }

    // ───────────────────────── 日志 / 持久化 / 工具 ─────────────────────────

    private fun pushExternalLogs(lines: List<String>) {
        if (lines.isEmpty()) return
        // 保持原有展示行为：服务日志 + relay 协议日志（累计列表），最多保留 MAX_LOG_LINES 条
        val merged = synchronized(logLock) {
            val combined = (serviceLogs.toList() + lines).takeLast(MAX_LOG_LINES)
            serviceLogs.clear()
            serviceLogs.addAll(combined)
            combined
        }
        _uiState.update { it.copy(logs = merged) }
    }

    private fun log(message: String) {
        val line = "${System.currentTimeMillis() % 100000} $message"
        Log.i(TAG, message)
        val merged = synchronized(logLock) {
            serviceLogs.addLast(line)
            while (serviceLogs.size > MAX_LOG_LINES) serviceLogs.removeFirst()
            serviceLogs.toList()
        }
        _uiState.update { it.copy(logs = merged) }
    }

    private fun snapshotLogs(): List<String> = synchronized(logLock) { serviceLogs.toList() }

    private fun restoreMappingState() {
        val snapshot = MappingJson.decode(prefs().getString(PREF_MAPPING_JSON, null))
        synchronized(mapperLock) {
            mapper.updateConfig(snapshot.config)
            mapper.setManualOffset(snapshot.manualOffset)
            waveEngine.setConfig(snapshot.wave)
        }
        _uiState.update {
            it.copy(
                mapping = mapper.config,
                manualOffset = mapper.manualOffset,
                wave = snapshot.wave.sanitized(),
            )
        }
        log(
            "mapping restored: safetyMax=${mapper.config.safetyMax} " +
                "manualOffset=${mapper.manualOffset} classBase=${mapper.config.classBase} " +
                "wave=${snapshot.wave.shape.id}/${snapshot.wave.frequencyHz}Hz/${snapshot.wave.depthPercent}%",
        )
    }

    /**
     * 急停锁存持久化：进程被杀/重启后必须恢复锁存，不能因重建而“自动复位”。
     * 写入失败只记录日志（不能因为持久化失败就放行控制）。
     */
    private fun persistEstopLatch(latched: Boolean) {
        runCatching {
            prefs().edit().putBoolean(PREF_ESTOP_LATCHED, latched).apply()
        }.onFailure { e ->
            log("! estop latch persist failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 服务进程创建时恢复急停锁存；后续引擎启动会再次把控制器置回锁存态。 */
    private fun restoreEstopLatch() {
        val latched = runCatching { prefs().getBoolean(PREF_ESTOP_LATCHED, false) }.getOrDefault(false)
        if (!latched) return
        _uiState.update {
            it.copy(
                emergencyStopped = true,
                estopDeviceStopUnverified = true,
                estopResetPhase = EstopResetPhase.NONE,
                estopResetChecks = emptyList(),
                estopResetFailure = null,
            )
        }
        log("E-STOP latch restored from prefs：需显式复位流程才能恢复控制（不自动启动）")
    }

    /**
     * 映射/波形持久化的**唯一入口**（审计 S6）：总是从 mapper/waveEngine 读最新快照再写，
     * 配置变更与"到期自动关闭"并发时不会整份 JSON 读-改-写互覆；写入单锁串行，失败打日志不吞。
     */
    private fun persistMappingState() {
        val snapshot = synchronized(mapperLock) {
            MappingSnapshot(mapper.config, mapper.manualOffset, waveEngine.config)
        }
        synchronized(persistLock) {
            runCatching {
                prefs().edit()
                    .putString(PREF_MAPPING_JSON, MappingJson.encode(snapshot))
                    .apply()
            }.onFailure { e ->
                log("! mapping_json persist failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun buildQrPayload(wsUrl: String): String {
        if (wsUrl.isBlank()) return ""
        val encoded = URLEncoder.encode(wsUrl, "UTF-8")
        return "https://dungeon-lab.cn/s/?v=1&action=socket&url=$encoded"
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ───────────────────────── 通知（节流） ─────────────────────────

    private fun startForegroundSafe(host: String, port: Int) {
        val text = "中继 $host:$port · 等待 DG-LAB APP"
        val notification = buildNotification(text)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        foregroundStarted = true
        lastNotificationText = text
        lastNotificationAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * 刷新前台通知。onLinkState 可能高达 ~10Hz（V4 tick 发布状态），这里做节流：
     *  - force=true（arm/disarm/estop/启停）：立即发；
     *  - 文案没变：不重复 notify；
     *  - 文案变了但距上次 < [NOTIFICATION_MIN_INTERVAL_MS]：跳过（下一次状态变化/ tick 再发）。
     */
    private fun refreshNotification(force: Boolean = false) {
        if (!foregroundStarted) return
        val st = _uiState.value
        val text = buildString {
            append(if (st.appConnected) "APP 已连接" else "等待 APP")
            append(" · 强度 ").append(st.currentIntensity)
            if (st.recognitionPaused) append(" · 识别暂停")
            if (st.emergencyStopped) append(" · 急停锁存")
            append(if (st.manualOverrideActive) " · 人工接管" else " · 自动")
            val cls = MotionClass.of(st.effectiveClass)
            if (cls != null) append(" · ").append(cls.labelCn)
        }
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (text == lastNotificationText) return
            if (now - lastNotificationAtMs < NOTIFICATION_MIN_INTERVAL_MS) return
        }
        lastNotificationText = text
        lastNotificationAtMs = now
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MotionController")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pending)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "运动控制服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "本地 V4 中继 + 运动识别" }
        nm.createNotificationChannel(channel)
    }

    // ───────────────────────── 生命周期收尾 ─────────────────────────

    private suspend fun stopEngineAwait() {
        generationCounter.incrementAndGet()
        desiredEngine = null
        reconcileEngines()
    }

    private fun shutdown() {
        scope.launch {
            runCatching { withTimeout(SHUTDOWN_TIMEOUT_MS) { stopEngineAwait() } }
            foregroundStarted = false
            runCatching {
                ServiceCompat.stopForeground(this@ControllerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
        }
    }
}
