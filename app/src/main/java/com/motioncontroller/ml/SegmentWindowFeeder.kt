package com.motioncontroller.ml

/**
 * 「重采样段号变化 → 滑动窗口复位」胶水（纯 Kotlin，无 Android 依赖，可 JVM 直接构造）。
 *
 * 端上流式管线的关键不变量：>200ms 停顿会被 [StreamingResampler] 切成新段，而滑窗
 * （[SlidingWindowBuffer]）不能跨段切窗——否则窗口会横跨中断段，产生无意义特征，且窗口内
 * 频域起点不再对齐「段内绝对帧号 % spectralStride == 0」网格。与离线
 * `Motion-ml/dataset/session_dataset.py::_sliding_windows_by_segment` 的分段语义一致。
 *
 * 顺序契约（与抽取前 `MotionPipeline.handleSample` 逐行等价）：
 *  1. 先把原始采样交给 [StreamingResampler.accept]；
 *  2. 若 [segmentIndex] 发生变化且滑窗 `totalFrames > 0`，**先**复位滑窗；
 *  3. 复位之后才由调用方把本次新段帧交给 [push]（reset 后第 0 行 = 新段首帧）。
 *
 * 本类**不做同步**：调用方须在自己的锁内串行访问（端上是 `MotionPipeline.bufferLock`）。
 */
class SegmentWindowFeeder(
    private val resampler: StreamingResampler = StreamingResampler(),
    private val windowBuffer: SlidingWindowBuffer = SlidingWindowBuffer(),
) {

    /** 当前连续段编号（>200ms 间隔时 +1）。 */
    val segmentIndex: Long get() = resampler.segmentIndex

    /** 当前段内累计送入滑窗的 50Hz 帧数。 */
    val totalFrames: Long get() = windowBuffer.totalFrames

    /** 对外展示的缓冲进度（0..windowSize）。 */
    val bufferedFrames: Int get() = windowBuffer.bufferedFrames

    /** 当前缓冲的真实帧数（可能大于 windowSize）。 */
    val bufferedRawFrames: Int get() = windowBuffer.bufferedRawFrames

    /**
     * 最近一次 [accept] 是否因断段复位了滑窗缓冲（仅用于诊断日志）。
     * 不变量：为 true 时 `windowBuffer.totalFrames == 0`，且发生在本次返回的帧被 [push] 之前。
     */
    var lastAcceptResetWindow: Boolean = false
        private set

    /**
     * 送入一个原始采样：先重采样，再按需复位滑窗（见类注释的顺序契约）。
     *
     * @return 本次产生的 50Hz 帧（顺序不变）；断段时最多 1 帧且为新段首帧。
     * @throws Exception 由 [StreamingResampler.accept] 抛出的异常原样上抛（调用方负责记日志），
     *   此时滑窗保持未复位状态。
     */
    fun accept(timeMs: Double, values: DoubleArray): List<TimedFrame> {
        val segmentBefore = resampler.segmentIndex
        lastAcceptResetWindow = false
        val frames = resampler.accept(timeMs, values)
        if (resampler.segmentIndex != segmentBefore && windowBuffer.totalFrames > 0L) {
            // 与离线分段语义一致：>200ms 停顿后不跨段切窗，从新段重新攒满一个窗口。
            // 必须在 push 新段帧之前 reset，否则新段首帧会和新旧窗口混在一起。
            windowBuffer.reset()
            lastAcceptResetWindow = true
        }
        return frames
    }

    /**
     * 送入一个已重采样帧。
     *
     * @return 命中 stride 时返回需要计算特征的最近若干帧（末尾 = 最新帧），否则 null。
     */
    fun push(frame: TimedFrame): Array<DoubleArray>? = windowBuffer.push(frame.values)

    /** 清空重采样器与滑窗（start/stop/急停复位路径）。 */
    fun reset() {
        resampler.reset()
        windowBuffer.reset()
        lastAcceptResetWindow = false
    }
}
