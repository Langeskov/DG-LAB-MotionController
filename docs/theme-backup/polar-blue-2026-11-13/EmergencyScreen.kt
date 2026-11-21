package com.motioncontroller.ui.emergency

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motioncontroller.service.EstopResetCheck
import com.motioncontroller.service.EstopResetCheckStatus
import com.motioncontroller.service.EstopResetPhase
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.components.DataText
import com.motioncontroller.ui.components.McConfirmDialog
import com.motioncontroller.ui.components.McPanel
import com.motioncontroller.ui.components.McTag
import com.motioncontroller.ui.components.SectionLabel
import com.motioncontroller.ui.components.StatusLamp
import com.motioncontroller.ui.components.TacticalButton
import com.motioncontroller.ui.components.WarningBanner
import com.motioncontroller.ui.components.rememberReduceMotion
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.i18n.uiCase
import com.motioncontroller.ui.model.EmergencyModel
import com.motioncontroller.ui.model.EmergencyNodeId
import com.motioncontroller.ui.model.EmergencyNodeStatus
import com.motioncontroller.ui.model.emergencyNodeLabel
import com.motioncontroller.ui.model.emergencyStatusLabel
import com.motioncontroller.ui.model.estopResetBlockerText
import com.motioncontroller.ui.model.estopResetCheckStatus
import com.motioncontroller.ui.model.estopResetFailureText
import com.motioncontroller.ui.model.resetCheckLabel
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType
import com.motioncontroller.ui.theme.cjkTuned

/**
 * EMERGENCY MODE：E-STOP 锁存后的独立全屏紧急页面。
 *
 * 结构（需求第 3 节）：
 *  - 外围：动态红黑警戒边框（[EmergencyBorder]；减少动态效果时退化为静态）；
 *  - 中央：稳定的紧急状态面板 + 停止命令链（COMMAND → FRAMES → APP ACK → DEVICE）；
 *  - 底部：RESET E-STOP 复位流程（REQUESTED → RESETTING → SELF_CHECK → 失败/成功）。
 *
 * 所有状态都来自 [UiState]（Service 维护）；本页不维护任何业务状态，
 * 不提供开始/停止、接管、强度、波形等普通控制入口。
 */
@Composable
fun EmergencyScreen(
    state: UiState,
    onRequestReset: () -> Unit,
    onReissueStop: () -> Unit,
    onRetryRelay: () -> Unit,
) {
    val strings = rememberUiStrings()
    val reduceMotion = rememberReduceMotion()

    Box(Modifier.fillMaxSize().background(McColors.EmergencyBlack)) {
        EmergencyBorder(
            topText = strings.emergencyBorderTop,
            bottomText = strings.emergencyBorderBottom,
            reduceMotion = reduceMotion,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                HeadlineRow(state)
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TitleBlock(state)
                    CommandChainPanel(state)
                    WarningBanner(
                        title = strings.emergencyDeviceUnverified,
                        body = strings.emergencyDeviceUnverifiedBody,
                        color = McColors.Warning,
                        glyph = "!",
                    )
                    LinkStatusPanel(state)
                    LockedControlNotice()
                    ResetSection(
                        state = state,
                        onRequestReset = onRequestReset,
                        onReissueStop = onReissueStop,
                        onRetryRelay = onRetryRelay,
                    )
                    Text(
                        strings.emergencyCannotLeave,
                        style = McType.caption.cjkTuned(strings.emergencyCannotLeave),
                        color = McColors.TextDim,
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

// ───────────────────────────── 顶部标题行 ─────────────────────────────

@Composable
private fun HeadlineRow(state: UiState) {
    val strings = rememberUiStrings()
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(McColors.EmergencyBrightRed))
        Spacer(Modifier.width(8.dp))
        Text(
            strings.emergencyTitle,
            style = McType.labelLarge.cjkTuned(strings.emergencyTitle),
            color = McColors.EmergencyBrightRed,
        )
        Spacer(Modifier.width(8.dp))
        McTag("E-STOP", McColors.EmergencyBrightRed, filled = true)
        Spacer(Modifier.weight(1f))
        EmergencyLinkLamp("APP", state.appConnected)
        Spacer(Modifier.width(10.dp))
        EmergencyLinkLamp("DEV", state.deviceReady)
    }
}

@Composable
private fun EmergencyLinkLamp(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusLamp(
            color = if (ok) McColors.Success else McColors.TextDim,
            size = 7.dp,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = McType.label,
            color = if (ok) McColors.TextSecondary else McColors.TextDim,
        )
    }
}

// ───────────────────────────── 标题区块 ─────────────────────────────

@Composable
private fun TitleBlock(state: UiState) {
    val strings = rememberUiStrings()
    Column(Modifier.fillMaxWidth()) {
        Text(
            "/// ${strings.emergencyTitle} ///",
            style = McType.label.cjkTuned(strings.emergencyTitle),
            color = McColors.EmergencyRed,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            strings.emergencyActivated,
            style = McType.labelLarge.cjkTuned(strings.emergencyActivated),
            color = McColors.EmergencyBrightRed,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            strings.estopLatched,
            style = McType.dataXl.copy(fontSize = 30.sp).cjkTuned(strings.estopLatched),
            color = McColors.EmergencyBrightRed,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            strings.emergencySessionSuspended,
            style = McType.labelLarge.cjkTuned(strings.emergencySessionSuspended),
            color = McColors.TextPrimary,
        )
        Text(
            strings.emergencySessionSuspendedBody,
            style = McType.body.cjkTuned(strings.emergencySessionSuspendedBody),
            color = McColors.TextSecondary,
        )
        state.engineError?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(
                error,
                style = McType.caption.cjkTuned(error),
                color = McColors.EmergencyRed,
            )
        }
    }
}

