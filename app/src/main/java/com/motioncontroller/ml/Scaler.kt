package com.motioncontroller.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * StandardScaler 参数（纯 Kotlin，无 Android 依赖）。
 *
 * 训练侧 sklearn 的标准化：`x_std = (x - mean) / scale`，其中 `scale = sqrt(var)`（ddof=0）。
 *
 * 当前 `assets/scaler.json` 是占位（`valid=false`，mean=0 / scale=1）：
 * 此时仍可标准化（数值不变），但推理结果不可信，UI 通过
 * [MotionPipelineStatus.scalerValid] 显示警告。
 */
class StandardScaler(
    val mean: DoubleArray,
    val scale: DoubleArray,
    /** scaler 是否为真实训练参数；false 表示占位/缺失。 */
    val valid: Boolean,
) {

    /** 特征维度（应为 24）。 */
    val dim: Int get() = mean.size

    init {
        require(mean.size == scale.size) {
            "mean(${mean.size}) 与 scale(${scale.size}) 长度必须一致"
        }
        for (i in mean.indices) {
            require(mean[i].isFinite()) { "mean[$i] 不是有限值：${mean[i]}" }
            require(scale[i].isFinite()) { "scale[$i] 不是有限值：${scale[i]}" }
            // scale==0 仍允许（transform 按 1 处理，保持既有策略）；负数一定是坏参数
            require(scale[i] >= 0.0) { "scale[$i] 必须 >= 0：${scale[i]}" }
        }
    }

    /**
     * 标准化一行特征：`(x[d] - mean[d]) / scale[d]`；`scale[d] == 0` 时按 1 处理。
     */
    fun transform(row: DoubleArray): DoubleArray {
        val out = DoubleArray(row.size)
        for (d in row.indices) {
            val m = if (d < mean.size) mean[d] else 0.0
            val s = if (d < scale.size) scale[d] else 1.0
            val div = if (s == 0.0) 1.0 else s
            out[d] = (row[d] - m) / div
        }
        return out
    }

    /** 批量标准化（不修改入参）。 */
    fun transformAll(rows: Array<DoubleArray>): Array<DoubleArray> = Array(rows.size) { transform(rows[it]) }

    companion object {
        const val ASSET_NAME = "scaler.json"

        /** 恒等 scaler（valid=false）：用于 scaler.json 缺失/损坏时的降级。 */
        fun identity(dim: Int = FeatureExtractor.FEATURE_DIM): StandardScaler =
            StandardScaler(DoubleArray(dim), DoubleArray(dim) { 1.0 }, false)

        /**
         * 解析 scaler.json。字段：`valid`(bool)、`mean`(24)、`scale`(24)。
         *
         * 校验（2026-10-31 audit）：维度必须 == [FeatureExtractor.FEATURE_DIM]，mean/scale 全部
         * 有限，scale >= 0（scale==0 按 1 处理的既有策略保留）。
         *
         * @throws IllegalArgumentException 结构不合法（由调用方决定降级为 [identity]）
         */
        fun parse(json: String): StandardScaler = fromJsonElement(Json.parseToJsonElement(json))

        /** 从已解析的 JsonElement 构造（kotlinx JsonElement API，不用 @Serializable）。 */
        fun fromJsonElement(element: JsonElement): StandardScaler {
            require(element is JsonObject) { "scaler.json 根节点必须是对象" }
            val valid = element["valid"]?.jsonPrimitive?.booleanOrNull ?: false
            val mean = readArray(element, "mean")
            val scale = readArray(element, "scale")
            require(mean.size == scale.size) {
                "mean(${mean.size}) 与 scale(${scale.size}) 长度必须一致"
            }
            require(mean.size == FeatureExtractor.FEATURE_DIM) {
                "scaler 维度必须是 ${FeatureExtractor.FEATURE_DIM}，收到 ${mean.size}"
            }
            return StandardScaler(mean, scale, valid)
        }

        private fun readArray(obj: JsonObject, key: String): DoubleArray {
            val arr = obj[key]?.jsonArray
                ?: throw IllegalArgumentException("scaler.json 缺少数组字段 '$key'")
            return DoubleArray(arr.size) { i ->
                arr[i].jsonPrimitive.doubleOrNull
                    ?: throw IllegalArgumentException("scaler.json '$key[$i]' 不是数值")
            }
        }
    }
}
