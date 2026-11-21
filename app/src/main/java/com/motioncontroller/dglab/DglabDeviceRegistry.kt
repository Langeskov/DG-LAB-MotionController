package com.motioncontroller.dglab

import com.motioncontroller.relay.boolOrNull
import com.motioncontroller.relay.deepMerge
import com.motioncontroller.relay.intOrNull
import com.motioncontroller.relay.objOrNull
import com.motioncontroller.relay.scalarStringOrNull
import com.motioncontroller.relay.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一个郊狼设备（DG-LAB APP 上报的 `devices` 元素）。
 *
 * [props] / [slotState] 的合并语义按事件类型区分（重要）：
 *  - `devices.snapshot` 是**完整快照**：整个 props/slotState 以本次上报为准（旧字段全部丢弃）；
 *  - `devices.patch` / `slots.patch` 是**增量**：只带变化字段，必须深合并，否则会丢
 *    `intensityA/B`、`comfortLimit` 等没上报的字段。
 *
 * 旧实现把 snapshot 也做深合并，导致 APP 重连后旧连接的 `intensityA`/`connectState` 残留，
 * 强度闭环会拿过期上报值算差 → 已修正。
 */
internal data class DglabDeviceEntry(
    val slotId: String,
    val name: String = "",
    val type: String = "",
    val props: JsonObject = EMPTY_OBJECT,
    val slotState: JsonObject = EMPTY_OBJECT,
) {

    /** 有设备/连接中。两个信号取 AND：显式断开或明确没有设备都不算可用。 */
    val connected: Boolean
        get() = props.stringOrNull("connectState") != "disconnected" &&
            slotState.boolOrNull("hasDevice") != false

    /** activeSlot 候选：V4 里 `connectState` 与 `slotState.hasDevice` 任一表示存在即可 */
    val activeCandidate: Boolean
        get() = props.stringOrNull("connectState") != "disconnected" ||
            slotState.boolOrNull("hasDevice") != false

    val intensityA: Int? get() = props.intOrNull("intensityA")

    val intensityB: Int? get() = props.intOrNull("intensityB")

    /** 设备电量（APP `props.power`；未上报为 null） */
    val power: Int? get() = props.intOrNull("power")

    /** 设备/固件版本（APP `props.version`，可能是数字或字符串；未上报为 null） */
    val version: String? get() = props.stringOrNull("version") ?: props.scalarStringOrNull("version")

    /** COYOTE_030 `props.channelAStatus`（0 无输出/过低、1 开路、2 正常、3 输出故障、4 通道屏蔽） */
    val channelAStatus: Int? get() = props.intOrNull("channelAStatus")

    val channelBStatus: Int? get() = props.intOrNull("channelBStatus")

    /** `slotState.channelA.isMuted`；未上报/不支持为 null（不能默认当作 false） */
    val channelAMuted: Boolean? get() = slotState.objOrNull("channelA")?.boolOrNull("isMuted")

    val channelBMuted: Boolean? get() = slotState.objOrNull("channelB")?.boolOrNull("isMuted")

    /**
     * 给 UI/诊断的摘要。所有设备状态字段都来自 APP 上报（`props` / `slotState`），
     * 未上报时为 null；**没有任何字段能证明 BLE 设备端已停止输出**。
     */
    fun summary(): DglabDevice = DglabDevice(
        slotId = slotId,
        name = name,
        type = type,
        connected = connected,
        power = power,
        version = version,
        channelAMuted = channelAMuted,
        channelBMuted = channelBMuted,
        channelAStatus = channelAStatus,
        channelBStatus = channelBStatus,
    )

    /** 增量覆盖：空字符串表示"没上报"保留旧值；props/slotState 深合并 */
    fun merge(overlay: DglabDeviceEntry): DglabDeviceEntry = copy(
        name = overlay.name.ifBlank { name },
        type = overlay.type.ifBlank { type },
        props = props.deepMerge(overlay.props),
        slotState = slotState.deepMerge(overlay.slotState),
    )

    companion object {
        private val EMPTY_OBJECT = JsonObject(emptyMap())

        fun fromJson(json: JsonObject): DglabDeviceEntry? {
            val slotId = json.scalarStringOrNull("slotId")
                ?: json.scalarStringOrNull("id")
                ?: return null
            return DglabDeviceEntry(
                slotId = slotId,
                name = json.stringOrNull("name") ?: "",
                type = json.stringOrNull("type") ?: "",
                props = json.objOrNull("props") ?: EMPTY_OBJECT,
                slotState = json.objOrNull("slotState") ?: EMPTY_OBJECT,
            )
        }
    }
}

