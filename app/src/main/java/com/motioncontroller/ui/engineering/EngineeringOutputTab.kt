package com.motioncontroller.ui.engineering

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.motioncontroller.mapping.MappingConfig
import com.motioncontroller.ml.MotionClass
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.components.DataText
import com.motioncontroller.ui.components.KeyValueRow
import com.motioncontroller.ui.components.McNotice
import com.motioncontroller.ui.components.McSlider
import com.motioncontroller.ui.components.SectionLabel
import com.motioncontroller.ui.components.WarningBanner
import com.motioncontroller.ui.i18n.UiStrings
import com.motioncontroller.ui.i18n.fmt
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType
import kotlin.math.roundToInt

/**
 * OUTPUT CONFIGURATION：classBase / manualOffset / safetyMax 可调，其余映射参数只读；
 * A/B 通道三态强度；明确标注本工程**未实现**波形/频率下发。
 */
@Composable
internal fun EngineeringOutputTab(
    state: UiState,
    onApplyMapping: (MappingConfig) -> Unit,
    onManualOffset: (Float) -> Unit,
) {
    val strings = rememberUiStrings()
    val mapping = state.mapping
    val link = state.link
    val classCount = MotionClass.entries.size

    var offsetDraft by remember(state.manualOffset, mapping.manualLimit) {
        mutableStateOf(state.manualOffset)
    }
    var safetyMaxDraft by remember(mapping.safetyMax) {
        mutableStateOf(mapping.safetyMax.toFloat())
    }
    val classBaseDraft = remember(mapping.classBase) {
        mutableStateListOf<Float>().apply {
            for (index in 0 until MotionClass.entries.size) {
                add(mapping.classBase.getOrElse(index) { 0f })
            }
        }
    }
    val offsetLimit = mapping.manualLimit.coerceAtLeast(1f)

    EngineeringTabScaffold {
        WarningBanner(
            title = strings.waveNotImplementedTitle,
            body = strings.waveNotImplementedBody,
            color = McColors.Warning,
        )

        EngineeringPanel(title = strings.supportedOps) {
            KeyValueRow("t=3", strings.opAddIntensity, valueColor = McColors.BlueAccent)
            KeyValueRow("t=7", strings.opSetIntensity, valueColor = McColors.BlueAccent)
            KeyValueRow("clear", strings.opClear, valueColor = McColors.BlueAccent)
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.notSentTitle,
                body = strings.notSentBody,
            )
        }

        EngineeringPanel(title = strings.channelIntensity, accent = McColors.BlueAccent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(strings.valueLabel, modifier = Modifier.weight(1f))
                DataText(
                    text = strings.channelA,
                    modifier = Modifier.width(64.dp),
                    color = McColors.BlueAccent,
                    style = McType.dataSmall,
                )
                DataText(
                    text = strings.channelB,
                    modifier = Modifier.width(64.dp),
                    color = McColors.BlueAccent,
                    style = McType.dataSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            ChannelValueRow(
                label = strings.sentTarget,
                channelA = link.targetIntensityA.toString(),
                channelB = link.targetIntensityB.toString(),
                valueColor = McColors.BlueAccent,
            )
            ChannelValueRow(
                label = strings.estimated,
                channelA = link.estimateIntensityA.toString(),
                channelB = link.estimateIntensityB.toString(),
                valueColor = McColors.SteelBlue,
            )
            ChannelValueRow(
                label = strings.appReported,
                channelA = link.reportedIntensityA?.toString() ?: "--",
                channelB = link.reportedIntensityB?.toString() ?: "--",
                valueColor = McColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.threeValuesTitle,
                body = strings.threeValuesBody,
            )
            Spacer(Modifier.height(4.dp))
            KeyValueRow(
                label = strings.mappingTarget,
                value = state.targetIntensity.toString(),
                valueColor = McColors.BlueAccent,
            )
            KeyValueRow(
                label = strings.appliedSlew,
                value = state.currentIntensity.toString(),
                valueColor = McColors.BlueAccent,
            )
        }

        EngineeringPanel(title = strings.classBasePanel) {
            MotionClass.entries.forEachIndexed { index, motionClass ->
                val value = classBaseDraft.getOrElse(index) { 0f }.coerceIn(0f, 100f)
                val isEffective = index == state.effectiveClass
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DataText(
                        text = "$index ${motionClass.name} / ${strings.motionClassLabel(index)}",
                        modifier = Modifier.weight(1f),
                        color = if (isEffective) McColors.BlueAccent else McColors.TextSecondary,
                        style = McType.dataSmall,
                    )
                    DataText(
                        text = value.roundToInt().toString(),
                        color = McColors.BlueAccent,
                        style = McType.dataMedium,
                    )
                }
                McSlider(
                    value = value,
                    onValueChange = { raw ->
                        if (index < classBaseDraft.size) {
                            classBaseDraft[index] = raw.coerceIn(0f, 100f)
                        }
                    },
                    valueRange = 0f..100f,
                    steps = 99,
                    onValueChangeFinished = {
                        val updated = (0 until classCount).map { i ->
                            classBaseDraft.getOrElse(i) { 0f }.coerceIn(0f, 100f)
                        }
                        onApplyMapping(mapping.copy(classBase = updated))
                    },
                )
            }
            McNotice(
                title = strings.classBaseTitle,
                body = strings.classBaseBody,
            )
        }

        EngineeringPanel(title = strings.manualOffsetPanel) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(
                    fmt(strings.offsetLimitLabel, mapping.manualLimit.roundToInt()),
                    modifier = Modifier.weight(1f),
                )
                DataText(
                    text = signedIntText(offsetDraft),
                    color = if (offsetDraft == 0f) McColors.TextSecondary else McColors.BlueAccent,
                    style = McType.dataLarge,
                )
            }
            McSlider(
                value = offsetDraft,
                onValueChange = { raw ->
                    val clamped = raw.coerceIn(-offsetLimit, offsetLimit)
                    offsetDraft = clamped
                    onManualOffset(clamped)
                },
                valueRange = -offsetLimit..offsetLimit,
                steps = 0,
            )
            McNotice(
                title = strings.manualOffsetTitle,
                body = strings.manualOffsetBody,
            )
        }

        EngineeringPanel(title = strings.safetyMaxPanel, accent = McColors.Warning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(strings.safetyLimitLabel, modifier = Modifier.weight(1f))
                DataText(
                    text = safetyMaxDraft.roundToInt().toString(),
                    color = McColors.BlueAccent,
                    style = McType.dataLarge,
                )
            }
            McSlider(
                value = safetyMaxDraft,
                onValueChange = { raw -> safetyMaxDraft = raw.coerceIn(0f, 200f) },
                valueRange = 0f..200f,
                steps = 39,
                onValueChangeFinished = {
                    onApplyMapping(
                        mapping.copy(safetyMax = safetyMaxDraft.roundToInt().coerceIn(0, 200)),
                    )
                },
            )
            McNotice(
                title = strings.safetyLimitLabel,
                body = strings.safetyLimitBody,
            )
        }

        EngineeringPanel(title = strings.mappingParams) {
            KeyValueRow(strings.manualLimit, "±${mapping.manualLimit.roundToInt()}")
            KeyValueRow(strings.switchWindows, mapping.switchWindows.toString())
            KeyValueRow(strings.probEmaLabel, decimalText(mapping.probEma, 2))
            KeyValueRow(strings.slewUp, "${decimalText(mapping.slewPerSecond, 0)} /s")
            KeyValueRow(strings.slewDown, "${decimalText(mapping.slewDownPerSecond, 0)} /s")
            KeyValueRow(
                label = strings.restOverrideLabel,
                value = if (mapping.restOverrideEnabled) strings.statusEnabled else strings.statusDisabled,
                valueColor = if (mapping.restOverrideEnabled) McColors.SteelBlue else McColors.TextDim,
            )
            KeyValueRow(strings.restGyroThr, "${decimalText(mapping.restGyroThreshold, 3)} rad/s")
            KeyValueRow(strings.restAccThr, "${decimalText(mapping.restAccStdThreshold, 3)} m/s²")
            KeyValueRow(strings.restWindows, mapping.restWindows.toString())
            KeyValueRow(
                label = strings.restClasses,
                value = mapping.restOverrideClasses
                    .sorted()
                    .mapNotNull { MotionClass.of(it)?.name }
                    .joinToString(", ")
                    .ifBlank { "--" },
                valueColor = McColors.SteelBlue,
            )
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.readOnlyTitle,
                body = strings.readOnlyBody,
            )
        }

        EngineeringPanel(title = strings.localWavePanel) {
            KeyValueRow(
                label = strings.enabledLabel,
                value = if (state.wave.enabled) strings.on else strings.off,
                valueColor = if (state.wave.enabled) McColors.BlueAccent else McColors.TextDim,
            )
            KeyValueRow(strings.shapeLabel, strings.waveShapeName(state.wave.shape))
            KeyValueRow(strings.frequency, "${decimalText(state.wave.frequencyHz, 2)} Hz")
            KeyValueRow(strings.depth, "${state.wave.depthPercent}%")
            KeyValueRow(
                strings.duration,
                if (state.wave.durationSec == 0) {
                    strings.waveDurationContinuous
                } else {
                    "${state.wave.durationSec}s"
                },
            )
            KeyValueRow(
                label = strings.sessionLabel,
                value = if (state.waveActive) strings.statusActive else strings.statusIdle,
                valueColor = if (state.waveActive) McColors.BlueAccent else McColors.TextDim,
            )
            KeyValueRow(
                strings.gainBaseLabel,
                "${(state.waveGain * 100).toInt()}% / ${state.waveBaseIntensity}",
            )
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.protocolLimitTitle,
                body = strings.protocolLimitBody,
            )
        }
    }
}

/** A/B 双通道的一行只读数值。 */
@Composable
private fun ChannelValueRow(
    label: String,
    channelA: String,
    channelB: String,
    valueColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(label, modifier = Modifier.weight(1f))
        DataText(
            text = channelA,
            modifier = Modifier.width(64.dp),
            color = valueColor,
            style = McType.dataMedium,
        )
        DataText(
            text = channelB,
            modifier = Modifier.width(64.dp),
            color = valueColor,
            style = McType.dataMedium,
        )
    }
}

/** 带符号整数文案（手动偏移）：+N / -N / 0。 */
private fun signedIntText(value: Float): String {
    if (!value.isFinite()) return "--"
    val rounded = value.roundToInt()
    return if (rounded > 0) "+$rounded" else rounded.toString()
}
