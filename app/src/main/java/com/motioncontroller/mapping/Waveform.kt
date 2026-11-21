package com.motioncontroller.mapping

/**
 * 本地波形（强度包络合成）。
 *
 * 背景（真机反馈）：进入控制后脉冲节奏仍由 DG-LAB APP 的波形库决定，本工程希望"覆盖或手动设置波形"。
 * V4 外部控制协议（已真机验证面）只提供强度类指令（`device.op` t=3 AddIntensity / t=7 SetIntensity v=0 /
 * `device.op.clear`），**没有切换或下发 APP 波形的指令**。因此本模块在唯一可验证的控制通道上合成
 * “强度包络”：对映射输出做周期性调制（呼吸/潮汐/涌动等节奏），叠加在 APP 波形之上。
 *
 * 安全约定：
 *  - 包络增益恒在 [0, 1]，只做“向下调制”（**不放大**映射输出；上限不可破：base 已过 safetyMax/slew，
 *    包络自身沿口 ≤ base×depth，链路层 t=3 的 MAX_STEP=10/200ms 再把快沿平滑成阶梯）；
 *  - 不触碰急停/断连/未 arm 的归零逻辑（那些路径根本不会走到调制）；
 *  - [WaveConfig.sanitized] 保证任何持久化/外部输入都落在合法区间，绝不产生 NaN。
 */
enum class WaveShape(val id: String) {
    /** 恒定（不调制，增益恒 1）。 */
    FLAT("flat"),

    /** 正弦：连续起伏。 */
    SINE("sine"),

    /** 三角：线性上升/下降。 */
    TRIANGLE("triangle"),

    /** 方波：通/断各半周期。 */
    SQUARE("square"),

    /** 锯齿：缓慢上升后瞬间回落。 */
    SAWTOOTH("sawtooth"),

    /** 阵列：周期内前 35% 输出、其余静默（节拍感最强）。 */
    BURST("burst"),
    ;

    companion object {
        fun fromId(id: String?): WaveShape = entries.firstOrNull { it.id == id }
            // 损坏/未知输入回退 FLAT（不调制）而不是 SINE：最保守方向（审计 S7）
            ?: FLAT
    }
}

/**
 * 波形配置（可持久化；所有字段以 [sanitized] 为准）。
 *
 * @param enabled 总开关；false 时包络恒 1（不做任何调制）。
 * @param shape 包络形状。
 * @param frequencyHz 包络频率 0.05–2.0Hz。链路侧强度指令约 2.5Hz/通道，>1Hz 会明显阶梯化，UI 收到 1.5Hz。
 * @param depthPercent 调制深度 0–100%：输出在 `base × (100% - depth%)` 与 `base × 100%` 之间摆动。
 * @param durationSec 持续时间（**每段 START 会话的时长**，不是总时长）；0 = 直到手动关闭/停止控制。
 *   disarm/急停/断连/引擎重启会结束会话，重新 START 从 0 重新计时；到期自动关闭（由服务层落状态）。
 */
data class WaveConfig(
    val enabled: Boolean = false,
    val shape: WaveShape = WaveShape.SINE,
    val frequencyHz: Float = DEFAULT_FREQUENCY_HZ,
    val depthPercent: Int = DEFAULT_DEPTH_PERCENT,
    val durationSec: Int = 0,
) {
    fun sanitized(): WaveConfig = copy(
        frequencyHz = if (frequencyHz.isFinite()) {
            frequencyHz.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
        } else {
            DEFAULT_FREQUENCY_HZ
        },
        depthPercent = depthPercent.coerceIn(0, 100),
        durationSec = durationSec.coerceIn(0, MAX_DURATION_SEC),
    )

    companion object {
        val Default = WaveConfig()
        const val MIN_FREQUENCY_HZ = 0.05f
        const val MAX_FREQUENCY_HZ = 2.0f
        const val DEFAULT_FREQUENCY_HZ = 0.5f
        const val DEFAULT_DEPTH_PERCENT = 60
        const val MAX_DURATION_SEC = 3600
    }
}

/** 纯函数包络（JVM 可测）。phase01 ∈ [0,1)，返回增益 ∈ [1-depth, 1]。 */
object WaveformMath {

