package com.motioncontroller.ml

/**
 * 滑窗缓冲（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 维护最近 [historyFrames] 帧 50Hz 原始特征。每新增 [stride] 帧且缓冲 ≥ [windowSize] 帧时，
 * 回调一个「计算窗口」：返回最近 `min(缓冲帧数, historyFrames)` 帧。
 *
 * 为什么返回的可能多于 [windowSize]：
 *  - 滚动统计（10 帧窗）需要窗口前 9 帧历史，否则窗口前 9 行的均值/标准差会与离线不一致；
 *  - 频域（100 帧窗 + 每 10 帧一次再插值）需要窗口前的历史帧才能对齐到"绝对帧号 % 10 == 0"的窗口起点。
 * 调用方对返回的所有帧做 [FeatureExtractor.transform]，再取**最后 [windowSize] 行**作为模型输入。
 */
class SlidingWindowBuffer(
    val windowSize: Int = DEFAULT_WINDOW_SIZE,
    val stride: Int = DEFAULT_STRIDE,
    /** 保留的历史帧上限（含窗口本身）。 */
    val historyFrames: Int = DEFAULT_HISTORY_FRAMES,
) {

    init {
        require(windowSize > 0) { "windowSize 必须 > 0，收到 $windowSize" }
        require(stride > 0) { "stride 必须 > 0，收到 $stride" }
        require(historyFrames >= windowSize) {
            "historyFrames($historyFrames) 不能小于 windowSize($windowSize)"
        }
    }

    private val frames = ArrayDeque<DoubleArray>()

    /** 自上次产出窗口以来新增的帧数。 */
    var framesSinceLastWindow: Int = 0
        private set

    /** 累计送入的帧数（绝对帧号）。 */
    var totalFrames: Long = 0L
        private set

    /** 当前缓冲帧数（真实值，可能大于 [windowSize]）。 */
    val bufferedRawFrames: Int get() = frames.size

    /** 对外展示的缓冲进度（0..windowSize），与 `MotionPipelineStatus.bufferedFrames` 一致。 */
    val bufferedFrames: Int get() = if (frames.size < windowSize) frames.size else windowSize

    /** 是否已攒够一个窗口。 */
    val hasFullWindow: Boolean get() = frames.size >= windowSize

    /**
     * 送入一帧原始 10 维数据（会被复制保存）。
     *
     * @return 需要做特征计算+推理时返回最近的若干帧（末尾 = 最新帧），否则返回 null。
     */
    fun push(values: DoubleArray): Array<DoubleArray>? {
        frames.addLast(values.copyOf())
        while (frames.size > historyFrames) frames.removeFirst()
        totalFrames++
        framesSinceLastWindow++

        if (frames.size < windowSize) return null
        if (framesSinceLastWindow < stride) return null

        framesSinceLastWindow = 0
        return Array(frames.size) { frames[it] }
    }

    /** 清空缓冲（急停后恢复 / stop 时调用）。 */
    fun reset() {
        frames.clear()
        framesSinceLastWindow = 0
        totalFrames = 0L
    }

    companion object {
        /** 模型窗口帧数（3s @ 50Hz）。 */
        const val DEFAULT_WINDOW_SIZE = 150

        /** 推理步长（1s @ 50Hz）。 */
        const val DEFAULT_STRIDE = 50

        /**
         * 计算历史帧上限：150 窗口 + 9 帧滚动历史 + 频域对齐余量（10 帧级）。
         * 取 250 帧（5s）足够覆盖 100 帧频域窗与滚动历史的双重需求。
         */
        const val DEFAULT_HISTORY_FRAMES = 250

        /**
         * 校验 50Hz 网格对齐不变量（2026-10-31 audit HIGH）。
         *
         * 为什么需要：`FeatureExtractor` 的频域窗口起点固定为 `0, 10, 20, ...`（相对传入矩阵的
         * 第 0 行）。端上为了无 lookahead，只把「最近 ≤historyFrames 帧」交给特征提取，再取最后
         * `windowSize` 行。要让窗口内的 FFT 起点与整段离线计算时一致，必须保证**传入矩阵的第 0 行
         * 对应绝对帧号 % spectralStride == 0**：
         *  - 首个窗口：`bufferStart = 0`（绝对帧号 0）；
         *  - 之后：`bufferStart = totalFrames - frames.size`；缓冲未满时 = 0；满后
         *    `= windowSize + k*stride - historyFrames`。
         * 因此要求 `windowSize % spectralStride == 0`、`stride % spectralStride == 0`、
         * `historyFrames % spectralStride == 0`（当前 150/50/250 对 10 全部整除）。
         *
         * @throws IllegalArgumentException 任一不变量被破坏（配置错误应尽早失败，而不是静默掉准确率）
         */
        fun requireGridCompatible(
            windowSize: Int = DEFAULT_WINDOW_SIZE,
            stride: Int = DEFAULT_STRIDE,
            historyFrames: Int = DEFAULT_HISTORY_FRAMES,
            spectralStride: Int = FeatureExtractor.DEFAULT_SPECTRAL_STRIDE,
            spectralWindow: Int = FeatureExtractor.DEFAULT_SPECTRAL_WINDOW,
        ) {
            require(windowSize > 0) { "windowSize 必须 > 0，收到 $windowSize" }
            require(stride > 0) { "stride 必须 > 0，收到 $stride" }
            require(historyFrames >= windowSize) {
                "historyFrames($historyFrames) 不能小于 windowSize($windowSize)"
            }
            require(spectralStride > 0) { "spectralStride 必须 > 0，收到 $spectralStride" }
            require(windowSize % spectralStride == 0) {
                "windowSize($windowSize) 必须是 spectralStride($spectralStride) 的整数倍：" +
                    "否则窗口起点会落在非 FFT 网格上"
            }
            require(stride % spectralStride == 0) {
                "stride($stride) 必须是 spectralStride($spectralStride) 的整数倍：" +
                    "否则 bufferStart 会漂移出 50Hz 频域网格"
            }
            require(historyFrames % spectralStride == 0) {
                "historyFrames($historyFrames) 必须是 spectralStride($spectralStride) 的整数倍：" +
                    "否则历史窗口第 0 行对应的绝对帧号不是网格起点"
            }
            require(windowSize >= spectralWindow) {
                "windowSize($windowSize) 不能小于 spectralWindow($spectralWindow)：模型窗口内至少要有一次完整 FFT"
            }
        }
    }
}
