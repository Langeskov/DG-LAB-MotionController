package com.motioncontroller.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ONNX 运动分类器（Android，ONNX Runtime）。
 *
 * 模型契约（AGENTS.md 4.4）：
 *  - 输入 `input`: float32 `[batch, 150, 24]`（已标准化）
 *  - 输出 `output`: float32 `[batch, 5]`（logits）
 *
 * 执行提供策略（2026-10-31 真机事故后调整）：
 *  1. 优先使用 **XNNPACK**（低延迟）；创建后立即用全零输入做一次 warm-up 推理。
 *  2. 若 XNNPACK session 创建或 warm-up 失败（已知：某些 ORT 版本 + 动态 shape 的
 *     Transformer 模型会在 `self_attn/Reshape` 报 `input_shape_size == requested_shape_size`），
 *     关闭该 session，**回退纯 CPU EP** 重建并再次 warm-up。
 *  3. 两者都失败才抛异常（由 MotionPipeline 降级显示错误，不崩 App）。
 *
 * 性能（2026-10-31 audit）：
 *  - 输入 `FloatArray` / direct `FloatBuffer` / `OnnxTensor` 全部复用：每个窗口不再分配
 *    150×24 的中间对象，也不再重建 native tensor（direct buffer 零拷贝）；
 *  - CPU 回退的 intra-op 线程数与 XNNPACK 的 `intra_op_num_threads` 对齐（[INTRA_OP_THREADS]）；
 *  - [close] 幂等（AtomicBoolean 守卫），重复调用不会二次释放 native 资源。
 *
 * 线程：session 不支持并发推理；[predict] 只应由单线程（MotionPipeline 的推理线程）调用。
 */
