package com.motioncontroller.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Softmax` / argmax 单测（模型输出 logits → 概率）。 */
class SoftmaxTest {

    @Test
    fun probabilitiesSumToOne() {
        val logits = floatArrayOf(2.5f, -1.0f, 0.0f, 0.5f, 3.0f)
        val probs = Softmax.apply(logits)
        assertEquals(5, probs.size)
        var sum = 0.0
        for (p in probs) {
            assertTrue("概率必须非负: $p", p >= 0f)
            sum += p
        }
        assertEquals(1.0, sum, 1e-6)
        // argmax 应指向最大的 logit
        assertEquals(4, Softmax.argmax(probs))
        // 保持 logit 的相对大小顺序
        assertTrue(probs[4] > probs[0])
        assertTrue(probs[0] > probs[3])
        assertTrue(probs[3] > probs[2])
        assertTrue(probs[2] > probs[1])
    }

    @Test
    fun largeLogitsAreNumericallyStable() {
        val probs = Softmax.apply(floatArrayOf(10_000f, 10_001f, -10_000f, 0f, 5f))
        var sum = 0.0
        for (p in probs) {
            assertTrue("不应出现 NaN/Inf: $p", p.isFinite())
            sum += p
        }
        assertEquals(1.0, sum, 1e-5)
        assertEquals(1, Softmax.argmax(probs))
    }

    @Test
    fun equalLogitsGiveUniform() {
        val probs = Softmax.apply(FloatArray(5) { 1.0f })
        for (p in probs) assertEquals(0.2f, p, 1e-6f)
        assertEquals(0, Softmax.argmax(probs))
    }

    @Test
    fun emptyInputIsSafe() {
        assertEquals(0, Softmax.apply(FloatArray(0)).size)
        assertEquals(0, Softmax.argmax(FloatArray(0)))
    }
}
