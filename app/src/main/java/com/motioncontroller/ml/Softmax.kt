package com.motioncontroller.ml

import kotlin.math.exp

/**
 * 数值稳定的 softmax（纯 Kotlin，便于 JVM 单测）。
 *
 * 模型输出是 logits（AGENTS.md 4.4），必须经 softmax 才能当概率使用。
 */
object Softmax {

    /** 返回与 [logits] 等长的概率数组（和为 1）。空数组原样返回。 */
    fun apply(logits: FloatArray): FloatArray {
        if (logits.isEmpty()) return FloatArray(0)
        var max = logits[0]
        for (i in 1 until logits.size) if (logits[i] > max) max = logits[i]
        val out = FloatArray(logits.size)
        var sum = 0.0
        for (i in logits.indices) {
            val e = exp((logits[i] - max).toDouble())
            out[i] = e.toFloat()
            sum += e
        }
        if (sum <= 0.0 || sum.isNaN()) {
            // 极端数值兜底：退化为均匀分布
            val uniform = 1f / logits.size
            for (i in out.indices) out[i] = uniform
            return out
        }
        val inv = 1.0 / sum
        for (i in out.indices) out[i] = (out[i] * inv).toFloat()
        return out
    }

    /** argmax（并列时取最小下标，与 `np.argmax` 一致）。 */
    fun argmax(probs: FloatArray): Int {
        if (probs.isEmpty()) return 0
        var best = 0
        for (i in 1 until probs.size) if (probs[i] > probs[best]) best = i
        return best
    }
}
