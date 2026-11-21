package com.motioncontroller.mapping

import com.motioncontroller.ml.MotionClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 需要持久化的映射状态：完整 [MappingConfig] + 手动偏移 + [WaveConfig]（本地波形）。 */
data class MappingSnapshot(
    val config: MappingConfig = MappingConfig.Default,
    val manualOffset: Float = 0f,
    val wave: WaveConfig = WaveConfig.Default,
)

/**
 * [MappingConfig] / manualOffset 的 JSON 编解码。
 *
 * 只用 kotlinx-serialization-json 的运行时 [JsonElement] API（**不需要 serialization 编译器插件**），
 * 纯 JVM 逻辑，可单测。持久化/反序列化的所有输入都视为不可信数据：
 *  - 缺失字段 → 该项默认值；
 *  - 类型错误 / 维度错误 / 非有限数 / 越界 → 该项回退默认值或钳制到合法区间（见 [MappingConfig.sanitized]）；
 *  - 未知字段（含 version 升级新增字段）→ 忽略；
 *  - 整段 JSON 非法 / 不是对象 → 全部回退默认值。
 *
 * 安全优先：反序列化永远不会抛异常，也永远不会产生 NaN/负 slew/错误维度的配置。
 */
object MappingJson {

    /** 持久化格式版本；解析时忽略不匹配（向后兼容未知字段）。 */
    const val VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_CONFIG = "config"
    private const val KEY_MANUAL_OFFSET = "manualOffset"
    private const val KEY_WAVE = "wave"

    private const val KEY_WAVE_ENABLED = "enabled"
    private const val KEY_WAVE_SHAPE = "shape"
    private const val KEY_WAVE_FREQ = "frequencyHz"
    private const val KEY_WAVE_DEPTH = "depthPercent"
    private const val KEY_WAVE_DURATION = "durationSec"

    private const val KEY_CLASS_BASE = "classBase"
    private const val KEY_MANUAL_LIMIT = "manualLimit"
    private const val KEY_SAFETY_MAX = "safetyMax"
    private const val KEY_SWITCH_WINDOWS = "switchWindows"
    private const val KEY_PROB_EMA = "probEma"
    private const val KEY_SLEW = "slewPerSecond"
    private const val KEY_SLEW_DOWN = "slewDownPerSecond"
    private const val KEY_REST_ENABLED = "restOverrideEnabled"
    private const val KEY_REST_GYRO = "restGyroThreshold"
    private const val KEY_REST_ACC = "restAccStdThreshold"
    private const val KEY_REST_WINDOWS = "restWindows"
    private const val KEY_REST_CLASSES = "restOverrideClasses"

    /** 编码为 JSON 字符串（配置先经 [MappingConfig.sanitized]，保证可安全回读）。 */
    fun encode(config: MappingConfig, manualOffset: Float, wave: WaveConfig = WaveConfig.Default): String {
        val safeConfig = config.sanitized()
        val offset = if (manualOffset.isFinite()) {
            manualOffset.coerceIn(-safeConfig.manualLimit, safeConfig.manualLimit)
        } else {
            0f
        }
        return buildJsonObject {
            put(KEY_VERSION, VERSION)
            put(KEY_CONFIG, configToJson(safeConfig))
            put(KEY_MANUAL_OFFSET, offset)
            put(KEY_WAVE, waveToJson(wave))
        }.toString()
    }

    fun encode(snapshot: MappingSnapshot): String = encode(snapshot.config, snapshot.manualOffset, snapshot.wave)

    /** 解析 JSON 字符串；null/空白/非法内容都返回默认快照，绝不抛异常。 */
    fun decode(text: String?): MappingSnapshot {
        if (text.isNullOrBlank()) return MappingSnapshot()
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return MappingSnapshot()
        return snapshotFromJson(element)
    }

    /** 解析根对象；非对象 → 默认快照。 */
    fun snapshotFromJson(element: JsonElement?): MappingSnapshot {
        val root = element as? JsonObject ?: return MappingSnapshot()
        val config = configFromJson(root[KEY_CONFIG])
        val offsetRaw = root.primitive(KEY_MANUAL_OFFSET)?.floatOrNull ?: 0f
        val offset = if (offsetRaw.isFinite()) offsetRaw else 0f
        return MappingSnapshot(
            config = config,
            manualOffset = offset.coerceIn(-config.manualLimit, config.manualLimit),
            wave = waveFromJson(root[KEY_WAVE]),
        )
    }

    /** 编码波形配置（先 sanitize）。 */
    fun waveToJson(wave: WaveConfig): JsonObject {
        val safe = wave.sanitized()
        return buildJsonObject {
            put(KEY_WAVE_ENABLED, safe.enabled)
            put(KEY_WAVE_SHAPE, safe.shape.id)
            put(KEY_WAVE_FREQ, safe.frequencyHz)
            put(KEY_WAVE_DEPTH, safe.depthPercent)
            put(KEY_WAVE_DURATION, safe.durationSec)
        }
    }

