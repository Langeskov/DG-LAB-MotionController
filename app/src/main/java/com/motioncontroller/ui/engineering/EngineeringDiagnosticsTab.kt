package com.motioncontroller.ui.engineering

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.components.CheckRow
import com.motioncontroller.ui.components.DataText
import com.motioncontroller.ui.components.EmptyHint
import com.motioncontroller.ui.components.KeyValueRow
import com.motioncontroller.ui.components.McButtonVariant
import com.motioncontroller.ui.components.McNotice
import com.motioncontroller.ui.components.MetricTile
import com.motioncontroller.ui.components.SegmentedSelector
import com.motioncontroller.ui.components.TacticalButton
import com.motioncontroller.ui.components.WarningBanner
import com.motioncontroller.ui.i18n.LanguageMode
import com.motioncontroller.ui.i18n.fmt
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.model.ControlModel
import com.motioncontroller.ui.model.DiagnosticsText
import com.motioncontroller.ui.model.formatAgeMs
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType

/** 错误行筛选关键词（与旧界面“告警/错误”语义保持一致）。 */
private fun isErrorLine(line: String): Boolean =
    line.contains("!") || line.contains("fail", ignoreCase = true) || line.contains("错误")

/**
 * SYSTEM DIAGNOSTICS：界面语言、报告导出、控制状态、急停传递阶段、连接诊断、性能指标、错误行、最近事件。
 *
 * 关键语义：急停只证明“控制器已发出 + APP 已回 resp”，设备端（BLE）确认本实现没有通道，
 * 必须明确显示 UNVERIFIED，不能声称设备已确认。
 */
