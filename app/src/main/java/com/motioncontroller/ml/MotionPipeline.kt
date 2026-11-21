package com.motioncontroller.ml

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.motioncontroller.sensor.GnssSpeedProvider
import com.motioncontroller.sensor.ImuCollector
import com.motioncontroller.sensor.ImuSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 端侧运动识别管线实现：
 *
 * ```
 * IMU(acc/gyro/rotation) + GNSS speed
 *   → 50Hz 流式重采样（[StreamingResampler]，>200ms 断段不插值）
 *   → 滑窗缓冲（[SlidingWindowBuffer]，150 帧窗口 / 50 帧步长，保留 250 帧历史）
 *   → 24 维特征（[FeatureExtractor]）→ StandardScaler（[StandardScaler]）
 *   → ONNX（[MotionClassifier]）→ softmax → [MotionPrediction]
 * ```
 *
 * 线程模型（2026-10-31 audit 调整）：
 *  - 传感器回调在各自的 HandlerThread 上（IMU / GNSS 各一条），回调里只做重采样 + 入缓冲（O(1)），
 *    **不占主线程**；
 *  - 特征 + 推理在**专用单线程**上串行执行（避免 ORT session 并发）；
 *  - 推理任务只保留最新一份（[CoalescingLatestQueue]，深度 1，drop oldest）：单次特征+ORT 超过
 *    1s 时不会堆积过期窗口；
 *  - [MotionPipelineApi.start] 的 `onResult` 在后台线程回调，调用方自行切回主线程；
 *  - 模型加载失败 / scaler 缺失都不会崩：状态里报 `lastError` / `scalerValid=false`。
 *
 * 模型生命周期：ONNX session 与 scaler 走**进程级缓存**（[classifierCache]/[scalerCache]），
 * 反复 startEngine/stopEngine 不再重建 + 重新 warm-up；缓存拥有 native session 生命周期，
 * [stop] 不会关闭它（下次启动复用），只有竞态下未被缓存接管的 session 才会在推理线程终止后
 * 兜底关闭一次（[MotionClassifier.close] 幂等）。
 *
 * 与离线训练管线的一致性：
 *  - 重采样逐帧等价 `session_dataset.py::resample_frame`；
 *  - 特征提取直接复用 24 维参考实现（golden vector 校验，误差 <1e-5）；
 *  - 窗口计算时多带最多 250 帧历史，保证窗口前 9 行的滚动统计和频域窗口起点
 *    与"整段离线计算后再切窗"尽量一致；[SlidingWindowBuffer.requireGridCompatible]
 *    在启动时校验 150/50/250 对频域步长 10 的整除不变量（audit HIGH）。
 */
class MotionPipeline(private val context: Context) : MotionPipelineApi {

    companion object {
        private const val TAG = "ML"

        /** 目标采样率（Hz），必须与训练一致。 */
        const val TARGET_HZ = Resampler.TARGET_HZ

        /** 断段阈值（ms）。 */
        const val MAX_GAP_MS = Resampler.MAX_GAP_MS

        /** 模型窗口帧数（3s @ 50Hz）。 */
        const val WINDOW_SIZE = SlidingWindowBuffer.DEFAULT_WINDOW_SIZE

        /** 推理步长（帧）。 */
        const val STRIDE = SlidingWindowBuffer.DEFAULT_STRIDE

        /** 窗口计算时保留的历史帧数。 */
        const val HISTORY_FRAMES = SlidingWindowBuffer.DEFAULT_HISTORY_FRAMES

        /** 停止时等待推理线程收敛的最长时间（ms）。 */
        private const val STOP_TIMEOUT_MS = 300L

        /** bufferedFrames 状态上报间隔（帧）：50Hz 采样 → 10 帧 ≈ 5Hz 更新。 */
        private const val STATUS_FRAME_STEP = 10

        /**
         * 进程级 scaler 缓存（assets 静态）：避免每次 startEngine 重新读 assets + 解析 JSON。
         * 解析失败同样缓存（见 [ProcessModelCache]），不会每次启动重复付出失败成本。
         */
        private val scalerCache = ProcessModelCache<StandardScaler>("scaler.json")

        /**
         * 进程级 ONNX session 缓存：避免每次 startEngine 重建 session + 重新 warm-up
         * （XNNPACK 失败的机型上旧实现每次都要跑一遍 XNNPACK 失败 + CPU 回退）。
         */
        private val classifierCache = ProcessModelCache<MotionClassifier>("motion_encoder.onnx") { clf ->
            clf.close()
        }

        /**
         * 关闭并清空进程级模型缓存（幂等）。正常 service 生命周期不需要调用；保留给
         * 测试/以后需要显式释放 native 资源的场景（如进入后台低内存模式）。
         */
        fun clearModelCaches() {
            classifierCache.close()
            scalerCache.close()
        }
    }