    /** 解析波形配置；缺失字段回退默认值，**损坏/未知**值回退保守值（shape→FLAT 不调制），绝不抛异常。 */
    fun waveFromJson(element: JsonElement?): WaveConfig {
        val obj = element as? JsonObject ?: return WaveConfig.Default
        val d = WaveConfig.Default
        val shapeRaw = obj.primitive(KEY_WAVE_SHAPE)
        return WaveConfig(
            enabled = obj.boolean(KEY_WAVE_ENABLED, d.enabled),
            // 缺失 → 默认；存在但不认识 → FLAT（最保守，审计 S7）
            shape = if (shapeRaw == null) d.shape else WaveShape.fromId(shapeRaw.content),
            frequencyHz = obj.float(KEY_WAVE_FREQ, d.frequencyHz),
            depthPercent = obj.int(KEY_WAVE_DEPTH, d.depthPercent),
            durationSec = obj.int(KEY_WAVE_DURATION, d.durationSec),
        ).sanitized()
    }

    /** 解析单个 config 对象；缺失/非对象 → 默认配置。 */
    fun configFromJson(element: JsonElement?): MappingConfig {
        val obj = element as? JsonObject ?: return MappingConfig.Default
        val d = MappingConfig.Default

        val classBase = (obj[KEY_CLASS_BASE] as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.floatOrNull }
            ?.takeIf { list -> list.size == MotionClass.entries.size && list.all { it != null } }
            ?.map { it!! }
            ?: d.classBase

        // 非法类别下标直接丢弃（TRAIN=4 合法，越界忽略）；字段缺失/非数组 → 默认集合
        val restClasses = (obj[KEY_REST_CLASSES] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            ?.filter { it in 0 until MotionClass.entries.size }
            ?.toSet()
            ?: d.restOverrideClasses

        return MappingConfig(
            classBase = classBase,
            manualLimit = obj.float(KEY_MANUAL_LIMIT, d.manualLimit),
            safetyMax = obj.int(KEY_SAFETY_MAX, d.safetyMax),
            switchWindows = obj.int(KEY_SWITCH_WINDOWS, d.switchWindows),
            probEma = obj.float(KEY_PROB_EMA, d.probEma),
            slewPerSecond = obj.float(KEY_SLEW, d.slewPerSecond),
            slewDownPerSecond = obj.float(KEY_SLEW_DOWN, d.slewDownPerSecond),
            restOverrideEnabled = obj.boolean(KEY_REST_ENABLED, d.restOverrideEnabled),
            restGyroThreshold = obj.float(KEY_REST_GYRO, d.restGyroThreshold),
            restAccStdThreshold = obj.float(KEY_REST_ACC, d.restAccStdThreshold),
            restWindows = obj.int(KEY_REST_WINDOWS, d.restWindows),
            restOverrideClasses = restClasses,
        ).sanitized()
    }

    /** 编码单个 config（先 sanitize）。 */
    fun configToJson(config: MappingConfig): JsonObject {
        val safe = config.sanitized()
        return buildJsonObject {
            putJsonArray(KEY_CLASS_BASE) { safe.classBase.forEach { add(JsonPrimitive(it)) } }
            put(KEY_MANUAL_LIMIT, safe.manualLimit)
            put(KEY_SAFETY_MAX, safe.safetyMax)
            put(KEY_SWITCH_WINDOWS, safe.switchWindows)
            put(KEY_PROB_EMA, safe.probEma)
            put(KEY_SLEW, safe.slewPerSecond)
            put(KEY_SLEW_DOWN, safe.slewDownPerSecond)
            put(KEY_REST_ENABLED, safe.restOverrideEnabled)
            put(KEY_REST_GYRO, safe.restGyroThreshold)
            put(KEY_REST_ACC, safe.restAccStdThreshold)
            put(KEY_REST_WINDOWS, safe.restWindows)
            putJsonArray(KEY_REST_CLASSES) { safe.restOverrideClasses.sorted().forEach { add(JsonPrimitive(it)) } }
        }
    }

    // ───────────────────────── 解析辅助 ─────────────────────────

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.float(key: String, fallback: Float): Float =
        primitive(key)?.floatOrNull?.takeIf { it.isFinite() } ?: fallback

    private fun JsonObject.int(key: String, fallback: Int): Int =
        primitive(key)?.intOrNull ?: fallback

    private fun JsonObject.boolean(key: String, fallback: Boolean): Boolean =
        primitive(key)?.booleanOrNull ?: fallback
}
