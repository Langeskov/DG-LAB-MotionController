package com.motioncontroller.ml

/**
 * 有限值闸门（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 目的：在把标准化后的窗口喂给 ONNX 之前，先确认**所有**元素都是有限值。
 * 一旦输入含 NaN/Inf，ORT 输出 logits 也会变 NaN；旧实现里 `Softmax.apply` 的兜底
 * 会把它退化成均匀分布（argmax=0 → STATIONARY），把数据错误伪装成"静止"。
 * 现在改为显式拒绝：记 `lastError` 并跳过本次推理。
 */
object FiniteGate {

    /**
     * 返回第一个非有限元素的位置 `row to col`；全部有限返回 null。
     * 空数组视为有限（没有非法元素）。
     */
    fun firstNonFinite(rows: Array<DoubleArray>): Pair<Int, Int>? {
        for (i in rows.indices) {
            val row = rows[i]
            for (j in row.indices) {
                if (!row[j].isFinite()) return i to j
            }
        }
        return null
    }

    /** 所有元素是否都是有限值。 */
    fun allFinite(rows: Array<DoubleArray>): Boolean = firstNonFinite(rows) == null

    /** 浮点数组里是否含 NaN/Inf（用于校验 softmax 输出）。 */
    fun containsNonFinite(values: FloatArray): Boolean {
        for (v in values) if (!v.isFinite()) return true
        return false
    }
}
