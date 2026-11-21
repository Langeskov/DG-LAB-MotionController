package com.motioncontroller.ui.preflight

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.QrCode
import com.motioncontroller.ui.components.CheckRow
import com.motioncontroller.ui.components.DataText
import com.motioncontroller.ui.components.EmptyHint
import com.motioncontroller.ui.components.GearButton
import com.motioncontroller.ui.components.McButtonVariant
import com.motioncontroller.ui.components.McPanel
import com.motioncontroller.ui.components.McPanelHeader
import com.motioncontroller.ui.components.McTag
import com.motioncontroller.ui.components.SectionLabel
import com.motioncontroller.ui.components.StatusLamp
import com.motioncontroller.ui.components.TacticalButton
import com.motioncontroller.ui.components.WarningBanner
import com.motioncontroller.ui.components.checkStatusColor
import com.motioncontroller.ui.i18n.UiStrings
import com.motioncontroller.ui.i18n.fmt
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.model.CheckStatus
import com.motioncontroller.ui.model.ConnectionMode
import com.motioncontroller.ui.model.PreflightModel
import com.motioncontroller.ui.model.PreflightSnapshot
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType
import com.motioncontroller.ui.theme.cjkTuned

/**
 * PRE-FLIGHT 页面：进入 OPERATION 前的唯一入口。
 *
 * 布局（手机竖屏优先，360dp 宽）：
 *   顶部标识行 → 连接模式选择 → DG-LAB 连接地址/二维码 → 自检清单 → 底部 ENTER OPERATION。
 *
 * 数据来源约定：
 *  - 所有真实状态只读 [UiState]；
 *  - 自检结果全部来自 [PreflightModel.derive]（纯函数），本文件不复刻任何判断逻辑；
 *  - 关键项未 PASS 时“进入”按钮禁用，并在 subtitle 里说明原因；
 *  - 只有 [PreflightSnapshot.bootSequenceAllowed] 为 true 才会播放 [BootSequence]，
 *    关键失败时绝不展示 READY、绝不播放启动动效。
 */