/**
 * 设备列表状态机：`devices.snapshot` / `devices.patch` / `slots.patch` 三个事件在这里收敛。
 *
 * 非线程安全——由 [V4Controller] 的锁保护。每个 `apply*` 返回"是否真的改变了状态"，
 * 调用方据此决定要不要 `publishLocked()`（避免每个心跳/重复快照都刷新 UI StateFlow）。
 */
internal class DglabDeviceRegistry {

    private val devices = LinkedHashMap<String, DglabDeviceEntry>()

    val size: Int get() = devices.size

    fun entries(): List<DglabDeviceEntry> = devices.values.toList()

    fun get(slotId: String): DglabDeviceEntry? = devices[slotId]

    /**
     * 选 activeSlotId：第一个真正可用（[DglabDeviceEntry.connected]）的设备。
     *
     * 审计修复：旧实现找不到可用设备时会回退到"第一个条目"，即使它是 `disconnected`，
     * 于是 `controlAvailable()` 为 true、`device.op` 会往一个不存在的 slot 下发。
     * 现在只认 connected 条目，全部断开时返回 null。
     */
    fun activeSlotId(): String? = devices.values.firstOrNull { it.connected }?.slotId

    fun clear() = devices.clear()

    /**
     * `devices.snapshot` 是完整列表：以本次上报为准。
     *
     * 缺席的 slot 移除；同 slotId 的 props/slotState **整体替换**（不做深合并，避免旧连接残留）。
     */
    fun applySnapshot(incoming: JsonArray): Boolean {
        val next = LinkedHashMap<String, DglabDeviceEntry>(incoming.size)
        for (element in incoming) {
            val json = element as? JsonObject ?: continue
            val entry = DglabDeviceEntry.fromJson(json) ?: continue
            next[entry.slotId] = entry
        }
        val changed = devices.keys.toList() != next.keys.toList() ||
            next.any { (id, entry) -> devices[id] != entry }
        if (changed) {
            devices.clear()
            devices.putAll(next)
        }
        return changed
    }

    fun applyPatch(added: JsonArray?, removed: JsonArray?): Boolean {
        var changed = false
        if (added != null) {
            for (element in added) {
                val json = element as? JsonObject ?: continue
                val entry = DglabDeviceEntry.fromJson(json) ?: continue
                val previous = devices[entry.slotId]
                val merged = previous?.merge(entry) ?: entry
                if (previous != merged) {
                    devices[entry.slotId] = merged
                    changed = true
                }
            }
        }
        if (removed != null) {
            for (element in removed) {
                val slotId = (element as? JsonPrimitive)?.content ?: continue
                if (devices.remove(slotId) != null) changed = true
            }
        }
        return changed
    }

    /**
     * `slots.patch`：`{slots:[{slotId, props:{...}, slotState:{...}}]}`，props 为增量。
     * 未知 slot 忽略（APP 还没发 devices.snapshot 时不该凭空创造设备）。
     */
    fun applySlotsPatch(slots: JsonArray): Boolean {
        var changed = false
        for (element in slots) {
            val json = element as? JsonObject ?: continue
            val slotId = json.scalarStringOrNull("slotId") ?: json.scalarStringOrNull("id") ?: continue
            val previous = devices[slotId] ?: continue
            val merged = previous.merge(
                DglabDeviceEntry(
                    slotId = slotId,
                    props = json.objOrNull("props") ?: EMPTY_OBJECT,
                    slotState = json.objOrNull("slotState") ?: EMPTY_OBJECT,
                ),
            )
            if (merged != previous) {
                devices[slotId] = merged
                changed = true
            }
        }
        return changed
    }

    private companion object {
        val EMPTY_OBJECT = JsonObject(emptyMap())
    }
}
