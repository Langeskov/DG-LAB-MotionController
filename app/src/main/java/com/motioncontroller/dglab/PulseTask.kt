package com.motioncontroller.dglab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase 4A：V4 `device.op` t=0 `AppendPulseData` 的**协议层**数据结构与校验。
 *
 * 本文件只做数据建模 / 结构校验，不决定波形语义、不内置任何波形库、也不默认启用：
 *  - 帧的“数值语义”（频率/强度/间隔）以公开协议与真实设备验证为准，未验证前不臆测；
 *  - 本工程默认关闭 t=0（见 [V4Controller.pulseTasksEnabled]）；只有在显式实验模式下才允许下发，
 *    因为波形帧会**绕过** `safetyMax / slew / EMA` 等强度闭环约束（Phase 3 报告 §8 已列为 BLOCKED）。
 */
enum class PulseFrameVersion(val wire: Int) {
    /** V2 帧 `[a, b, interval]`（3 个整数） */
    V2(2),

    /** V3 帧 `[a1, a2, a3, a4, b1, b2, b3, b4]`（8 个整数；默认） */
    V3(3),
}

/** 一条 AppendPulseData 任务（单设备、单通道）。 */
data class PulseTaskSpec(
    /** 0 = A 通道，1 = B 通道 */
    val channel: Int,
    /** 帧列表：V2 每帧 3 个整数，V3 每帧 8 个整数 */
    val frames: List<List<Int>>,
    val version: PulseFrameVersion = PulseFrameVersion.V3,
    /** 最长播放时长（ms）；0 = 不按时长自动结束（帧列表消费完仍会完成） */
    val durationMs: Int = 0,
    /** true = 替换同设备/通道/类型已有任务（`im`），false = 排队 */
    val replace: Boolean = true,
)

/** 任务在 APP 侧的生命周期（对应 `device.op` 的终态 reason）。 */
enum class PulseTaskState {
    /** 尚未下发 / 已被清空 */
    IDLE,
    /** 已入队，等待 APP 终态回执 */
    RUNNING,
    /** 帧列表消费完 / 时长到期（`reason=completed`） */
    COMPLETED,
    /** 被 `device.op.clear` 清除（`reason=cleared`） */
    CLEARED,
    /** 被替换（`reason=replaced`） */
    REPLACED,
    /** 连接断开被取消（`reason=cancelled`） */
    CANCELLED,
    /** 错误响应 / 发送失败 / 校验失败 */
    FAILED,
}

/** 单通道任务状态快照（UI/诊断只读，不代表 BLE 设备物理输出确认）。 */
data class PulseTaskStatus(
    val channel: Int,
    val state: PulseTaskState,
    val reqId: String? = null,
    val reason: String? = null,
)

/**
 * AppendPulseData 的结构校验器（纯 JVM，可单测）。
 *
 * 只做**结构**校验（帧数/维度/范围/时长），不解释帧数值含义；因此通过校验不等于波形安全，
 * 调用方仍必须满足 Phase 3 报告 §8 的准入条件（合法帧来源、非人体验证、限幅）。
 */
object PulseTaskSpecValidator {

    const val MAX_FRAMES = 1_200

    /** 最长 120s：即使实验模式也禁止“无限播放”。 */
    const val MAX_DURATION_MS = 120_000

    /** 帧值只做结构上限（1 字节），不解释语义。 */
    private const val MAX_FRAME_VALUE = 255

    /** @return null 表示通过；否则为拒绝原因（可直接写日志） */
    fun validate(spec: PulseTaskSpec): String? {
        if (spec.channel != 0 && spec.channel != 1) return "channel 必须是 0(A) 或 1(B)"
        if (spec.frames.isEmpty()) return "帧列表不能为空"
        if (spec.frames.size > MAX_FRAMES) return "帧数 ${spec.frames.size} 超过上限 $MAX_FRAMES"
        val expected = when (spec.version) {
            PulseFrameVersion.V2 -> 3
            PulseFrameVersion.V3 -> 8
        }
        spec.frames.forEachIndexed { index, frame ->
            if (frame.size != expected) {
                return "第 $index 帧长度 ${frame.size} 与 ${spec.version.name} 要求的 $expected 不符"
            }
            frame.forEachIndexed { valueIndex, value ->
                if (value < 0 || value > MAX_FRAME_VALUE) {
                    return "第 $index 帧第 $valueIndex 个值 $value 超出 0..$MAX_FRAME_VALUE"
                }
            }
        }
        if (spec.durationMs < 0 || spec.durationMs > MAX_DURATION_MS) {
            return "durationMs=${spec.durationMs} 超出 0..$MAX_DURATION_MS"
        }
        return null
    }

    /** `device.op` 的 `v` 字段：整数帧数组的 JSON 形式（`number[][]`）。 */
    fun framesToJson(frames: List<List<Int>>): JsonArray =
        JsonArray(frames.map { row -> JsonArray(row.map { JsonPrimitive(it) }) })
}