class MotionClassifier private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String,
    /** 该 session 是否成功启用了 XNNPACK（false = 纯 CPU EP 回退）。 */
    val usedXnnpack: Boolean,
) {

    private val inputShape = longArrayOf(1L, WINDOW_SIZE.toLong(), FEATURE_DIM.toLong())

    /** 复用的输入展平数组（按行主序写入）。 */
    private val flat = FloatArray(WINDOW_SIZE * FEATURE_DIM)

    /** 复用的 direct 输入缓冲：ORT 对 direct buffer 零拷贝，复用 tensor 时数据更新即可生效。 */
    private val inputBuffer: FloatBuffer = ByteBuffer.allocateDirect(flat.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    @Volatile
    private var inputTensor: OnnxTensor? = null

    /** 复用的 logits 读出缓冲（输出维度 5）。 */
    private val logits = FloatArray(CLASS_COUNT)

    private val closeGuard = AtomicBoolean(false)

    /** native session 是否已关闭（close 幂等）。 */
    val closed: Boolean get() = closeGuard.get()

    /**
     * 对 [WINDOW_SIZE] 帧 × [FEATURE_DIM] 维特征做一次推理，返回 softmax 后的 5 类概率。
     *
     * @throws IllegalArgumentException 输入形状不符
     */
    fun predict(window: Array<DoubleArray>): FloatArray {
        require(window.size == WINDOW_SIZE) {
            "窗口帧数必须是 $WINDOW_SIZE，收到 ${window.size}"
        }
        var idx = 0
        for (i in 0 until WINDOW_SIZE) {
            val row = window[i]
            require(row.size == FEATURE_DIM) {
                "第 $i 帧特征维度必须是 $FEATURE_DIM，收到 ${row.size}"
            }
            for (d in 0 until FEATURE_DIM) flat[idx++] = row[d].toFloat()
        }
        return runFlat()
    }

    /** 把 [flat] 写入复用的 direct 输入缓冲（position 归零）。 */
    private fun fillInputBuffer() {
        inputBuffer.clear()
        inputBuffer.put(flat)
        inputBuffer.rewind()
    }

    /** 首次推理时创建输入 tensor；之后复用同一个 tensor（数据由 [fillInputBuffer] 更新）。 */
    private fun obtainInputTensor(): OnnxTensor {
        inputTensor?.let { return it }
        return OnnxTensor.createTensor(env, inputBuffer, inputShape).also { inputTensor = it }
    }

    private fun runFlat(): FloatArray {
        fillInputBuffer()
        val tensor = obtainInputTensor()
        var result: OrtSession.Result? = null
        try {
            result = session.run(mapOf(inputName to tensor))
            val out = result.get(0) as OnnxTensor
            val buffer = out.floatBuffer
            val count = buffer.remaining()
            val readInto = if (count == logits.size) logits else FloatArray(count)
            buffer.get(readInto)
            return Softmax.apply(readInto)
        } finally {
            runCatching { result?.close() }
        }
    }

    /** 用全零输入做一次推理，验证 session 在当前设备/EP 上真的能跑通。 */
    private fun warmUp() {
        flat.fill(0f)
        runFlat()
    }

    /**
     * 释放该 session 持有的 native 资源（OrtEnvironment 是进程级单例，不在这里关闭）。
     * 幂等：重复调用只生效一次。
     */
    fun close() {
        if (!closeGuard.compareAndSet(false, true)) return
        runCatching { inputTensor?.close() }
        inputTensor = null
        runCatching { session.close() }
    }

    companion object {
        /** 模型在 assets 中的文件名。 */
        const val ASSET_NAME = "motion_encoder.onnx"

        /** 输入张量名（模型契约）。 */
        const val INPUT_NAME = "input"

        /** 输出张量名（模型契约）。 */
        const val OUTPUT_NAME = "output"

        /** 窗口帧数（150 帧 = 3s @ 50Hz）。 */
        const val WINDOW_SIZE = SlidingWindowBuffer.DEFAULT_WINDOW_SIZE

        /** 特征维度（24）。 */
        const val FEATURE_DIM = FeatureExtractor.FEATURE_DIM

        /** 类别数（STATIONARY/WALKING/CYCLING/CAR/TRAIN）。 */
        const val CLASS_COUNT = 5

        /**
         * ORT 线程数：XNNPACK 与纯 CPU 回退使用同一个值，避免两条路径性能差异过大。
         * 手机端 2 线程足够（单次推理 2~20ms），再高只会和传感器线程抢 CPU。
         */
        const val INTRA_OP_THREADS = 2

        /**
         * 从内存中的模型字节创建 session（XNNPACK 优先、失败自动回退 CPU）。
         *
         * @throws Exception 模型非法 / native 库不可用 / 两种 EP 都跑不通时抛出
         *         （调用方负责降级，不能崩 App）
         */
        fun fromBytes(modelBytes: ByteArray): MotionClassifier {
            require(modelBytes.isNotEmpty()) { "模型字节为空" }
            val env = OrtEnvironment.getEnvironment()

            // 1) 优先 XNNPACK：创建 + warm-up
            var accelerated: MotionClassifier? = null
            try {
                accelerated = build(env, modelBytes, useXnnpack = true)
                accelerated.warmUp()
                return accelerated
            } catch (t: Throwable) {
                runCatching { accelerated?.close() }
            }

            // 2) 回退纯 CPU EP
            val cpu = build(env, modelBytes, useXnnpack = false)
            try {
                cpu.warmUp()
                return cpu
            } catch (t: Throwable) {
                runCatching { cpu.close() }
                throw IllegalStateException("ONNX 模型加载/预热失败（XNNPACK 与 CPU 均不可用）: ${t.message}", t)
            }
        }

        private fun build(
            env: OrtEnvironment,
            modelBytes: ByteArray,
            useXnnpack: Boolean,
        ): MotionClassifier {
            val options = OrtSession.SessionOptions()
            var session: OrtSession? = null
            try {
                if (useXnnpack) {
                    options.addXnnpack(mapOf("intra_op_num_threads" to INTRA_OP_THREADS.toString()))
                } else {
                    // CPU 回退：与 XNNPACK 分支对齐 intra-op 线程数
                    options.setIntraOpNumThreads(INTRA_OP_THREADS)
                }
                session = env.createSession(modelBytes, options)
                val inputNames: Set<String> = session.inputNames
                val outputNames: Set<String> = session.outputNames
                val inputName = when {
                    inputNames.contains(INPUT_NAME) -> INPUT_NAME
                    inputNames.isNotEmpty() -> inputNames.first()
                    else -> throw IllegalStateException("模型没有输入")
                }
                val outputName = when {
                    outputNames.contains(OUTPUT_NAME) -> OUTPUT_NAME
                    outputNames.isNotEmpty() -> outputNames.first()
                    else -> throw IllegalStateException("模型没有输出")
                }
                return MotionClassifier(env, session, inputName, outputName, useXnnpack)
            } catch (t: Throwable) {
                runCatching { session?.close() }
                throw t
            } finally {
                runCatching { options.close() }
            }
        }
    }
}