@Composable
internal fun EngineeringDiagnosticsTab(
    state: UiState,
    appVersion: String,
    languageMode: LanguageMode,
    onLanguageModeChange: (LanguageMode) -> Unit,
    onCopyLogs: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val strings = rememberUiStrings()
    val link = state.link
    val pipeline = state.pipeline
    val control = ControlModel.derive(state, strings)
    val reportLines = remember(state, appVersion) {
        DiagnosticsText.build(state, appVersion).lineSequence().count()
    }
    val recentEvents = state.logs.takeLast(40).reversed()
    val errorEvents = remember(state.logs) { state.logs.filter(::isErrorLine) }

    EngineeringTabScaffold {
        EngineeringPanel(title = strings.languagePanelTitle, accent = McColors.SteelBlue) {
            SegmentedSelector(
                options = listOf(
                    strings.languageOptionSystem,
                    strings.languageOptionChinese,
                    strings.languageOptionEnglish,
                ),
                selectedIndex = when (languageMode) {
                    LanguageMode.SYSTEM -> 0
                    LanguageMode.ZH -> 1
                    LanguageMode.EN -> 2
                },
                onSelect = { index ->
                    onLanguageModeChange(
                        when (index) {
                            0 -> LanguageMode.SYSTEM
                            1 -> LanguageMode.ZH
                            else -> LanguageMode.EN
                        },
                    )
                },
                accent = McColors.SteelBlue,
            )
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.languagePanelTitle,
                body = strings.languageNote,
                color = McColors.SteelBlue,
            )
        }

        EngineeringPanel(title = strings.reportExport, accent = McColors.BlueAccent) {
            KeyValueRow(strings.appVersionLabel, appVersion)
            KeyValueRow(strings.logBuffer, fmt(strings.linesCount, state.logs.size))
            KeyValueRow(
                label = strings.reportSize,
                value = fmt(strings.reportReady, reportLines),
                valueColor = McColors.Success,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TacticalButton(
                    text = strings.copyLogs,
                    onClick = onCopyLogs,
                    modifier = Modifier.weight(1f),
                    variant = McButtonVariant.PRIMARY,
                    subtitle = strings.clipboard,
                    height = 48.dp,
                )
                TacticalButton(
                    text = strings.export,
                    onClick = onExportDiagnostics,
                    modifier = Modifier.weight(1f),
                    variant = McButtonVariant.OUTLINE,
                    subtitle = strings.diagnostics,
                    height = 48.dp,
                )
            }
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.reportNoticeTitle,
                body = strings.reportNoticeBody,
            )
        }

        EngineeringPanel(title = strings.controlState) {
            KeyValueRow(
                label = strings.armedLabel,
                value = if (state.armed) strings.yes else strings.no,
                valueColor = if (state.armed) McColors.BlueAccent else McColors.TextSecondary,
            )
            KeyValueRow(
                label = strings.phaseLabel,
                value = strings.controlPhase(control.phase),
                valueColor = if (control.estopLatched) {
                    McColors.Danger
                } else if (control.armed) {
                    McColors.BlueAccent
                } else {
                    McColors.TextSecondary
                },
            )
            KeyValueRow(strings.transition, state.controlTransition.name)
            KeyValueRow(
                label = strings.estopUi,
                value = if (state.emergencyStopped) strings.statusLatched else strings.statusClear,
                valueColor = if (state.emergencyStopped) McColors.Danger else McColors.TextDim,
            )
            KeyValueRow(
                label = strings.estopController,
                value = if (link.emergencyStopped) strings.statusLatched else strings.statusClear,
                valueColor = if (link.emergencyStopped) McColors.Danger else McColors.TextDim,
            )
            KeyValueRow(strings.targetApplied, "${state.targetIntensity} / ${state.currentIntensity}")
            if (control.blockers.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                SectionLabelPair(title = strings.startBlockers, lines = control.blockers)
            }
        }

        EngineeringPanel(title = strings.estopPropagation, accent = McColors.Warning) {
            KeyValueRow(
                label = strings.uiLatch,
                value = if (state.emergencyStopped) strings.statusLatched else strings.statusClear,
                valueColor = if (state.emergencyStopped) McColors.Danger else McColors.TextDim,
            )
            KeyValueRow(
                label = strings.controllerLatch,
                value = if (link.emergencyStopped) strings.statusLatched else strings.statusClear,
                valueColor = if (link.emergencyStopped) McColors.Danger else McColors.TextDim,
            )
            Spacer(Modifier.height(4.dp))
            CheckRow(
                label = strings.estopValueIssued,
                statusText = if (link.estopIssued) strings.yes else strings.no,
                color = if (link.estopIssued) McColors.Warning else McColors.TextDim,
            )
            CheckRow(
                label = strings.framesQueuedLabel,
                statusText = if (link.estopFramesQueued) strings.yes else strings.no,
                color = if (link.estopFramesQueued) McColors.Warning else McColors.TextDim,
            )
            CheckRow(
                label = strings.appAckedLabel,
                statusText = if (link.estopAppAcked) strings.yes else strings.no,
                color = if (link.estopAppAcked) McColors.Success else McColors.Warning,
                detail = strings.appAckedDetail,
            )
            KeyValueRow(strings.ackReason, link.estopAckReason ?: "--")
            KeyValueRow(strings.issuedAt, clockText(link.estopIssuedAtMs))
            Spacer(Modifier.height(8.dp))
            WarningBanner(
                title = strings.deviceUnverifiedTitle,
                body = strings.deviceUnverifiedBody,
                color = McColors.Warning,
            )
        }

        // 连接诊断（relay 层新增字段；null 显示 "--"）
        EngineeringPanel(title = strings.connectionDiagnosticsTitle) {
            KeyValueRow(
                label = strings.handshakeCount,
                value = link.handshakeCount.toString(),
                valueColor = if (link.handshakeCount > 0) McColors.SteelBlue else McColors.TextSecondary,
            )
            KeyValueRow(
                label = strings.lastHandshake,
                value = link.lastHandshakeSummary ?: "--",
                valueColor = if (link.lastHandshakeSummary != null) McColors.TextPrimary else McColors.TextDim,
            )
            KeyValueRow(
                label = strings.lastRejectReasonLabel,
                value = link.lastRejectReason ?: "--",
                valueColor = if (link.lastRejectReason != null) McColors.Warning else McColors.TextDim,
            )
            KeyValueRow(
                label = strings.lastCloseCodeLabel,
                value = link.lastCloseCode?.toString() ?: "--",
                valueColor = if (link.lastCloseCode != null) McColors.SteelBlue else McColors.TextDim,
            )
            KeyValueRow(
                label = strings.lastCloseReasonLabel,
                value = link.lastCloseReason?.ifBlank { "--" } ?: "--",
                valueColor = if (link.lastCloseReason != null) McColors.TextPrimary else McColors.TextDim,
            )
        }

        EngineeringPanel(title = strings.performance) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    label = strings.inference,
                    value = pipeline.lastInferenceMs.toString(),
                    modifier = Modifier.weight(1f),
                    unit = "ms",
                    accent = McColors.BlueAccent,
                )
                MetricTile(
                    label = strings.buffer,
                    value = pipeline.bufferedFrames.toString(),
                    modifier = Modifier.weight(1f),
                    unit = "/150",
                    accent = McColors.SteelBlue,
                )
                MetricTile(
                    label = strings.predictionAge,
                    value = formatAgeMs(state.predictionAgeMs),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    label = strings.gpsFixAge,
                    value = formatAgeMs(pipeline.gpsFixAgeMs),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = strings.outboundQueue,
                    value = link.queuedOutboundCount.toString(),
                    modifier = Modifier.weight(1f),
                    unit = strings.unitFrames,
                    accent = if (link.queuedOutboundCount > 0) McColors.Warning else McColors.Border,
                )
                MetricTile(
                    label = strings.dropped,
                    value = link.droppedOpCount.toString(),
                    modifier = Modifier.weight(1f),
                    unit = strings.unitOps,
                    accent = if (link.droppedOpCount > 0L) McColors.Warning else McColors.Border,
                )
            }
        }

        EngineeringPanel(
            title = fmt(strings.errorsTitle, errorEvents.size),
            accent = if (errorEvents.isNotEmpty()) McColors.Warning else null,
        ) {
            if (errorEvents.isEmpty()) {
                EmptyHint(strings.noErrorLines)
            } else {
                errorEvents.takeLast(40).reversed().forEach { line ->
                    Text(
                        text = line,
                        style = McType.dataSmall,
                        color = McColors.Warning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.filterTitle,
                body = strings.filterBody,
            )
        }

        EngineeringPanel(title = fmt(strings.recentEventsTitle, recentEvents.size, state.logs.size)) {
            if (recentEvents.isEmpty()) {
                EmptyHint(strings.noEvents)
            } else {
                recentEvents.forEach { line ->
                    Text(
                        text = line,
                        style = McType.dataSmall,
                        color = McColors.TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.orderTitle,
                body = strings.orderBody,
            )
        }
    }
}

/** 小标题 + 多行等宽文本（启动阻塞原因等）。 */
@Composable
private fun SectionLabelPair(title: String, lines: List<String>) {
    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
        com.motioncontroller.ui.components.SectionLabel(title)
        Spacer(Modifier.height(2.dp))
        lines.forEach { line ->
            DataText(
                text = "· $line",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                color = McColors.Warning,
                style = McType.dataSmall,
            )
        }
    }
}