    private val _status = MutableStateFlow(MotionPipelineStatus())
    override val status: StateFlow<MotionPipelineStatus> = _status

    private val imuCollector = ImuCollector(context)
    private val gnssProvider = GnssSpeedProvider(context)

    /** 重采样 + 滑窗 + 「断段→窗口复位」胶水；resampler/windowBuffer 由它持有（见 [SegmentWindowFeeder]）。 */
    private val feeder = SegmentWindowFeeder(
        resampler = StreamingResampler(TARGET_HZ, MAX_GAP_MS),
        windowBuffer = SlidingWindowBuffer(WINDOW_SIZE, STRIDE, HISTORY_FRAMES),
    )
    private val featureExtractor = FeatureExtractor()

    /** 深度 1 的合并队列：特征+ORT 超过 1s 时只保留最新窗口，不堆积过期任务。 */
    private val inferenceQueue = CoalescingLatestQueue<InferenceJob>()

    init {
        // 50Hz 网格不变量：history/stride/window 必须是频域步长的整数倍，否则窗口内的
        // FFT 起点会漂移出 preprocess.py 的「绝对帧号 % 10 == 0」网格（audit HIGH）。
        SlidingWindowBuffer.requireGridCompatible(
            windowSize = WINDOW_SIZE,
            stride = STRIDE,
            historyFrames = HISTORY_FRAMES,
            spectralStride = FeatureExtractor.DEFAULT_SPECTRAL_STRIDE,
            spectralWindow = FeatureExtractor.DEFAULT_SPECTRAL_WINDOW,
        )
    }

    /** 保护 feeder/latestWindowEndElapsedNanos（传感器线程 vs 推理线程）。 */
    private val bufferLock = Any()

    private var executor: ExecutorService? = null

    @Volatile
    private var classifier: MotionClassifier? = null

    @Volatile
    private var scaler: StandardScaler = StandardScaler.identity()

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var onResult: ((MotionPrediction) -> Unit)? = null

    /** 时间轴原点（ns）。SensorEvent.timestamp 与 SystemClock.elapsedRealtimeNanos 同基准。 */
    @Volatile
    private var originNanos: Long = 0L

    /** 最新一个 50Hz 重采样帧的**绝对**单调时钟时间（ns，自 boot）；用于给预测打窗口末端时间戳。 */
    @Volatile
    private var latestWindowEndElapsedNanos: Long = 0L

    /** 上次上报的 bufferedFrames（节流用）。 */
    @Volatile
    private var lastReportedBufferedFrames: Int = 0