    fun gainOf(shape: WaveShape, phase01: Float, depthPercent: Int): Float {
        val depth = (depthPercent.coerceIn(0, 100)) / 100f
        if (depth <= 0f || shape == WaveShape.FLAT) return 1f
        val phase = if (phase01.isFinite()) {
            // 规整到 [0,1)
            var p = phase01 % 1f
            if (p < 0f) p += 1f
            p
        } else {
            0f
        }
        val raw = when (shape) {
            WaveShape.FLAT -> 1f
            WaveShape.SINE -> (kotlin.math.sin(phase * 2.0 * Math.PI).toFloat() + 1f) / 2f
            WaveShape.TRIANGLE -> if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
            WaveShape.SQUARE -> if (phase < 0.5f) 1f else 0f
            WaveShape.SAWTOOTH -> phase
            WaveShape.BURST -> if (phase < 0.35f) 1f else 0f
        }
        // 只向下调制：depth=100% 时波谷为 0，depth=50% 时波谷为 base 的 50%
        return (1f - depth * (1f - raw)).coerceIn(0f, 1f)
    }
}

/** 一次调制的结果。[expired] 为 true 表示本次刚好触发“持续时间到期”，调用方负责自动关闭。 */
data class WaveTick(
    val output: Int,
    val gain: Float,
    val active: Boolean,
    val expired: Boolean,
)

/**
 * 波形会话引擎（非线程安全；由调用方持锁使用，与映射器同一把锁）。
 *
 * 语义：
 *  - `enabled=true` 且首次参与控制 tick 时开启会话（计时起点 = 该时刻）；
 *  - [tick] 按墙钟（单调时钟）推进相位；未启用/会话结束 → 增益 1、输出原样；
 *  - durationSec > 0 且到期 → 返回 `expired=true` 一次，会话结束（等待下次 START 重新开启）。
 */
class WaveformEngine {

    var config: WaveConfig = WaveConfig.Default
        private set

    private var sessionActive = false
    private var sessionStartMs = 0L

    /** 持续时间到期后的锁定：等待重新启用或显式 [beginSession]（如重新 START）才开新会话。 */
    private var sessionExpired = false

    val isSessionActive: Boolean get() = sessionActive

    fun setConfig(newConfig: WaveConfig) {
        val safe = newConfig.sanitized()
        val previous = config
        config = safe
        if (!safe.enabled && previous.enabled) {
            // 关闭开关立即结束会话
            endSession()
        }
        if (safe.enabled && !previous.enabled) {
            // 重新启用解除“已到期”锁定
            sessionExpired = false
        }
    }

    /** 开启/重开会话（计时与相位从 nowMs 重新开始）。 */
    fun beginSession(nowMs: Long) {
        sessionActive = true
        sessionStartMs = nowMs
        sessionExpired = false
    }

    fun endSession() {
        sessionActive = false
    }

    /** 会话剩余秒数（0 = 未启用/无限/已到期）；供 UI 显示。 */
    fun remainingSec(nowMs: Long): Int {
        if (!sessionActive || !config.enabled) return 0
        val durationMs = config.durationSec * 1000L
        if (durationMs <= 0L) return 0
        val remainMs = durationMs - (nowMs - sessionStartMs)
        return if (remainMs <= 0L) 0 else ((remainMs + 999) / 1000).toInt()
    }

    /**
     * 对调制前输出 [base] 施加包络。未启用/未在会话时原样返回。
     * 到期时本次仍返回到期前的输出并附 `expired=true`，下一次 tick 起增益恢复 1。
     */
    fun tick(base: Int, nowMs: Long): WaveTick {
        val safeBase = base.coerceAtLeast(0)
        val cfg = config
        if (!cfg.enabled) {
            sessionActive = false
            return WaveTick(safeBase, 1f, active = false, expired = false)
        }
        if (!sessionActive) {
            if (sessionExpired) {
                // 已到期：不自动重跑，等待重新启用开关或重新 START
                return WaveTick(safeBase, 1f, active = false, expired = false)
            }
            beginSession(nowMs)
        }
        val elapsedMs = (nowMs - sessionStartMs).coerceAtLeast(0L)
        val durationMs = cfg.durationSec * 1000L
        val expired = durationMs > 0L && elapsedMs >= durationMs
        if (expired) {
            endSession()
            sessionExpired = true
            return WaveTick(safeBase, 1f, active = false, expired = true)
        }
        val phase01 = (elapsedMs * cfg.frequencyHz / 1000.0).toFloat() % 1f
        val gain = WaveformMath.gainOf(cfg.shape, phase01, cfg.depthPercent)
        val output = (safeBase * gain + 0.5f).toInt().coerceIn(0, safeBase)
        return WaveTick(output, gain, active = true, expired = false)
    }
}
