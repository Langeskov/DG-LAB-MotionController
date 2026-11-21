package com.motioncontroller.ui.operation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motioncontroller.mapping.WaveConfig
import com.motioncontroller.mapping.WaveShape
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.components.DataText
import com.motioncontroller.ui.components.McPanel
import com.motioncontroller.ui.components.McSlider
import com.motioncontroller.ui.components.McTag
import com.motioncontroller.ui.components.MeterBar
import com.motioncontroller.ui.components.SectionLabel
import com.motioncontroller.ui.components.SegmentedSelector
import com.motioncontroller.ui.components.StatusLamp
import com.motioncontroller.ui.i18n.UiStrings
import com.motioncontroller.ui.i18n.fmt
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType

/** 形状选项（顺序即 SegmentedSelector 索引；文案经 UiStrings.waveShapeName 映射）。 */
private val ShapeOptions = listOf(
    WaveShape.FLAT,
    WaveShape.SINE,
    WaveShape.TRIANGLE,
    WaveShape.SQUARE,
    WaveShape.SAWTOOTH,
    WaveShape.BURST,
)

/** 持续时间选项（秒；0 = 直到手动关闭）。 */
private val DurationOptions = listOf(0, 30, 60, 300)

/**
 * 本地波形面板（**强度包络合成 / INTENSITY ENVELOPE**）。
 *
 * 诚实标注（真机反馈："进入控制后波形还是由 DG-LAB APP 控制"）：
 *  V4 外部控制协议只提供强度指令，**无法切换/下发 APP 的脉冲波形**；
 *  本面板控制的是本机合成的“强度包络”——对映射输出做周期性向下调制，叠加在 APP 波形之上。
 *  建议在 DG-LAB APP 里选择「连续/恒定」类波形作载波，由这里决定节奏。
 *
 * V2.0 布局：主界面默认只显示紧凑摘要（形状/频率/深度/持续时间 + 运行状态 + 诚实标注），
 * 点击标题展开后调整参数；为未来的「设备脉冲波形（DevicePulseProfile）」预留独立入口位置，
 * 但在协议未验证前不提供任何设备波形控件。
 */
