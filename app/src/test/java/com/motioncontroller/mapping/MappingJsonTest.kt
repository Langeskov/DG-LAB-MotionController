package com.motioncontroller.mapping

import com.motioncontroller.ml.MotionClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingJsonTest {

    private val custom = MappingConfig(
        classBase = listOf(1f, 12f, 23f, 34f, 45f),
        manualLimit = 25f,
        safetyMax = 80,
        switchWindows = 3,
        probEma = 0.5f,
        slewPerSecond = 25f,
        slewDownPerSecond = 90f,
        restOverrideEnabled = false,
        restGyroThreshold = 0.03f,
        restAccStdThreshold = 0.07f,
        restWindows = 3,
        restOverrideClasses = setOf(MotionClass.CYCLING.ordinal, MotionClass.TRAIN.ordinal),
    )

    @Test
    fun `编解码往返保持全部字段`() {
        val json = MappingJson.encode(custom, 12.5f)
        val snapshot = MappingJson.decode(json)
        assertEquals(custom.sanitized(), snapshot.config)
        assertEquals(12.5f, snapshot.manualOffset, 1e-4f)
    }

    @Test
    fun `默认配置往返并忽略未知字段`() {
        val json = MappingJson.encode(MappingSnapshot())
        assertFalse(json.contains("null"))
        // 手工在根和 config 里加未知字段（模拟未来版本新增字段）
        val withUnknown = json.dropLast(1) +
            ""","unknownRoot":123,"config2":{}}"""
        val snapshot = MappingJson.decode(withUnknown)
        assertEquals(MappingConfig.Default, snapshot.config)
        assertEquals(0f, snapshot.manualOffset, 1e-4f)
    }

    @Test
    fun `非法 JSON 或空值回退默认`() {
        listOf(null, "", "   ", "not json", "[]", "42", "{\"config\": 3}").forEach { bad ->
            assertEquals("input=$bad", MappingSnapshot(), MappingJson.decode(bad))
        }
    }

    @Test
    fun `classBase 维度错误回退默认`() {
        val snapshot = MappingJson.decode(
            """{"version":1,"config":{"classBase":[1,2,3],"safetyMax":70},"manualOffset":5}""",
        )
        // classBase 维度不对 → 回退默认；其余合法字段保留
        assertEquals(MappingConfig.Default.classBase, snapshot.config.classBase)
        assertEquals(70, snapshot.config.safetyMax)
        assertEquals(5f, snapshot.manualOffset, 1e-4f)
    }

    @Test
    fun `classBase 非数值元素回退默认`() {
        val snapshot = MappingJson.decode(
            """{"config":{"classBase":[0,10,20,"oops",40]}}""",
        )
        assertEquals(MappingConfig.Default.classBase, snapshot.config.classBase)
    }

    @Test
    fun `数值越界被钳制到合法范围`() {
        val snapshot = MappingJson.decode(
            """{"config":{"safetyMax":9999,"switchWindows":0,"probEma":5,"slewPerSecond":-3,
            |"slewDownPerSecond":0,"manualLimit":-1,"restWindows":0,"restOverrideClasses":[2,9,-1,2]},
            |"manualOffset":999}""".trimMargin(),
        )
        val cfg = snapshot.config
        assertEquals(200, cfg.safetyMax)
        assertEquals(1, cfg.switchWindows)
        assertEquals(0.99f, cfg.probEma, 1e-4f)
        assertEquals(1f, cfg.slewPerSecond, 1e-4f) // 负 slew 被钳制到最小 1
        assertEquals(1f, cfg.slewDownPerSecond, 1e-4f)
        assertEquals(0f, cfg.manualLimit, 1e-4f)
        assertEquals(1, cfg.restWindows)
        // 非法类别下标被丢弃，重复项去重
        assertEquals(setOf(MotionClass.CYCLING.ordinal), cfg.restOverrideClasses)
        // manualLimit=0 → 偏移被钳制到 0
        assertEquals(0f, snapshot.manualOffset, 1e-4f)
    }

    @Test
    fun `字段类型错误逐项回退默认`() {
        val snapshot = MappingJson.decode(
            """{"config":{"safetyMax":"abc","switchWindows":true,"probEma":{},"slewDownPerSecond":null,
            |"restOverrideEnabled":"yes","classBase":null},"manualOffset":"x"}""".trimMargin(),
        )
        assertEquals(MappingConfig.Default, snapshot.config)
        assertEquals(0f, snapshot.manualOffset, 1e-4f)
    }

    @Test
    fun `缺少字段使用默认值`() {
        val snapshot = MappingJson.decode("""{"config":{"safetyMax":35}}""")
        assertEquals(MappingConfig.Default.copy(safetyMax = 35), snapshot.config)
    }

    @Test
    fun `manualOffset 按 manualLimit 钳制且 NaN 归零`() {
        val clipped = MappingJson.decode(
            """{"config":{"manualLimit":25},"manualOffset":100}""",
        )
        assertEquals(25f, clipped.manualOffset, 1e-4f)

        val encoded = MappingJson.encode(custom, Float.NaN)
        val decoded = MappingJson.decode(encoded)
        assertEquals(0f, decoded.manualOffset, 1e-4f)
    }

    @Test
    fun `编码结果可被二次往返且包含版本号`() {
        val json = MappingJson.encode(custom, -7f)
        assertTrue(json.contains("\"version\":1"))
        val once = MappingJson.decode(json)
        val twice = MappingJson.decode(MappingJson.encode(once))
        assertEquals(once, twice)
    }

    // ───────────────────────── 波形（WaveConfig） ─────────────────────────

    @Test
    fun `波形配置往返保持全部字段`() {
        val wave = WaveConfig(
            enabled = true,
            shape = WaveShape.BURST,
            frequencyHz = 1.25f,
            depthPercent = 80,
            durationSec = 60,
        )
        val json = MappingJson.encode(custom, 3f, wave)
        val snapshot = MappingJson.decode(json)
        assertEquals(wave, snapshot.wave)
        assertEquals(custom.sanitized(), snapshot.config)
    }

    @Test
    fun `旧格式无波形字段时回退默认波形`() {
        val oldFormat = """{"version":1,"config":{"safetyMax":35},"manualOffset":7}"""
        val snapshot = MappingJson.decode(oldFormat)
        assertEquals(WaveConfig.Default, snapshot.wave)
        assertEquals(35, snapshot.config.safetyMax)
    }

    @Test
    fun `波形字段非法逐项回退默认并钳制越界`() {
        val snapshot = MappingJson.decode(
            """
            {"wave":{"enabled":"yes","shape":"no-such-shape","frequencyHz":999,
             "depthPercent":-5,"durationSec":"abc"}}
            """.trimIndent(),
        )
        // enabled 类型错误 → 默认 false；shape 未知 → FLAT（保守不调制）；频率钳到上限；深度钳到 0；持续时间回退 0
        assertEquals(false, snapshot.wave.enabled)
        assertEquals(WaveShape.FLAT, snapshot.wave.shape)
        assertEquals(WaveConfig.MAX_FREQUENCY_HZ, snapshot.wave.frequencyHz, 1e-4f)
        assertEquals(0, snapshot.wave.depthPercent)
        assertEquals(0, snapshot.wave.durationSec)
    }
}
