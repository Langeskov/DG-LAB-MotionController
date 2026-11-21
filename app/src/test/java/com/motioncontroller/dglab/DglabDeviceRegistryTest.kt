package com.motioncontroller.dglab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备列表状态机单测：V4 的 `props`/`slotState` 是增量，深合并/activeSlot 选择/增删必须正确，
 * 否则强度闭环会用到错误的 slotId 或丢失上报强度。
 */
class DglabDeviceRegistryTest {

    @Test
    fun snapshotSelectsFirstConnectedDevice() {
        val registry = DglabDeviceRegistry()
        registry.applySnapshot(
            jsonArray(
                """
                [
                  {"slotId":"slot-a","name":"A","type":"COYOTE_030",
                   "props":{"connectState":"disconnected","intensityA":0},
                   "slotState":{"hasDevice":false}},
                  {"slotId":"slot-b","name":"B","type":"COYOTE_030",
                   "props":{"connectState":"connected","intensityA":12},"slotState":{"hasDevice":true}},
                  {"slotId":"slot-c","name":"C","type":"COYOTE_030","props":{}}
                ]
                """
            )
        )
        assertEquals(3, registry.size)
        assertEquals("slot-b", registry.activeSlotId())
        assertEquals(12, registry.get("slot-b")?.intensityA)
        assertEquals(true, registry.get("slot-b")?.connected)
        assertEquals(false, registry.get("slot-a")?.connected)
        // 无 connectState/hasDevice 的按"存在"处理
        assertEquals(true, registry.get("slot-c")?.activeCandidate)
    }

    @Test
    fun slotsPatchDeepMergesIncrementalProps() {
        val registry = DglabDeviceRegistry()
        registry.applySnapshot(
            jsonArray(
                """
                [{"slotId":"slot-1","name":"郊狼","type":"COYOTE_030",
                  "props":{"connectState":"connected","intensityA":5,"power":80},
                  "slotState":{"hasDevice":true,"channelA":{"isMuted":false,"comfortLimit":50}}}]
                """
            )
        )

        val changed = registry.applySlotsPatch(
            jsonArray(
                """
                [{"slotId":"slot-1",
                  "props":{"intensityA":42,"intensityB":7},
                  "slotState":{"channelA":{"isMuted":true}}}]
                """
            )
        )
        assertTrue(changed)

        val entry = registry.get("slot-1")!!
        // 新值生效
        assertEquals(42, entry.intensityA)
        assertEquals(7, entry.intensityB)
        // 没上报的字段必须保留（深合并）
        assertEquals(80, entry.props.intField("power"))
        assertEquals("connected", entry.props.strField("connectState"))
        assertEquals(true, entry.slotState.boolField("hasDevice"))
        assertEquals(true, entry.slotState.objField("channelA")?.boolField("isMuted"))
        assertEquals(50, entry.slotState.objField("channelA")?.intField("comfortLimit"))
    }

    @Test
    fun slotsPatchIgnoresUnknownSlot() {
        val registry = DglabDeviceRegistry()
        registry.applySnapshot(
            jsonArray("""[{"slotId":"slot-1","props":{"intensityA":0}}]"""),
        )
        val changed = registry.applySlotsPatch(
            jsonArray("""[{"slotId":"ghost","props":{"intensityA":99}}]"""),
        )
        assertEquals(false, changed)
        assertNull(registry.get("ghost"))
        assertEquals(1, registry.size)
    }

    @Test
    fun devicesPatchAddsAndRemoves() {
        val registry = DglabDeviceRegistry()
        registry.applySnapshot(
            jsonArray(
                """
                [{"slotId":"slot-1","props":{"connectState":"connected"}},
                 {"slotId":"slot-2","props":{"connectState":"connected"}}]
                """
            )
        )
        registry.applyPatch(
            jsonArray("""[{"slotId":"slot-3","props":{"connectState":"connected"}}]"""),
            jsonArray("""["slot-2"]"""),
        )
        assertEquals(setOf("slot-1", "slot-3"), registry.entries().map { it.slotId }.toSet())
        assertNull(registry.get("slot-2"))
    }

    @Test
    fun snapshotRemovesAbsentSlots() {
        val registry = DglabDeviceRegistry()
        registry.applySnapshot(
            jsonArray(
                """
                [{"slotId":"slot-1","props":{"connectState":"connected"}},
                 {"slotId":"slot-2","props":{"connectState":"connected"}}]
                """
            )
        )
        registry.applySnapshot(
            jsonArray("""[{"slotId":"slot-2","props":{"connectState":"connected"}}]"""),
        )
        assertEquals(listOf("slot-2"), registry.entries().map { it.slotId })
        assertEquals("slot-2", registry.activeSlotId())
    }