@Composable
fun PreflightScreen(
    state: UiState,
    serviceBound: Boolean,
    appVersion: String,
    onSelectMode: (ConnectionMode) -> Unit,
    onCopyAddress: () -> Unit,
    onRetryService: () -> Unit,
    onOpenEngineering: () -> Unit,
    onEnterOperation: () -> Unit,
) {
    val strings = rememberUiStrings()
    val snapshot = remember(state, serviceBound, strings) { PreflightModel.derive(state, serviceBound, strings) }
    val currentMode = ConnectionMode.ofBindHost(state.bindHost)

    // 用户点选、但中继还没切过去的模式。
    // 注意：ControllerService 会先乐观更新 bindHost 再异步重启中继，因此“requested != bindHost”
    // 这个条件在真实时序里几乎不可观测；用户点选了不同模式时用 switchPending 持续到引擎收敛结束。
    var requestedMode by remember { mutableStateOf<ConnectionMode?>(null) }
    var switchPending by remember { mutableStateOf(false) }
    LaunchedEffect(state.engineLoading) {
        if (!state.engineLoading) {
            switchPending = false
            requestedMode = null
        }
    }
    val pendingSwitch = state.engineLoading &&
        (switchPending || (requestedMode != null && requestedMode != currentMode))

    // 多机模式默认展开二维码；模式（真实 bindHost 形态）变化时重置。
    var qrVisible by remember(currentMode) { mutableStateOf(currentMode.isLan) }

    // 启动动效：只有用户点 ENTER 才播放；过程中关键项失守则立即收回，不伪造 READY。
    var booting by remember { mutableStateOf(false) }
    LaunchedEffect(booting, snapshot.bootSequenceAllowed) {
        if (booting && !snapshot.bootSequenceAllowed) booting = false
    }

    val rawAddress = state.address.trim()
    val hasAddress = rawAddress.isNotEmpty() && !rawAddress.contains("0.0.0.0")
    val addressText = if (hasAddress) rawAddress else strings.addressNotRunning

    val firstCriticalFail = snapshot.checks.firstOrNull {
        it.critical && it.status == CheckStatus.FAIL
    }
    val failureBody = state.engineError
        ?: firstCriticalFail?.detail
        ?: strings.criticalCheckFailed

    // 不可进入原因：优先 FAIL 的 detail，其次等待连接/加载的项（blocker 已按优先级排序）。
    val blockedReason: String? = if (snapshot.canEnter) {
        null
    } else {
        val blocker = snapshot.blocker
        when {
            blocker == null -> strings.checksIncomplete
            blocker.status == CheckStatus.FAIL -> blocker.detail
            else -> fmt(strings.blockedWaitingFor, blocker.label, blocker.detail)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(McColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PreflightHeader(
                appVersion = appVersion,
                onOpenEngineering = onOpenEngineering,
            )

            // 常规屏幕一屏内可读完；更小的屏幕/展开二维码时滚动兜底，底部按钮始终可见。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ModeSection(
                    currentMode = currentMode,
                    requestedMode = requestedMode,
                    pendingSwitch = pendingSwitch,
                    onSelect = { mode ->
                        if (mode != currentMode) {
                            requestedMode = mode
                            switchPending = true
                        }
                        onSelectMode(mode)
                    },
                )

                LinkPanel(
                    addressText = addressText,
                    hasAddress = hasAddress,
                    qrPayload = state.qrPayload,
                    qrVisible = qrVisible,
                    isLan = currentMode.isLan,
                    onToggleQr = { qrVisible = !qrVisible },
                    onCopyAddress = onCopyAddress,
                )

                ChecklistPanel(snapshot = snapshot)

                if (snapshot.criticalFailure) {
                    FailureActions(
                        failureBody = failureBody,
                        engineLoading = state.engineLoading,
                        onRetryService = onRetryService,
                        onOpenEngineering = onOpenEngineering,
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = McColors.Divider)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                TacticalButton(
                    text = strings.enterOperation,
                    onClick = { if (!booting) booting = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = snapshot.canEnter,
                    variant = McButtonVariant.PRIMARY,
                    subtitle = blockedReason,
                    height = 64.dp,
                )
            }
        }

        // 全屏启动动效覆盖层：纯展示，不触碰任何业务状态。
        if (booting) {
            BootSequence(
                active = true,
                allowed = snapshot.bootSequenceAllowed,
                onFinished = {
                    booting = false
                    onEnterOperation()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ───────────────────────────── 顶部标识 ─────────────────────────────

@Composable
private fun PreflightHeader(
    appVersion: String,
    onOpenEngineering: () -> Unit,
) {
    val strings = rememberUiStrings()
    val versionLabel = if (appVersion.isBlank()) "—" else appVersion
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.appName,
                    style = McType.title.cjkTuned(strings.appName),
                    color = McColors.TextPrimary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    McTag(text = strings.screenPreflight, color = McColors.BlueAccent)
                    DataText(
                        text = fmt(strings.versionLabel, versionLabel),
                        style = McType.dataSmall,
                        color = McColors.TextDim,
                    )
                }
            }
            GearButton(onClick = onOpenEngineering, contentDescription = strings.engineeringTitle)
        }
        HorizontalDivider(thickness = 1.dp, color = McColors.Divider)
    }
}

// ───────────────────────────── 模式选择 ─────────────────────────────

@Composable
private fun ModeSection(
    currentMode: ConnectionMode,
    requestedMode: ConnectionMode?,
    pendingSwitch: Boolean,
    onSelect: (ConnectionMode) -> Unit,
) {
    val strings = rememberUiStrings()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(
                text = strings.connectionModeTitle,
                modifier = Modifier.weight(1f),
            )
            SectionLabel(
                text = if (currentMode.isLan) strings.modeMultiLan else strings.modeSingleLocal,
                color = McColors.TextDim,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionMode.entries.forEach { mode ->
                ModePanel(
                    mode = mode,
                    selected = mode == currentMode,
                    restarting = pendingSwitch && mode == requestedMode,
                    onClick = { onSelect(mode) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
        val pending = requestedMode.takeIf { pendingSwitch }
        if (pending != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusLamp(color = McColors.Warning)
                SectionLabel(
                    text = strings.restartingRelay,
                    color = McColors.Warning,
                    modifier = Modifier.weight(1f),
                )
                DataText(
                    text = strings.connectionModeName(pending),
                    style = McType.dataSmall,
                    color = McColors.Warning,
                )
            }
        }
    }
}

@Composable
private fun ModePanel(
    mode: ConnectionMode,
    selected: Boolean,
    restarting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberUiStrings()
    val accent = when {
        restarting -> McColors.Warning
        selected -> McColors.BlueAccent
        else -> null
    }
    val marker = when {
        restarting -> strings.markerRestarting
        selected -> strings.markerActive
        else -> strings.markerSelect
    }
    val markerColor = when {
        restarting -> McColors.Warning
        selected -> McColors.BlueAccent
        else -> McColors.TextDim
    }
    McPanel(
        modifier = modifier.clickable(onClick = onClick),
        accent = accent,
        contentPadding = 10.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings.connectionModeName(mode),
                style = McType.dataLarge,
                color = when {
                    restarting -> McColors.Warning
                    selected -> McColors.BlueAccent
                    else -> McColors.TextPrimary
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (restarting) {
                StatusLamp(color = McColors.Warning)
            } else if (selected) {
                StatusLamp(color = McColors.BlueAccent)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = strings.connectionModeDesc(mode),
            style = McType.caption,
            color = McColors.TextSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        DataText(
            text = if (mode.isLan) strings.modeHintLan else strings.modeHintLocal,
            style = McType.dataSmall,
            color = McColors.TextDim,
        )
        Spacer(Modifier.height(5.dp))
        SectionLabel(text = marker, color = markerColor)
    }
}

// ───────────────────────────── 连接地址 / 二维码 ─────────────────────────────

@Composable
private fun LinkPanel(
    addressText: String,
    hasAddress: Boolean,
    qrPayload: String,
    qrVisible: Boolean,
    isLan: Boolean,
    onToggleQr: () -> Unit,
    onCopyAddress: () -> Unit,
) {
    val strings = rememberUiStrings()
    val qr = remember(qrPayload) { QrCode.bitmap(qrPayload) }

    McPanel(modifier = Modifier.fillMaxWidth(), contentPadding = 10.dp) {
        McPanelHeader(
            title = strings.linkPanelTitle,
            trailing = {
                McTag(
                    text = if (isLan) strings.tagLan else strings.tagLocal,
                    color = McColors.SteelBlue,
                )
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(McColors.SurfaceInset, RoundedCornerShape(1.dp))
                .border(1.dp, McColors.Border, RoundedCornerShape(1.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            DataText(
                text = addressText,
                modifier = Modifier.fillMaxWidth(),
                style = McType.dataSmall,
                color = if (hasAddress) McColors.BlueAccent else McColors.TextDim,
                maxLines = 2,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TacticalButton(
                text = strings.copyAddress,
                onClick = onCopyAddress,
                modifier = Modifier.weight(1f),
                enabled = hasAddress,
                variant = McButtonVariant.OUTLINE,
                subtitle = if (hasAddress) null else strings.relayNotReady,
                height = 52.dp,
            )
            TacticalButton(
                text = if (qrVisible) strings.hideQr else strings.showQr,
                onClick = onToggleQr,
                modifier = Modifier.weight(1f),
                variant = McButtonVariant.GHOST,
                height = 52.dp,
            )
        }
        if (qrVisible) {
            Spacer(Modifier.height(10.dp))
            if (qr != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = strings.qrContentDescription,
                        modifier = Modifier
                            .size(220.dp)
                            .background(Color.White, RoundedCornerShape(1.dp))
                            .padding(8.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    SectionLabel(text = strings.scanWithApp, color = McColors.TextDim)
                }
            } else {
                EmptyHint(
                    text = if (qrPayload.isBlank()) {
                        strings.qrUnavailableAddress
                    } else {
                        strings.qrUnavailableEncode
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// ───────────────────────────── 自检清单 ─────────────────────────────

@Composable
private fun ChecklistPanel(snapshot: PreflightSnapshot) {
    val strings = rememberUiStrings()
    val (summaryText, summaryColor) = when {
        snapshot.criticalFailure -> strings.statusFail to McColors.Danger
        snapshot.waiting -> strings.statusWaiting to McColors.Warning
        else -> strings.statusReady to McColors.Success
    }
    McPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = if (snapshot.criticalFailure) McColors.Danger else null,
        contentPadding = 10.dp,
    ) {
        McPanelHeader(
            title = strings.preflightCheckTitle,
            trailing = {
                McTag(text = summaryText, color = summaryColor)
            },
        )
        snapshot.checks.forEach { check ->
            CheckRow(
                label = check.label,
                statusText = strings.checkStatus(check.status),
                color = checkStatusColor(check.status),
                detail = check.detail,
                hollowLamp = check.status == CheckStatus.DISCONNECTED,
            )
        }
    }
}

// ───────────────────────────── 关键失败 ─────────────────────────────

@Composable
private fun FailureActions(
    failureBody: String,
    engineLoading: Boolean,
    onRetryService: () -> Unit,
    onOpenEngineering: () -> Unit,
) {
    val strings = rememberUiStrings()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WarningBanner(
            title = strings.preflightFailed,
            body = failureBody,
            color = McColors.Danger,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TacticalButton(
                text = strings.retry,
                onClick = onRetryService,
                modifier = Modifier.weight(1f),
                enabled = !engineLoading,
                variant = McButtonVariant.OUTLINE,
                subtitle = if (engineLoading) strings.restartingEngine else null,
                height = 52.dp,
            )
            TacticalButton(
                text = strings.engineeringTitle,
                onClick = onOpenEngineering,
                modifier = Modifier.weight(1f),
                variant = McButtonVariant.GHOST,
                height = 52.dp,
            )
        }
    }
}
