package com.motioncontroller.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * JSON 小工具（只用 kotlinx-serialization 的 [JsonElement] API，没有 `@Serializable`）。
 *
 * V4 协议里大量字段是可选的，所以这里刻意提供"宽容读取"：类型不匹配时返回 null 而不是抛异常。
 */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 把数字/数字字符串都读成 Int（APP 上报强度偶尔是 12.0 或 "12"，尽量宽容） */
internal fun JsonObject.intOrNull(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    primitive.intOrNull?.let { return it }
    val content = primitive.content
    return content.toIntOrNull() ?: content.toDoubleOrNull()?.toInt()
}

/** 标量转字符串（slotId/id 容忍数字） */
internal fun JsonObject.scalarStringOrNull(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return primitive.content
    val content = primitive.content
    return content.takeIf { it != "null" }
}

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

/**
 * 深合并：`this` 为底，[overlay] 覆盖。两边都是对象时递归合并（V4 的 props / slotState 是增量下发，
 * `slots.patch` 必须深合并，否则会丢掉没上报的字段）。
 */
internal fun JsonObject.deepMerge(overlay: JsonObject): JsonObject {
    if (overlay.isEmpty()) return this
    val out = LinkedHashMap<String, JsonElement>(this.size + overlay.size)
    out.putAll(this)
    for ((key, value) in overlay) {
        val previous = out[key]
        out[key] = if (previous is JsonObject && value is JsonObject) previous.deepMerge(value) else value
    }
    return JsonObject(out)
}

/** 构造 JSON 对象，null 值会被丢弃（V4 里"可选字段"用不到就整个不出现）。 */
internal fun jsonObject(vararg pairs: Pair<String, JsonElement?>): JsonObject {
    val map = LinkedHashMap<String, JsonElement>(pairs.size)
    for ((key, value) in pairs) {
        if (value != null) map[key] = value
    }
    return JsonObject(map)
}

internal fun jsonOf(value: String): JsonPrimitive = JsonPrimitive(value)

internal fun jsonOf(value: Int): JsonPrimitive = JsonPrimitive(value)

internal fun jsonOf(value: Long): JsonPrimitive = JsonPrimitive(value)