    /** 一次待推理的窗口任务：rows 是最近 ≤250 帧，windowEnd 为该窗口最后一帧的传感器时间。 */
    private class InferenceJob(
        val rows: Array<DoubleArray>,
        val windowEndElapsedNanos: Long,
    )

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun start(onResult: (MotionPrediction) -> Unit) {
        if (running) stop()

        this.onResult = onResult
        originNanos = SystemClock.elapsedRealtimeNanos()
        inferenceQueue.clear()
        lastReportedBufferedFrames = 0
        synchronized(bufferLock) {
            feeder.reset()
            latestWindowEndElapsedNanos = 0L
        }

        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ML-inference").apply {
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
            }
        }
        executor = exec
        running = true

        _status.update {
            it.copy(
                running = true,
                modelLoaded = false,
                bufferedFrames = 0,
                lastInferenceMs = 0,
                lastError = null,
                gpsAvailable = false,
                gpsSpeedKmh = 0f,
                gpsFixAgeMs = -1L,
                gpsEverFix = false,
            )
        }

        // 模型/scaler 加载在同一线程队列的最前面（进程级缓存命中时几乎零成本），
        // 后续窗口任务自然排在它之后
        exec.execute {
            runCatching { loadAssets() }.onFailure { t ->
                Log.w(TAG, "loadAssets failed: ${t.javaClass.simpleName}: ${t.message}")
                _status.update { s ->
                    s.copy(lastError = "模型/scaler 加载异常: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }

        gnssProvider.onSpeedUpdated = { snapshot ->
            _status.update {
                it.copy(
                    gpsAvailable = snapshot.available,
                    gpsSpeedKmh = snapshot.speedKmh.toFloat(),
                    gpsFixAgeMs = snapshot.fixAgeMs,
                    gpsEverFix = snapshot.everFix,
                )
            }
        }
        gnssProvider.start()
        val gnssSnapshot = gnssProvider.snapshot()
        _status.update {
            it.copy(
                gpsAvailable = gnssSnapshot.available,
                gpsSpeedKmh = gnssSnapshot.speedKmh.toFloat(),
                gpsFixAgeMs = gnssSnapshot.fixAgeMs,
                gpsEverFix = gnssSnapshot.everFix,
            )
        }

        imuCollector.onSample = ::handleSample
        imuCollector.start()
        val gyroOk = imuCollector.hasGyroscope
        val rotationOk = imuCollector.hasRotationVector
        if (!imuCollector.hasAccelerometer) {
            _status.update { it.copy(lastError = "设备没有加速度计，IMU 管线不可用") }
        } else if (!gyroOk || !rotationOk) {
            Log.w(TAG, "degraded IMU: gyro=$gyroOk rotation=$rotationOk (features will be less accurate)")
            _status.update {
                it.copy(
                    gyroAvailable = gyroOk,
                    rotationAvailable = rotationOk,
                    lastError = when {
                        !gyroOk && !rotationOk -> "设备缺少陀螺仪与旋转矢量传感器，特征退化"
                        !gyroOk -> "设备缺少陀螺仪，陀螺特征退化"
                        else -> "设备缺少旋转矢量传感器，姿态特征退化"
                    },
                )
            }
        }
        _status.update { it.copy(gyroAvailable = gyroOk, rotationAvailable = rotationOk) }
        Log.i(
            TAG,
            "pipeline started (acc=${imuCollector.hasAccelerometer} gyro=$gyroOk rot=$rotationOk " +
                "gnss=${gnssProvider.isAvailable()})",
        )
    }

    override fun stop() {
        if (!running && executor == null) {
            _status.update { it.copy(running = false) }
            return
        }
        running = false
        imuCollector.onSample = null
        runCatching { imuCollector.stop() }
        gnssProvider.onSpeedUpdated = null
        runCatching { gnssProvider.stop() }
        onResult = null

        // 先 shutdown + awaitTermination，再 shutdownNow；确保没有 in-flight 推理继续访问 session。
        val exec = executor
        executor = null
        if (exec != null) {
            runCatching { exec.shutdown() }
            runCatching { exec.awaitTermination(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            runCatching { exec.shutdownNow() }
            runCatching { exec.awaitTermination(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
        inferenceQueue.clear()
        // 复位 drain 调度标记：shutdownNow 可能丢弃排队中的 drain 任务，留着会让下次 start 的
        // offer(返回 false) 以为已有 drain 在跑，从而永远不再处理任务。
        while (!inferenceQueue.exitDrain()) inferenceQueue.clear()

        // 进程级缓存拥有 model 生命周期：正常路径 stop() 不关闭 session（下次 startEngine 复用，
        // 避免重建 + 重新 warm-up）。仅当本实例持有的 classifier 因竞态未被缓存接管（例如缓存被
        // 显式清理）时，才在推理线程终止后兜底关闭一次；close() 幂等，不会二次释放。
        val clf = classifier
        classifier = null
        if (clf != null && !classifierCache.owns(clf)) {
            runCatching { clf.close() }
        }

        synchronized(bufferLock) {
            feeder.reset()
            latestWindowEndElapsedNanos = 0L
        }
        lastReportedBufferedFrames = 0
        _status.update {
            it.copy(
                running = false,
                modelLoaded = false,
                bufferedFrames = 0,
                lastInferenceMs = 0,
                gpsAvailable = false,
                gpsSpeedKmh = 0f,
                gpsFixAgeMs = -1L,
                gpsEverFix = false,
            )
        }
        Log.i(TAG, "pipeline stopped")
    }

    override fun reset() {
        synchronized(bufferLock) {
            feeder.reset()
            latestWindowEndElapsedNanos = 0L
        }
        inferenceQueue.clear()
        lastReportedBufferedFrames = 0
        _status.update { it.copy(bufferedFrames = 0) }
        Log.i(TAG, "pipeline buffers reset")
    }

    // ───────────────────────── 采集 → 重采样 → 缓冲 ─────────────────────────

    /** 传感器回调（IMU HandlerThread）：重采样 + 入窗缓冲，命中 stride 时投递推理任务。 */
    private fun handleSample(sample: ImuSample) {
        if (!running) return
        val tMs = (sample.eventTimeNanos - originNanos) / 1_000_000.0
        val values = sample.toRawValues(gnssProvider.speedKmh)

        var readyRows: Array<DoubleArray>? = null
        var readyWindowEndNanos = 0L
        var buffered = 0
        synchronized(bufferLock) {
            val frames = try {
                feeder.accept(tMs, values)
            } catch (t: Throwable) {
                Log.w(TAG, "resample failed: ${t.message}")
                return
            }
            if (feeder.lastAcceptResetWindow) {
                // 与离线 `_sliding_windows_by_segment` 一致：>200ms 停顿后不跨段切窗，
                // 从新段重新攒满一个窗口（避免窗口横跨中断段产生无意义特征）。
                // reset 后 buffer 第 0 行 = 新段首帧，绝对帧号%10 对齐关系在新段内重新成立。
                Log.i(TAG, "IMU gap detected (segment ${feeder.segmentIndex}), window buffer reset")
            }
            for (frame in frames) {
                latestWindowEndElapsedNanos = originNanos + Math.round(frame.timeMs * 1_000_000.0)
                val rows = feeder.push(frame)
                if (rows != null) readyRows = rows
            }
            if (readyRows != null) readyWindowEndNanos = latestWindowEndElapsedNanos
            buffered = feeder.bufferedFrames
        }

        reportBufferedFrames(buffered)

        val rows = readyRows ?: return
        submitInference(rows, readyWindowEndNanos)
    }

    /** bufferedFrames 状态更新节流：每 [STATUS_FRAME_STEP] 帧（≈5Hz）或 0/满窗时上报。 */
    private fun reportBufferedFrames(buffered: Int) {
        val last = lastReportedBufferedFrames
        if (buffered == last) return
        if (buffered != 0 && buffered != WINDOW_SIZE && buffered - last < STATUS_FRAME_STEP) return
        lastReportedBufferedFrames = buffered
        _status.update { it.copy(bufferedFrames = buffered) }
    }

    private fun submitInference(rows: Array<DoubleArray>, windowEndElapsedNanos: Long) {
        if (!running) return
        val exec = executor ?: return
        // 只保留最新窗口：已有 drain 排队/运行时返回 false（drop oldest）
        if (!inferenceQueue.offer(InferenceJob(rows, windowEndElapsedNanos))) return
        try {
            exec.execute { drainInferenceQueue() }
        } catch (t: Throwable) {
            Log.w(TAG, "submit inference rejected: ${t.message}")
        }
    }

    /** 推理线程上的 drain 循环：持续取「最新」任务，直到队列为空才退出。 */
    private fun drainInferenceQueue() {
        while (true) {
            val job = inferenceQueue.poll()
            if (job == null) {
                if (inferenceQueue.exitDrain()) return
            } else {
                runInference(job)
            }
        }
    }

    // ───────────────────────── 特征 → 标准化 → 推理 ─────────────────────────

    /**
     * 最近 [WINDOW_SIZE] 帧的运动能量：(陀螺仪模长均值 rad/s, 加速度模长标准差 m/s²)。
     *
     * 训练数据实测（10 个会话的离线窗口）：静止 gyro≈0.0006/acc_std≈0.004，
     * 汽车 gyro 中位数 0.079、骑行 0.34、步行 1.9；而火车（TRAIN）与静止一样安静
     * （gyro≈0.0014），所以能量只能用于「否定 CAR/CYCLING」，不能用于识别静止/火车。
     */
    private fun motionEnergy(rows: Array<DoubleArray>): Pair<Float, Float> {
        val start = (rows.size - WINDOW_SIZE).coerceAtLeast(0)
        var gyroSum = 0.0
        var accSum = 0.0
        var accSqSum = 0.0
        var n = 0
        for (i in start until rows.size) {
            val r = rows[i]
            if (r.size < 10) continue
            val gx = r[4]; val gy = r[5]; val gz = r[6]
            val ax = r[1]; val ay = r[2]; val az = r[3]
            gyroSum += kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
            val m = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
            accSum += m
            accSqSum += m * m
            n++
        }
        if (n == 0) return 1f to 1f
        val mean = accSum / n
        val variance = (accSqSum / n - mean * mean).coerceAtLeast(0.0)
        return (gyroSum / n).toFloat() to kotlin.math.sqrt(variance).toFloat()
    }

    private fun runInference(job: InferenceJob) {
        if (!running) return
        val rows = job.rows
        if (rows.size < WINDOW_SIZE) return
        val clf = classifier ?: run {
            // 模型仍在加载或已失败：加载失败原因已经写进 status.lastError
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            // 1) 对 [历史 + 窗口] 全部帧提取 24 维特征（滚动/频域需要历史）
            val allFeatures = featureExtractor.transform(rows)
            val offset = allFeatures.size - WINDOW_SIZE
            val window = Array(WINDOW_SIZE) { allFeatures[offset + it] }

            // 2) StandardScaler
            val sc = scaler
            val standardized = Array(WINDOW_SIZE) { sc.transform(window[it]) }

            // 3) 非有限值闸门（audit #2）：绝不能把 NaN/Inf 喂给 ORT。旧实现会让 Softmax
            //    兜底成均匀分布 → argmax=0(STATIONARY)，把数据错误伪装成"静止"。
            val bad = FiniteGate.firstNonFinite(standardized)
            if (bad != null) {
                Log.w(TAG, "non-finite standardized feature at row=${bad.first} col=${bad.second}, skip inference")
                _status.update { s ->
                    s.copy(lastError = "特征含非有限值（row=${bad.first}, col=${bad.second}），已跳过本次推理")
                }
                return
            }

            // 4) ONNX → logits → softmax
            val probs = clf.predict(standardized)
            if (FiniteGate.containsNonFinite(probs)) {
                Log.w(TAG, "non-finite model output, skip inference")
                _status.update { s -> s.copy(lastError = "模型输出含非有限值，已跳过本次推理") }
                return
            }
            val classIndex = Softmax.argmax(probs)
            // 5) 运动能量（用于映射层的「矛盾消除」：CAR/CYCLING 不可能在几乎静止的窗口出现）
            val (gyroMean, accStd) = motionEnergy(rows)
            val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).toInt()

            _status.update { it.copy(lastInferenceMs = elapsedMs) }
            // 时间戳 = 窗口末端的**传感器时间**（elapsedRealtime 基准，ms），不是回调墙钟：
            // 回调可能排队延迟，用墙钟会不断把窗口"变新"，映射层/日志看到的时间轴失真。
            val timestampMs = if (job.windowEndElapsedNanos > 0L) {
                job.windowEndElapsedNanos / 1_000_000L
            } else {
                SystemClock.elapsedRealtime()
            }
            val callback = onResult
            if (callback != null && running) {
                callback(
                    MotionPrediction(
                        probs = probs,
                        classIndex = classIndex,
                        timestampMs = timestampMs,
                        gyroMean = gyroMean,
                        accStd = accStd,
                    ),
                )
            }
            Log.i(
                TAG,
                "inference ${elapsedMs}ms class=${MotionClass.of(classIndex)?.name} " +
                    "conf=${probs.getOrElse(classIndex) { 0f }} gyro=${"%.4f".format(gyroMean)} " +
                    "accStd=${"%.4f".format(accStd)}",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "inference failed: ${t.javaClass.simpleName}: ${t.message}")
            _status.update { s ->
                s.copy(lastError = "inference failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    // ───────────────────────── assets ─────────────────────────

    /** 在推理线程上通过进程级缓存加载 scaler.json 与 onnx 模型（失败只记录，不抛）。 */
    private fun loadAssets() {
        // scaler
        val sc = scalerCache.getOrLoad { loadScaler(context) }
        if (sc == null) {
            scaler = StandardScaler.identity()
            val reason = scalerCache.lastFailure
            _status.update {
                it.copy(
                    scalerValid = false,
                    lastError = "scaler.json 缺失或损坏，使用恒等标准化（推理不可信）: ${reason ?: "unknown"}",
                )
            }
        } else {
            scaler = sc
            _status.update { it.copy(scalerValid = sc.valid) }
            if (!sc.valid) {
                Log.w(TAG, "scaler.json is a placeholder (valid=false): predictions are NOT trustworthy")
            }
        }
        if (!running) return

        // model
        val clf = classifierCache.getOrLoad { loadClassifier(context) }
        if (clf != null) {
            if (!running) return
            classifier = clf
            _status.update { it.copy(modelLoaded = true) }
            Log.i(
                TAG,
                "model loaded (${MotionClassifier.ASSET_NAME}) xnnpack=${clf.usedXnnpack}" +
                    if (clf.usedXnnpack) "" else " (CPU fallback: XNNPACK warm-up failed)",
            )
        } else {
            Log.w(TAG, "model load failed: ${classifierCache.lastFailure}")
            _status.update { s ->
                s.copy(
                    modelLoaded = false,
                    lastError = "模型加载失败: ${classifierCache.lastFailure ?: "unknown"}",
                )
            }
        }
    }

    /** 读 assets + 解析 scaler.json；异常信息会由 [ProcessModelCache] 缓存并展示到 status。 */
    private fun loadScaler(ctx: Context): StandardScaler {
        val text = ctx.assets.open(StandardScaler.ASSET_NAME)
            .bufferedReader()
            .use { it.readText() }
        return StandardScaler.parse(text)
    }

    /** 读 assets + 构建（XNNPACK 优先、CPU 回退）的 ONNX session。 */
    private fun loadClassifier(ctx: Context): MotionClassifier {
        val bytes = ctx.assets.open(MotionClassifier.ASSET_NAME)
            .use { it.readBytes() }
        return MotionClassifier.fromBytes(bytes)
    }

    // ───────────────────────── 只读状态（调试/测试用） ─────────────────────────

    /** 当前缓冲的真实帧数（可能大于一个窗口）。 */
    val bufferedRawFrames: Int
        get() = synchronized(bufferLock) { feeder.bufferedRawFrames }
}
