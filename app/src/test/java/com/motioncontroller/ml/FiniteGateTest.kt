package com.motioncontroller.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `FiniteGate`（喂 ORT 前的 NaN/Inf 闸门）单测。 */
class FiniteGateTest {

    private fun row(base: Double, size: Int = 24) = DoubleArray(size) { base + it }

    @Test
    fun detectsFirstNonFinitePosition() {
        val clean = Array(3) { row(it.toDouble()) }
        assertNull(FiniteGate.firstNonFinite(clean))
        assertTrue(FiniteGate.allFinite(clean))

        val withNaN = Array(3) { row(it.toDouble()) }
        withNaN[2][7] = Double.NaN
        assertEquals(2 to 7, FiniteGate.firstNonFinite(withNaN))
        assertFalse(FiniteGate.allFinite(withNaN))

        // 第一处非法优先返回（先看行、再看列）
        withNaN[0][3] = Double.NEGATIVE_INFINITY
        assertEquals(0 to 3, FiniteGate.firstNonFinite(withNaN))
    }

    @Test
    fun detectsNonFiniteModelOutput() {
        assertFalse(FiniteGate.containsNonFinite(floatArrayOf(0.1f, 0.2f, 0.7f)))
        assertTrue(FiniteGate.containsNonFinite(floatArrayOf(0.1f, Float.NaN)))
        assertTrue(FiniteGate.containsNonFinite(floatArrayOf(Float.POSITIVE_INFINITY)))
        assertFalse(FiniteGate.containsNonFinite(FloatArray(0)))
    }

    @Test
    fun emptyRowsAreFinite() {
        assertTrue(FiniteGate.allFinite(emptyArray()))
    }
}
