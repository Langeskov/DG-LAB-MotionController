package com.motioncontroller.ui.engineering

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motioncontroller.ml.MotionClass
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.components.CheckRow
import com.motioncontroller.ui.components.DataText
import com.motioncontroller.ui.components.KeyValueRow
import com.motioncontroller.ui.components.McNotice
import com.motioncontroller.ui.components.McTag
import com.motioncontroller.ui.components.MeterBar
import com.motioncontroller.ui.components.WarningBanner
import com.motioncontroller.ui.i18n.fmt
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.model.MotionDisplayModel
import com.motioncontroller.ui.model.SpeedModel
import com.motioncontroller.ui.model.SpeedStatus
import com.motioncontroller.ui.model.formatAgeMs
import com.motioncontroller.ui.model.formatSpeedKmh
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType

/**
 * MOTION RECOGNITION：模型文件/加载状态、5 个真实类别、稳定类别、置信度、smoothedProbs、
 * IMU/GNSS 可用性、推理管线诊断。
 *
 * 类别严格等于 [MotionClass.entries]，不新增/重排类别。
 */
@Composable
internal fun EngineeringMotionTab(state: UiState) {
    val strings = rememberUiStrings()
    val pipeline = state.pipeline
    val prediction = state.prediction
    val model = MotionDisplayModel.model(state)
    val speed = SpeedModel.derive(pipeline)
    val pipelineError = pipeline.lastError

    EngineeringTabScaffold {
        EngineeringPanel(title = strings.modelPanelTitle) {
            KeyValueRow(
                label = strings.modelFile,
                value = "motion_encoder.onnx",
                valueColor = McColors.SteelBlue,
            )
            CheckRow(
                label = strings.modelSession,
                statusText = if (pipeline.modelLoaded) strings.statusLoaded else strings.statusNotLoaded,
                color = availabilityColor(pipeline.modelLoaded),
                detail = strings.modelSessionDetail,
            )
            CheckRow(
                label = strings.scalerLabel,
                statusText = if (pipeline.scalerValid) strings.statusValid else strings.statusInvalid,
                color = availabilityColor(pipeline.scalerValid),
                detail = "assets/scaler.json",
            )
            CheckRow(
                label = strings.pipelineLabel,
                statusText = if (pipeline.running) strings.statusRunning else strings.statusStopped,
                color = availabilityColor(pipeline.running),
            )
            if (!pipeline.scalerValid) {
                Spacer(Modifier.height(8.dp))
                WarningBanner(
                    title = strings.scalerInvalidTitle,
                    body = strings.scalerInvalidBody,
                    color = McColors.Warning,
                )
            }
            if (pipelineError != null) {
                Spacer(Modifier.height(8.dp))
                WarningBanner(title = strings.pipelineErrorTitle, body = pipelineError, color = McColors.Warning)
            }
        }

        EngineeringPanel(title = strings.stableClassPanel, accent = McColors.BlueAccent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DataText(
                    text = model.codeText,
                    color = if (model.available) McColors.BlueAccent else McColors.TextDim,
                    style = McType.dataLarge,
                )
                McTag(
                    text = if (model.available) strings.tagStable else strings.tagAwaiting,
                    color = if (model.available) McColors.Success else McColors.Warning,
                )
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            DataText(
                text = if (model.available) strings.motionClassLabel(model.classIndex) else strings.waitingForRecognition,
                modifier = Modifier.fillMaxWidth(),
                color = if (model.available) McColors.TextPrimary else McColors.TextDim,
                style = McType.dataMedium,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            KeyValueRow(
                label = strings.modelIndex,
                value = if (state.stableClass >= 0) state.stableClass.toString() else strings.awaitingValue,
            )
            KeyValueRow(
                label = strings.effectiveClassLabel,
                value = MotionClass.of(state.effectiveClass)
                    ?.let { "${it.name} / ${strings.motionClassLabel(state.effectiveClass)}" } ?: "--",
                valueColor = McColors.SteelBlue,
            )
            KeyValueRow(
                label = strings.mappingSourceLabel,
                value = if (state.manualClass != null) strings.manualOverride else strings.mappingSourceAuto,
                valueColor = if (state.manualClass != null) McColors.Warning else McColors.BlueAccent,
            )
            if (state.manualClass != null) {
                Spacer(Modifier.height(6.dp))
                McNotice(
                    title = strings.manualOverrideActiveTitle,
                    body = fmt(strings.manualOverrideActiveBody, state.manualClass),
                )
            }
        }

        EngineeringPanel(title = strings.currentPrediction) {
            KeyValueRow(
                label = strings.predictedClass,
                value = prediction?.motionClass?.let { "${it.name} / ${strings.motionClassLabel(it.ordinal)}" } ?: "--",
                valueColor = McColors.SteelBlue,
            )
            KeyValueRow(
                label = strings.confidence,
                value = prediction?.let { percentText(it.confidence) } ?: "--",
                valueColor = McColors.BlueAccent,
            )
            KeyValueRow(
                label = strings.gyroMean,
                value = prediction?.let { "${decimalText(it.gyroMean, 4)} rad/s" } ?: "--",
            )
            KeyValueRow(
                label = strings.accStd,
                value = prediction?.let { "${decimalText(it.accStd, 4)} m/s²" } ?: "--",
            )
            KeyValueRow(
                label = strings.windowEnd,
                value = prediction?.let { fmt(strings.windowEndValue, it.timestampMs) } ?: "--",
            )
            Spacer(Modifier.height(4.dp))
            McNotice(
                title = strings.windowNoticeTitle,
                body = strings.windowNoticeBody,
            )
        }

        EngineeringPanel(title = strings.classProbabilities) {
            MotionClass.entries.forEachIndexed { index, motionClass ->
                val rawProbability = state.smoothedProbs.getOrElse(index) { 0f }
                val probability = if (rawProbability.isFinite()) rawProbability.coerceIn(0f, 1f) else 0f
                val isStable = index == state.stableClass
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DataText(
                        text = "$index ${motionClass.name}",
                        modifier = Modifier.width(96.dp),
                        color = if (isStable) McColors.BlueAccent else McColors.TextSecondary,
                        style = McType.dataSmall,
                    )
                    MeterBar(
                        fraction = probability,
                        modifier = Modifier.weight(1f),
                        color = if (isStable) McColors.BlueAccent else McColors.SteelBlueDim,
                        height = 8.dp,
                    )
                    DataText(
                        text = percentText(probability),
                        modifier = Modifier.width(52.dp),
                        color = if (isStable) McColors.BlueAccent else McColors.TextSecondary,
                        style = McType.dataSmall,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            McNotice(
                title = strings.emaNoticeTitle,
                body = fmt(strings.emaNoticeBody, decimalText(state.mapping.probEma, 2)),
            )
        }

        EngineeringPanel(title = strings.classIndexMap) {
            MotionClass.entries.forEachIndexed { index, motionClass ->
                val base = state.mapping.classBase.getOrElse(index) { 0f }
                KeyValueRow(
                    label = "$index ${motionClass.name}",
                    value = fmt(strings.classBaseValue, strings.motionClassLabel(index), base.toInt()),
                    valueColor = if (index == state.stableClass) McColors.BlueAccent else McColors.TextPrimary,
                )
            }
        }

        EngineeringPanel(title = strings.imuGnssPanel) {
            CheckRow(
                label = strings.gyroLabel,
                statusText = if (pipeline.gyroAvailable) strings.statusAvailable else strings.statusMissing,
                color = availabilityColor(pipeline.gyroAvailable),
                detail = if (pipeline.gyroAvailable) {
                    null
                } else {
                    strings.gyroMissingDetail
                },
            )
            CheckRow(
                label = strings.rotationVector,
                statusText = if (pipeline.rotationAvailable) strings.statusAvailable else strings.statusMissing,
                color = availabilityColor(pipeline.rotationAvailable),
                detail = if (pipeline.rotationAvailable) {
                    null
                } else {
                    strings.rotationMissingDetail
                },
            )
            Spacer(Modifier.height(4.dp))
            CheckRow(
                label = strings.gnssFix,
                statusText = strings.speedDetail(speed.status),
                color = when (speed.status) {
                    SpeedStatus.LIVE -> McColors.Success
                    SpeedStatus.STALE -> McColors.Warning
                    SpeedStatus.UNAVAILABLE -> McColors.TextDim
                },
                detail = fmt(
                    strings.gnssFixDetail,
                    if (pipeline.gpsEverFix) strings.trueText else strings.falseText,
                    formatAgeMs(pipeline.gpsFixAgeMs),
                ),
            )
            KeyValueRow(
                label = strings.gnssSpeedLabel,
                value = if (speed.hasValue) {
                    "${formatSpeedKmh(pipeline.gpsSpeedKmh)} km/h${if (speed.isLive) "" else strings.staleSuffix}"
                } else {
                    "--"
                },
                valueColor = when {
                    speed.isLive -> McColors.Success
                    speed.hasValue -> McColors.Warning
                    else -> McColors.TextDim
                },
            )
            KeyValueRow(strings.gpsFixAge, formatAgeMs(pipeline.gpsFixAgeMs))
            KeyValueRow(
                strings.gpsEverFix,
                if (pipeline.gpsEverFix) strings.trueText else strings.falseText,
            )
            if (!pipeline.gpsAvailable) {
                Spacer(Modifier.height(6.dp))
                McNotice(
                    title = strings.gnssWarningTitle,
                    body = strings.gnssWarningBody,
                )
            }
        }

        EngineeringPanel(title = strings.inferencePipeline) {
            KeyValueRow(strings.bufferedFrames, "${pipeline.bufferedFrames} / 150")
            Spacer(Modifier.height(2.dp))
            MeterBar(
                fraction = pipeline.bufferedFrames / 150f,
                color = McColors.SteelBlue,
                height = 8.dp,
            )
            Spacer(Modifier.height(6.dp))
            KeyValueRow(
                label = strings.lastInference,
                value = "${pipeline.lastInferenceMs} ms",
                valueColor = McColors.BlueAccent,
            )
            KeyValueRow(strings.predictionAge, formatAgeMs(state.predictionAgeMs))
            CheckRow(
                label = strings.recognitionLabel,
                statusText = if (state.recognitionPaused) strings.statusPaused else strings.statusActive,
                color = availabilityColor(!state.recognitionPaused),
                detail = if (state.recognitionPaused) {
                    strings.recognitionPausedDetail
                } else {
                    null
                },
            )
            KeyValueRow(
                label = strings.restOverrideLabel,
                value = if (state.restOverrideActive) strings.statusActive else strings.statusInactive,
                valueColor = if (state.restOverrideActive) McColors.SteelBlue else McColors.TextDim,
            )
            Spacer(Modifier.height(4.dp))
            McNotice(
                title = strings.restRuleTitle,
                body = fmt(
                    strings.restRuleBody,
                    decimalText(state.mapping.restGyroThreshold, 3),
                    decimalText(state.mapping.restAccStdThreshold, 3),
                    state.mapping.restWindows,
                ),
            )
            if (state.recognitionPaused) {
                Spacer(Modifier.height(8.dp))
                WarningBanner(
                    title = strings.recognitionPausedTitle,
                    body = strings.recognitionPausedBody,
                    color = McColors.Warning,
                )
            }
        }
    }
}