// ───────────────────────────── 停止命令链 ─────────────────────────────

@Composable
private fun CommandChainPanel(state: UiState) {
    val strings = rememberUiStrings()
    val emergency = EmergencyModel.derive(state)
    McPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = McColors.EmergencyRed,
        backgroundColor = McColors.EmergencyPanel,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(strings.emergencyCommandChain, color = McColors.TextDim)
            Spacer(Modifier.weight(1f))
            McTag(
                if (emergency.latched) strings.estopLatched else strings.statusClear,
                McColors.EmergencyRed,
                filled = emergency.latched,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            EmergencyNodeId.entries.forEachIndexed { index, id ->
                if (index > 0) {
                    Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "›",
                            style = McType.dataLarge,
                            color = McColors.TextDim,
                        )
                    }
                }
                ChainNode(
                    id = id,
                    status = emergency.status(id),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ChainNode(
    id: EmergencyNodeId,
    status: EmergencyNodeStatus,
    modifier: Modifier = Modifier,
) {
    val strings = rememberUiStrings()
    val reduceMotion = rememberReduceMotion()
    val color by animateColorAsState(
        targetValue = emergencyNodeColor(status),
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 240),
        label = "emergency-node-color",
    )
    val label = strings.emergencyNodeLabel(id)
    val statusText = strings.emergencyStatusLabel(status)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLamp(color = color, size = 7.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = McType.label.cjkTuned(label),
                color = McColors.TextSecondary,
                maxLines = 2,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            statusText,
            // 节点宽度有限（4 节点 + 分隔符）：状态用较小字重 + 最多 2 行，避免 “UNVERIFIED” 被裁切
            style = McType.label.cjkTuned(statusText),
            color = color,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun emergencyNodeColor(status: EmergencyNodeStatus): Color = when (status) {
    EmergencyNodeStatus.ACKED -> McColors.Success
    EmergencyNodeStatus.QUEUED, EmergencyNodeStatus.ISSUED -> McColors.Success
    EmergencyNodeStatus.PENDING -> McColors.Warning
    EmergencyNodeStatus.UNVERIFIED -> McColors.EmergencyBrightRed
    EmergencyNodeStatus.FAILED, EmergencyNodeStatus.NOT_SENT -> McColors.EmergencyRed
}

// ───────────────────────────── 链路状态 ─────────────────────────────

@Composable
private fun LinkStatusPanel(state: UiState) {
    val strings = rememberUiStrings()
    val link = state.link
    McPanel(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = McColors.EmergencyPanel,
    ) {
        SectionLabel(strings.emergencyLinkStatus, color = McColors.TextDim)
        Spacer(Modifier.height(6.dp))
        LinkStatusRow(
            label = strings.emergencyLinkRelay,
            ok = link.relayRunning,
            okText = strings.statusOnline,
            badText = strings.statusOffline,
        )
        LinkStatusRow(
            label = strings.emergencyLinkApp,
            ok = link.appConnected,
            okText = strings.statusConnected,
            badText = strings.statusWaiting,
        )
        LinkStatusRow(
            label = strings.emergencyLinkDevice,
            ok = link.deviceReady,
            okText = strings.statusConnected,
            badText = strings.statusWaiting,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(
                strings.emergencyLinkIntensity,
                color = McColors.TextDim,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            DataText(
                text = "${link.targetIntensityA} / ${link.estimateIntensityA} / " +
                    (link.reportedIntensityA?.toString() ?: "--"),
                color = McColors.TextPrimary,
                style = McType.dataMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(strings.emergencyNodeDevice, color = McColors.TextDim)
            Spacer(Modifier.weight(1f))
            McTag(strings.emergencyStatusUnverified, McColors.EmergencyBrightRed)
            Spacer(Modifier.width(6.dp))
            Text(
                strings.emergencyNoDeviceAck,
                style = McType.caption.cjkTuned(strings.emergencyNoDeviceAck),
                color = McColors.TextDim,
            )
        }
    }
}

@Composable
private fun LinkStatusRow(label: String, ok: Boolean, okText: String, badText: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusLamp(
            color = if (ok) McColors.Success else McColors.TextDim,
            size = 7.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = McType.label.cjkTuned(label),
            color = McColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        val text = if (ok) okText else badText
        Text(
            text,
            style = McType.labelLarge.cjkTuned(text),
            color = if (ok) McColors.Success else McColors.Warning,
        )
    }
}

// ───────────────────────────── 锁定提示 ─────────────────────────────

@Composable
private fun LockedControlNotice() {
    val strings = rememberUiStrings()
    McPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = McColors.EmergencyRed.copy(alpha = 0.5f),
        backgroundColor = McColors.EmergencyPanel,
    ) {
        SectionLabel(strings.emergencyControlLocked, color = McColors.EmergencyRed)
        Spacer(Modifier.height(3.dp))
        Text(
            strings.emergencyControlLockedBody,
            style = McType.caption.cjkTuned(strings.emergencyControlLockedBody),
            color = McColors.TextSecondary,
        )
    }
}

// ───────────────────────────── 复位区域 ─────────────────────────────

@Composable
private fun ResetSection(
    state: UiState,
    onRequestReset: () -> Unit,
    onReissueStop: () -> Unit,
    onRetryRelay: () -> Unit,
) {
    val strings = rememberUiStrings()
    val emergency = EmergencyModel.derive(state)
    var showConfirm by remember { mutableStateOf(false) }

    McPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = McColors.EmergencyRed,
        backgroundColor = McColors.EmergencyPanel,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(strings.emergencyResetSection, color = McColors.EmergencyRed)
            Spacer(Modifier.weight(1f))
            when (state.estopResetPhase) {
                EstopResetPhase.REQUESTED -> McTag(strings.emergencyResetRequested, McColors.Warning)
                EstopResetPhase.RESETTING -> McTag(strings.emergencyResetting, McColors.Warning)
                EstopResetPhase.SELF_CHECK -> McTag(strings.emergencySelfChecking, McColors.Warning)
                EstopResetPhase.FAILED -> McTag(strings.emergencyResetFailed, McColors.EmergencyRed, filled = true)
                EstopResetPhase.NONE -> McTag(strings.resetRequired, McColors.EmergencyRed)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (state.estopResetInProgress) {
            ResetProgress(state)
        } else {
            val failure = state.estopResetFailure
            if (state.estopResetPhase == EstopResetPhase.FAILED && failure != null) {
                WarningBanner(
                    title = strings.emergencyResetFailed,
                    body = failure.detail,
                    color = McColors.EmergencyRed,
                    glyph = "×",
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    strings.estopResetFailureText(failure),
                    style = McType.caption.cjkTuned(strings.estopResetFailureText(failure)),
                    color = McColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
            }
            val blocker = emergency.resetBlockerCode
            TacticalButton(
                text = strings.resetEstop,
                onClick = { showConfirm = true },
                enabled = emergency.canReset,
                variant = com.motioncontroller.ui.components.McButtonVariant.OUTLINE,
                accent = McColors.EmergencyBrightRed,
                disabledColor = McColors.EmergencyRedDim,
                disabledContainerColor = McColors.EmergencyPanel,
                height = 54.dp,
                subtitle = when {
                    state.estopResetPhase == EstopResetPhase.FAILED -> strings.emergencyResetRetry
                    // 阻塞原因在按钮下方完整展示；按钮副标题保持短，避免被裁切
                    blocker != null -> strings.emergencyResetBlocked
                    else -> strings.resetEstopSubtitle
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                strings.emergencyResetHint,
                style = McType.caption.cjkTuned(strings.emergencyResetHint),
                color = McColors.TextSecondary,
            )
            if (blocker != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "${strings.emergencyResetBlocked}: ${strings.estopResetBlockerText(blocker)}",
                    style = McType.caption.cjkTuned(strings.emergencyResetBlocked),
                    color = McColors.Warning,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.link.relayRunning || state.engineError != null) {
                    TacticalButton(
                        text = strings.emergencyRetryRelay,
                        onClick = onRetryRelay,
                        modifier = Modifier.weight(1f),
                        variant = com.motioncontroller.ui.components.McButtonVariant.GHOST,
                        height = 40.dp,
                        accent = McColors.Warning,
                    )
                }
                // 链路可用但 APP 尚未回执时，允许手动补发停止命令（幂等）。
                // APP/设备不在线时按钮无意义（发了也送不到），此时只需等待链路恢复。
                if (state.link.appConnected && state.link.deviceReady && !state.link.estopAppAcked) {
                    TacticalButton(
                        text = strings.emergencyReissueStop,
                        onClick = onReissueStop,
                        modifier = Modifier.weight(1f),
                        variant = com.motioncontroller.ui.components.McButtonVariant.GHOST,
                        height = 40.dp,
                        accent = McColors.Warning,
                    )
                }
            }
            if (state.estopResetChecks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ResetChecks(state.estopResetChecks)
            }
        }
    }

    if (showConfirm) {
        McConfirmDialog(
            title = strings.emergencyResetDialogTitle,
            body = strings.emergencyResetDialogBody,
            confirmLabel = strings.reset,
            onConfirm = {
                showConfirm = false
                onRequestReset()
            },
            onDismiss = { showConfirm = false },
            dismissLabel = strings.cancel,
            danger = true,
            containerColor = McColors.EmergencyPanel,
            confirmColor = McColors.EmergencyBrightRed,
        )
    }
}

@Composable
private fun ResetProgress(state: UiState) {
    val strings = rememberUiStrings()
    val phaseText = when (state.estopResetPhase) {
        EstopResetPhase.REQUESTED -> strings.emergencyResetRequested
        EstopResetPhase.RESETTING -> strings.emergencyResetting
        EstopResetPhase.SELF_CHECK -> strings.emergencySelfChecking
        else -> strings.emergencyResetting
    }
    Text(
        phaseText,
        style = McType.labelLarge.cjkTuned(phaseText),
        color = McColors.Warning,
    )
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(3.dp),
        color = McColors.EmergencyBrightRed,
        trackColor = McColors.EmergencyRedDim,
    )
    Spacer(Modifier.height(8.dp))
    ResetChecks(state.estopResetChecks)
    Spacer(Modifier.height(4.dp))
    Text(
        strings.emergencyResetProgressNote,
        style = McType.caption.cjkTuned(strings.emergencyResetProgressNote),
        color = McColors.TextDim,
    )
}

@Composable
private fun ResetChecks(checks: List<EstopResetCheck>) {
    val strings = rememberUiStrings()
    if (checks.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        checks.forEach { check ->
            val label = strings.resetCheckLabel(check.id)
            val statusText = strings.estopResetCheckStatus(check.status)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusLamp(color = resetCheckColor(check.status), size = 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = McType.label.cjkTuned(label),
                    color = McColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    statusText,
                    style = McType.label.cjkTuned(statusText),
                    color = resetCheckColor(check.status),
                )
            }
        }
    }
}

private fun resetCheckColor(status: EstopResetCheckStatus): Color = when (status) {
    EstopResetCheckStatus.PASS -> McColors.Success
    EstopResetCheckStatus.FAIL -> McColors.EmergencyRed
    EstopResetCheckStatus.WAITING -> McColors.Warning
    EstopResetCheckStatus.UNVERIFIED -> McColors.Warning
}