    @Test
    fun snapshotReplacesPropsAndSlotStateWholesaleInsteadOfMerging() {
        val registry = DglabDeviceRegistry()
        registry.applySnapshot(
            jsonArray(
                """
                [{"slotId":"slot-1","name":"郊狼","type":"COYOTE_030",
                  "props":{"connectState":"connected","intensityA":30,"power":80},
                  "slotState":{"hasDevice":true,"channelA":{"comfortLimit":50}}}]
                """
            )
        )
        // 新快照（例如 APP 重连后）不带 power/channelA：旧字段必须整体丢弃，不能深合并残留
        val changed = registry.applySnapshot(
            jsonArray(
                """
                [{"slotId":"slot-1",
                  "props":{"connectState":"connected","intensityA":5},
                  "slotState":{"hasDevice":true,"channelA":{"isMuted":true}}}]
                """
            )
        )
        assertTrue(changed)
        val entry = registry.get("slot-1")!!
        assertEquals(5, entry.intensityA)
        assertNull("snapshot 是整体替换，旧 power 不能残留", entry.props.intField("power"))
        assertNull("snapshot 是整体替换，旧 comfortLimit 不能残留", entry.slotState.objField("channelA")?.intField("comfortLimit"))
        assertEquals(true, entry.slotState.objField("channelA")?.boolField("isMuted"))
        // 增量 slots.patch 仍然深合并（在 slotsPatchDeepMergesIncrementalProps 里覆盖）
        assertEquals(false, registry.applySnapshot(jsonArray("""[{"slotId":"slot-1","props":{"connectState":"connected","intensityA":5},"slotState":{"hasDevice":true,"channelA":{"isMuted":true}}}]""")))
    }

    @Test
    fun disconnectedDevicesAreNeverSelectedAsActiveSlot() {
        val registry = DglabDeviceRegistry()
        registry.applySnapshot(
            jsonArray(
                """
                [{"slotId":"slot-a","props":{"connectState":"disconnected"},"slotState":{"hasDevice":false}},
                 {"slotId":"slot-b","props":{"connectState":"connected"},"slotState":{"hasDevice":false}}]
                """
            )
        )
        // slot-b 的 hasDevice=false → 也算断开；两个都不能当 activeSlot
        assertNull("全部断开的设备不能作为 activeSlot", registry.activeSlotId())

        registry.applySnapshot(
            jsonArray("""[{"slotId":"slot-a","props":{"connectState":"connected"},"slotState":{"hasDevice":true}}]"""),
        )
        assertEquals("slot-a", registry.activeSlotId())
    }

    @Test
    fun summaryExposesAppReportedPropsAndSlotState() {
        val registry = DglabDeviceRegistry()
        registry.applySnapshot(
            jsonArray(
                """
                [{"slotId":"slot-1","name":"郊狼 3.0","type":"COYOTE_030",
                  "props":{"connectState":"connected","intensityA":12,"intensityB":0,
                           "power":80,"version":"2.1.0","channelAStatus":2,"channelBStatus":1},
                  "slotState":{"hasDevice":true,
                               "channelA":{"isMuted":false},
                               "channelB":{"isMuted":true}}}]
                """
            )
        )
        val summary = registry.get("slot-1")!!.summary()
        assertEquals(80, summary.power)
        assertEquals("2.1.0", summary.version)
        assertEquals(2, summary.channelAStatus)
        assertEquals(1, summary.channelBStatus)
        assertEquals(false, summary.channelAMuted)
        assertEquals(true, summary.channelBMuted)
        assertTrue(summary.connected)
    }

    @Test
    fun summaryLeavesUnreportedDeviceFieldsNull() {
        val registry = DglabDeviceRegistry()
        registry.applySnapshot(
            jsonArray("""[{"slotId":"slot-1","props":{"connectState":"connected"}}]"""),
        )
        val summary = registry.get("slot-1")!!.summary()
        assertNull("APP 未上报 power 不能伪造", summary.power)
        assertNull(summary.version)
        assertNull(summary.channelAStatus)
        assertNull(summary.channelAMuted)
        assertNull(summary.channelBMuted)
    }

    private fun jsonArray(text: String): JsonArray = Json.parseToJsonElement(text) as JsonArray

    private fun JsonObject.objField(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    private fun JsonObject.boolField(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strField(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