@Composable
fun WavePanel(
    state: UiState,
    onWaveConfig: (WaveConfig) -> Unit,
) {
    val strings = rememberUiStrings()
    val wave = state.wave
    val active = state.waveActive
    var expanded by rememberSaveable { mutableStateOf(false) }
    val statusColor = when {
        state.emergencyStopped -> McColors.Danger
        active -> McColors.BlueAccent
        wave.enabled -> McColors.IceBlue
        else -> McColors.TextDim
    }
    val statusText = when {
        state.emergencyStopped -> strings.estopLatched
        active -> strings.waveStatusModulating
        wave.enabled -> strings.waveStatusEnabled
        else -> strings.waveStatusOff
    }
    val valueColor = if (wave.enabled) McColors.TextPrimary else McColors.TextDim

    McPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = if (active) McColors.BlueAccent.copy(alpha = 0.35f) else null,
        contentPadding = 10.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(strings.wavePanelTitle, color = McColors.TextDim)
            Spacer(Modifier.weight(1f))
            StatusLamp(color = statusColor, size = 6.dp)
            Spacer(Modifier.width(5.dp))
            McTag(statusText, statusColor)
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "▲" else "▼", style = McType.dataSmall, color = McColors.TextSecondary)
        }

        // 摘要：主界面始终可看到当前包络配置（不把关键状态藏进展开层）。
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MetricChip(
                label = strings.waveShapeTitle,
                value = strings.waveShapeName(wave.shape),
                valueColor = valueColor,
                modifier = Modifier.weight(1f),
            )
            MetricChip(
                label = strings.frequency,
                value = String.format(java.util.Locale.US, "%.2fHz", wave.frequencyHz),
                valueColor = valueColor,
                modifier = Modifier.weight(1f),
            )
            MetricChip(
                label = strings.depth,
                value = "${wave.depthPercent}%",
                valueColor = valueColor,
                modifier = Modifier.weight(1f),
            )
            MetricChip(
                label = strings.duration,
                value = if (wave.durationSec == 0) "∞" else "${wave.durationSec}s",
                valueColor = valueColor,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            strings.wavePanelShortNote,
            style = McType.caption,
            color = McColors.TextDim,
        )

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(thickness = 1.dp, color = McColors.Divider)
            Spacer(Modifier.height(6.dp))
            Text(
                strings.wavePanelDesc,
                style = McType.caption,
                color = McColors.TextDim,
            )
            Spacer(Modifier.height(7.dp))

            // 开关（启用即在 START 后随控制循环开始计时/相位）
            SegmentedSelector(
                options = listOf(strings.off, strings.on),
                selectedIndex = if (wave.enabled) 1 else 0,
                onSelect = { onWaveConfig(wave.copy(enabled = it == 1)) },
                accent = McColors.BlueAccent,
            )

            if (wave.enabled) {
                Spacer(Modifier.height(7.dp))
                SectionLabel(strings.waveShapeTitle, color = McColors.TextDim)
                Spacer(Modifier.height(4.dp))
                SegmentedSelector(
                    options = ShapeOptions.map { strings.waveShapeName(it) },
                    selectedIndex = ShapeOptions.indexOf(wave.shape).coerceAtLeast(0),
                    onSelect = { index ->
                        ShapeOptions.getOrNull(index)?.let { onWaveConfig(wave.copy(shape = it)) }
                    },
                    height = 32.dp,
                    accent = McColors.BlueAccent,
                )

                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(strings.frequency, color = McColors.TextDim, modifier = Modifier.width(64.dp))
                    McSlider(
                        value = wave.frequencyHz,
                        onValueChange = { onWaveConfig(wave.copy(frequencyHz = it)) },
                        valueRange = 0.1f..1.5f,
                        steps = 13,
                        modifier = Modifier.weight(1f),
                        accent = McColors.BlueAccent,
                    )
                    Spacer(Modifier.width(8.dp))
                    DataText(
                        text = String.format(java.util.Locale.US, "%.2f Hz", wave.frequencyHz),
                        style = McType.dataSmall,
                        color = McColors.IceBlue,
                        modifier = Modifier.width(72.dp),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(strings.depth, color = McColors.TextDim, modifier = Modifier.width(64.dp))
                    McSlider(
                        value = wave.depthPercent.toFloat(),
                        onValueChange = { onWaveConfig(wave.copy(depthPercent = it.toInt())) },
                        valueRange = 0f..100f,
                        steps = 19,
                        modifier = Modifier.weight(1f),
                        accent = McColors.BlueAccent,
                    )
                    Spacer(Modifier.width(8.dp))
                    DataText(
                        text = "${wave.depthPercent}%",
                        style = McType.dataSmall,
                        color = McColors.IceBlue,
                        modifier = Modifier.width(72.dp),
                    )
                }

                Spacer(Modifier.height(4.dp))
                SectionLabel(strings.duration, color = McColors.TextDim)
                Spacer(Modifier.height(4.dp))
                // 预设外的合法时长（如持久化的 120s）不高亮任何段（-1），并在下方标注自定义值。
                val durationIndex = DurationOptions.indexOf(wave.durationSec)
                SegmentedSelector(
                    options = DurationOptions.map { durationOptionLabel(strings, it) },
                    selectedIndex = durationIndex,
                    onSelect = { index ->
                        DurationOptions.getOrNull(index)?.let { onWaveConfig(wave.copy(durationSec = it)) }
                    },
                    height = 32.dp,
                    accent = McColors.BlueAccent,
                )
                if (durationIndex < 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        fmt(strings.waveDurationCustom, customDurationText(wave.durationSec)),
                        style = McType.caption,
                        color = McColors.Warning,
                    )
                }

                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(strings.envelope, color = McColors.TextDim, modifier = Modifier.width(64.dp))
                    MeterBar(
                        fraction = state.waveGain.coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        color = McColors.BlueAccent,
                        height = 6.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    DataText(
                        text = "${(state.waveGain * 100).toInt()}%",
                        style = McType.dataSmall,
                        color = McColors.TextSecondary,
                        modifier = Modifier.width(72.dp),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MetricChip(
                        label = strings.waveBaseOutput,
                        value = "${state.waveBaseIntensity}",
                        modifier = Modifier.weight(1f),
                    )
                    MetricChip(
                        label = strings.waveModOutput,
                        value = "${state.targetIntensity}",
                        modifier = Modifier.weight(1f),
                    )
                    MetricChip(
                        label = strings.waveRemaining,
                        value = if (wave.durationSec == 0) "∞" else "${state.waveRemainingSec}s",
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!state.armed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        strings.waveArmedNote,
                        style = McType.caption,
                        color = McColors.TextDim,
                    )
                }
            }
        }
    }
}

/** 自定义/未预设时长文案（如 120s）。 */
private fun customDurationText(seconds: Int): String = "${seconds}s"

/** 持续时间选项文案（0 = 持续直到手动关闭）。 */
private fun durationOptionLabel(strings: UiStrings, seconds: Int): String = when (seconds) {
    0 -> strings.waveDurationContinuous
    30 -> strings.waveDuration30
    60 -> strings.waveDuration60
    300 -> strings.waveDuration300
    else -> customDurationText(seconds)
}

/** 小型读数块（波形摘要/包络读数）。 */
@Composable
private fun MetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = McColors.TextPrimary,
) {
    Column(
        modifier = modifier
            .background(McColors.SurfaceInset, RoundedCornerShape(2.dp))
            .border(1.dp, McColors.Border.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        SectionLabel(label, color = McColors.TextDim, maxLines = 2)
        Spacer(Modifier.height(1.dp))
        DataText(text = value, style = McType.dataMedium, color = valueColor)
    }
}
