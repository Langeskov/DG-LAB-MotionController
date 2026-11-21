package com.motioncontroller.ui.operation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motioncontroller.mapping.WaveConfig
import com.motioncontroller.ml.MotionClass
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.components.DataText
import com.motioncontroller.ui.components.EmergencyStopGlyph
import com.motioncontroller.ui.components.GearButton
import com.motioncontroller.ui.components.KeyValueRow
import com.motioncontroller.ui.components.McButtonVariant
import com.motioncontroller.ui.components.McMiniButton
import com.motioncontroller.ui.components.McPanel
import com.motioncontroller.ui.components.McSlider
import com.motioncontroller.ui.components.McTag
import com.motioncontroller.ui.components.SectionLabel
import com.motioncontroller.ui.components.StatusLamp
import com.motioncontroller.ui.components.TacticalButton
import com.motioncontroller.ui.components.WarningBanner
import com.motioncontroller.ui.components.rememberReduceMotion
import com.motioncontroller.ui.emergency.ControlSessionRestoredBanner
import com.motioncontroller.ui.i18n.fmt
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.i18n.uiCase
import com.motioncontroller.ui.model.ControlModel
import com.motioncontroller.ui.model.ControlPhase
import com.motioncontroller.ui.model.MappingSource
import com.motioncontroller.ui.model.MotionDisplayModel
import com.motioncontroller.ui.model.OutputModel
import com.motioncontroller.ui.model.OutputProfileSnapshot
import com.motioncontroller.ui.model.SpeedModel
import com.motioncontroller.ui.model.SpeedSnapshot
import com.motioncontroller.ui.model.SpeedStatus
import com.motioncontroller.ui.model.formatSpeedKmh
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType
import com.motioncontroller.ui.theme.cjkTuned

/**
 * OPERATION：主控制界面（普通用户唯一常驻页面，UI V2.0 POLAR BLUE）。
 *
 * 信息层级（从上到下，中等密度）：
 *  1. 顶部品牌 + RELAY/APP/DEVICE 链路灯 + 工程入口（不展示地址/tid/端口）；
 *  2. 仪表区：GNSS 速度（LIVE/STALE/UNAVAILABLE 严格区分）+ 当前运动模式（中文 + 技术英文码 + AUTO/MANUAL）；
 *  3. CONTROL AUTHORITY：AUTO / MANUAL OVERRIDE 接管与归还；
 *  4. OUTPUT PROFILE：TARGET / APPLIED 为主读数，BASE / OFFSET 为计算依据，安全上限与发送/估计/APP 上报辅助；
 *  5. INTENSITY ENVELOPE：本地强度包络的紧凑摘要（展开后调整形状/频率/深度/持续时间）；
 *  6. SYSTEM READINESS：拆分为 CONTROL LINK（RELAY/APP/DEVICE）与 MOTION ENGINE（MODEL/SCALER/GNSS）；
 *  7. START / STOP（按真实控制状态切换，阻塞原因可见）；
 *  8. 独立 EMERGENCY STOP（固定底部，始终可触达）。
 *
 * 所有数值均来自 [UiState]（纯派生：ControlModel / SpeedModel / MotionDisplayModel / OutputModel）。
 * 控制算法、映射公式与安全限制均不在此层修改。
 */
@Composable
fun OperationScreen(
    state: UiState,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onEmergencyStop: () -> Unit,
    onTakeManualControl: (Int) -> Unit,
    onSelectManualClass: (Int) -> Unit,
    onReturnToAuto: () -> Unit,
    onManualOffset: (Float) -> Unit,
    onWaveConfig: (WaveConfig) -> Unit,
    onOpenEngineering: () -> Unit,
    recoveryBanner: Boolean = false,
) {
    val strings = rememberUiStrings()
    val control = ControlModel.derive(state, strings)
    val speed = SpeedModel.derive(state.pipeline)
    val motion = MotionDisplayModel.effective(state)
    val output = OutputModel.derive(state)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(McColors.BackgroundGradientTop, McColors.Background),
                ),
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            TopStatusBar(state = state, onOpenEngineering = onOpenEngineering)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // E-STOP 恢复成功后的短暂提示：参与正常内容流，不覆盖顶部标题/链路栏。
                if (recoveryBanner) {
                    ControlSessionRestoredBanner()
                }
                if (state.recognitionPaused && state.armed) {
                    WarningBanner(
                        title = strings.warnRecognitionPausedTitle,
                        body = strings.warnRecognitionPausedBody,
                    )
                }
                if (state.engineError != null) {
                    WarningBanner(
                        title = strings.engineFaultTitle,
                        body = state.engineError,
                        color = McColors.Danger,
                        glyph = "×",
                    )
                }
                // 急停复位后回到 OPERATION：设备停机状态仍无法通过 BLE 验证。
                // 不阻塞用户查看/操作（START 仍有真实门禁），但必须持续提示，直到用户重新 START。
                if (state.estopDeviceStopUnverified && !state.armed) {
                    WarningBanner(
                        title = strings.checkEstopRecovery,
                        body = strings.detailEstopRecoveryUnverified,
                    )
                }

                // 仪表区：速度 + 运动模式并排，作为主页面第一视觉区域。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    SpeedGaugePanel(
                        speed = speed,
                        armed = state.armed,
                        recognitionPaused = state.recognitionPaused,
                        modifier = Modifier.weight(1.15f),
                    )
                    MotionModePanel(
                        state = state,
                        motionCode = motion.code,
                        motionLabel = if (motion.available) {
                            strings.motionClassLabel(motion.classIndex)
                        } else {
                            strings.waitingForRecognition
                        },
                        available = motion.available,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 数据失效必须明确提示，不能继续把旧值当实时速度。
                when (speed.status) {
                    SpeedStatus.STALE -> WarningBanner(
                        title = strings.speedStatus(SpeedStatus.STALE),
                        body = strings.speedStaleNote,
                    )
                    SpeedStatus.UNAVAILABLE -> Text(
                        strings.speedUnavailableNote,
                        style = McType.caption,
                        color = McColors.TextDim,
                    )
                    SpeedStatus.LIVE -> Unit
                }

                ControlAuthorityPanel(
                    state = state,
                    controlPhase = control.phase,
                    onTakeManualControl = onTakeManualControl,
                    onSelectManualClass = onSelectManualClass,
                    onReturnToAuto = onReturnToAuto,
                )
                OutputProfilePanel(
                    state = state,
                    output = output,
                    onManualOffset = onManualOffset,
                )
                StartStopPanel(
                    state = state,
                    controlPhase = control.phase,
                    startEnabled = control.startEnabled,
                    stopEnabled = control.stopEnabled,
                    blockers = control.blockers,
                    onArm = onArm,
                    onDisarm = onDisarm,
                )
                WavePanel(
                    state = state,
                    onWaveConfig = onWaveConfig,
                )
                ReadinessPanel(state = state)
                Spacer(Modifier.height(2.dp))
            }
            Spacer(Modifier.height(6.dp))
            EmergencyStopBar(
                state = state,
                onEmergencyStop = onEmergencyStop,
            )
        }
    }
}

// ───────────────────────────── 顶部状态栏 ─────────────────────────────

@Composable
private fun TopStatusBar(state: UiState, onOpenEngineering: () -> Unit) {
    val strings = rememberUiStrings()
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                strings.appName,
                style = McType.labelLarge.cjkTuned(strings.appName),
                color = McColors.TextPrimary,
                maxLines = 1,
            )
            Text(
                strings.brandSubtitle,
                style = McType.label.copy(fontSize = 7.5.sp, letterSpacing = 1.3.sp),
                color = McColors.IceBlue.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            LinkLamp(strings.readinessRelay, ok = state.link.relayRunning)
            LinkLamp(strings.readinessApp, ok = state.appConnected)
            LinkLamp(strings.readinessDevice, ok = state.deviceReady)
        }
        Spacer(Modifier.width(8.dp))
        GearButton(onClick = onOpenEngineering, contentDescription = strings.engineeringTitle)
    }
}

@Composable
private fun LinkLamp(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusLamp(
            color = if (ok) McColors.Success else McColors.TextDim,
            size = 6.dp,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label.uiCase(),
            style = McType.label.copy(fontSize = 9.sp, letterSpacing = 0.8.sp).cjkTuned(label),
            color = if (ok) McColors.TextSecondary else McColors.TextDim,
            maxLines = 1,
        )
    }
}

// ───────────────────────────── 速度仪表 ─────────────────────────────

@Composable
private fun SpeedGaugePanel(
    speed: SpeedSnapshot,
    armed: Boolean,
    recognitionPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = rememberUiStrings()
    val reduceMotion = rememberReduceMotion()
    val animatedSpeed by animateFloatAsState(
        targetValue = if (speed.status == SpeedStatus.UNAVAILABLE) 0f else speed.displayKmh,
        animationSpec = tween(
            durationMillis = if (reduceMotion || speed.status != SpeedStatus.LIVE) 0 else 260,
        ),
        label = "ground-speed",
    )
    val valueText = if (speed.status == SpeedStatus.UNAVAILABLE) "--" else formatSpeedKmh(animatedSpeed)
    val valueColor by animateColorAsState(
        targetValue = when (speed.status) {
            SpeedStatus.LIVE -> McColors.TextPrimary
            SpeedStatus.STALE -> McColors.Warning
            SpeedStatus.UNAVAILABLE -> McColors.TextDim
        },
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 220),
        label = "speed-color",
    )
    val statusColor = when (speed.status) {
        SpeedStatus.LIVE -> McColors.Success
        SpeedStatus.STALE -> McColors.Warning
        SpeedStatus.UNAVAILABLE -> McColors.TextDim
    }
    val fixText = when (speed.status) {
        SpeedStatus.LIVE -> strings.fixActive
        SpeedStatus.STALE -> strings.speedStatus(SpeedStatus.STALE)
        SpeedStatus.UNAVAILABLE -> strings.noFix
    }

    McPanel(
        modifier = modifier,
        accent = when {
            !armed -> null
            recognitionPaused -> McColors.Warning
            else -> McColors.BlueAccent.copy(alpha = 0.35f)
        },
        contentPadding = 10.dp,
    ) {
        SectionLabel(strings.groundSpeed, color = McColors.TextDim)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = valueText,
                style = McType.speedValue.copy(fontSize = 56.sp, lineHeight = 58.sp),
                color = valueColor,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "km/h",
                style = McType.dataSmall,
                color = McColors.TextDim,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            McTag(strings.speedStatus(speed.status), statusColor)
            // STALE 时标签已表达状态，下方还有整行警告；右侧不再重复同一句。
            if (speed.status != SpeedStatus.STALE) {
                Spacer(Modifier.weight(1f))
                DataText(text = fixText, color = statusColor, style = McType.dataSmall)
            }
        }
    }
}

// ───────────────────────────── 运动模式 ─────────────────────────────

@Composable
private fun MotionModePanel(
    state: UiState,
    motionCode: String,
    motionLabel: String,
    available: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = rememberUiStrings()
    val manual = state.manualClass != null
    val reduceMotion = rememberReduceMotion()
    val tagColor = if (manual) McColors.BlueAccent else McColors.SteelBlue
    McPanel(
        modifier = modifier,
        accent = if (manual) McColors.BlueAccent.copy(alpha = 0.45f) else null,
        contentPadding = 10.dp,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(strings.motionMode, color = McColors.TextDim)
            Spacer(Modifier.weight(1f))
            McTag(if (manual) strings.tagManual else strings.tagAuto, tagColor)
        }
        Spacer(Modifier.height(4.dp))
        Crossfade(
            targetState = motionCode,
            animationSpec = tween(durationMillis = if (reduceMotion) 0 else 180),
            label = "motion-mode",
        ) { code ->
            Text(
                text = code,
                style = McType.dataLarge.copy(fontSize = 23.sp, lineHeight = 27.sp),
                color = when {
                    !available -> McColors.TextDim
                    manual -> McColors.BlueAccent
                    else -> McColors.TextPrimary
                },
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = motionLabel,
            style = McType.body,
            color = if (available) McColors.TextSecondary else McColors.TextDim,
            maxLines = 1,
        )
    }
}

// ───────────────────────────── 控制权接管 ─────────────────────────────

@Composable
private fun ControlAuthorityPanel(
    state: UiState,
    controlPhase: ControlPhase,
    onTakeManualControl: (Int) -> Unit,
    onSelectManualClass: (Int) -> Unit,
    onReturnToAuto: () -> Unit,
) {
    val strings = rememberUiStrings()
    val manual = state.manualClass
    val manualClass = manual?.let { MotionClass.of(it) }
    val accent = if (manual != null) McColors.BlueAccent else McColors.SteelBlue
    McPanel(accent = if (manual != null) McColors.BlueAccent.copy(alpha = 0.6f) else null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(strings.controlAuthority, color = McColors.TextDim)
            Spacer(Modifier.weight(1f))
            McTag(
                if (manual != null) strings.manualOverride else strings.tagAuto,
                accent,
                filled = manual != null,
            )
        }
        Spacer(Modifier.height(5.dp))
        if (manual == null) {
            Text(
                strings.authorityAutoDesc,
                style = McType.caption,
                color = McColors.TextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            val initial = if (state.effectiveClass >= 0) state.effectiveClass else MotionClass.STATIONARY.ordinal
            TacticalButton(
                text = strings.takeManualControl,
                onClick = { onTakeManualControl(initial) },
                enabled = !state.emergencyStopped,
                variant = McButtonVariant.OUTLINE,
                height = 52.dp,
                subtitle = when {
                    state.emergencyStopped -> strings.takeManualDisabledEstop
                    state.armed -> strings.takeManualArmedNote
                    else -> null
                },
            )
        } else {
            Text(
                strings.authorityManualDesc,
                style = McType.caption,
                color = McColors.TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                McMiniButton(
                    glyph = "◀",
                    onClick = { onSelectManualClass(cycleClass(state.manualClass, -1)) },
                    size = 40.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = manualClass?.name ?: "—",
                        style = McType.dataLarge,
                        color = McColors.BlueAccent,
                        maxLines = 1,
                    )
                    Text(
                        text = manual?.let { strings.motionClassLabel(it) } ?: "—",
                        style = McType.body,
                        color = McColors.TextSecondary,
                    )
                }
                McMiniButton(
                    glyph = "▶",
                    onClick = { onSelectManualClass(cycleClass(state.manualClass, 1)) },
                    size = 40.dp,
                )
            }
            Spacer(Modifier.height(6.dp))
            val blocker = ControlModel.returnToAutoBlocker(state, strings)
            TacticalButton(
                text = strings.returnToAuto,
                onClick = onReturnToAuto,
                enabled = blocker == null,
                variant = McButtonVariant.OUTLINE,
                height = 52.dp,
                subtitle = blocker ?: strings.returnToAutoSubtitle,
                accent = if (blocker == null) McColors.BlueAccent else McColors.TextDim,
            )
        }
        if (controlPhase == ControlPhase.ESTOP_LATCHED) {
            Spacer(Modifier.height(4.dp))
            Text(strings.controlSuspendedEstop, style = McType.caption, color = McColors.Danger)
        }
    }
}

private fun cycleClass(current: Int?, delta: Int): Int {
    val size = MotionClass.entries.size
    val base = current ?: MotionClass.STATIONARY.ordinal
    val next = (base + delta) % size
    return if (next < 0) next + size else next
}

// ───────────────────────────── 输出映射 ─────────────────────────────

@Composable
private fun OutputProfilePanel(
    state: UiState,
    output: OutputProfileSnapshot,
    onManualOffset: (Float) -> Unit,
) {
    val strings = rememberUiStrings()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var offsetDraft by remember(state.manualOffset) { mutableStateOf(state.manualOffset) }
    val sourceColor = if (output.source == MappingSource.MANUAL) {
        McColors.BlueAccent
    } else {
        McColors.SteelBlue
    }
    val appliedColor = when {
        output.estopLatched -> McColors.Danger
        output.appliedIntensity > 0 -> McColors.BlueAccent
        else -> McColors.TextSecondary
    }
    McPanel(accent = if (output.source == MappingSource.MANUAL) McColors.BlueAccent.copy(alpha = 0.4f) else null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(strings.outputProfile, color = McColors.TextDim)
            Spacer(Modifier.width(6.dp))
            McTag(strings.mappingSource(output.source), sourceColor)
            Spacer(Modifier.weight(1f))
            McTag(
                "${strings.safetyLimit} ${output.safetyMax}",
                McColors.SteelBlueDim,
            )
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "▲" else "▼", style = McType.dataSmall, color = McColors.TextSecondary)
        }
        Spacer(Modifier.height(6.dp))
        // TARGET / APPLIED 主读数：软件映射目标与实际下发值必须分开看。
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PrimaryMetric(
                label = strings.outTarget,
                value = output.clampedTarget.toString(),
                valueColor = if (output.estopLatched) McColors.Danger else McColors.IceBlue,
                accent = if (output.estopLatched) McColors.Danger else McColors.IceBlue.copy(alpha = 0.55f),
                modifier = Modifier.weight(1.15f),
            )
            PrimaryMetric(
                label = strings.outApplied,
                value = output.appliedIntensity.toString(),
                valueColor = appliedColor,
                accent = appliedColor.copy(alpha = 0.45f),
                modifier = Modifier.weight(1.15f),
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryMetric(
                label = strings.outBase,
                value = output.baseIntensity.toString(),
                modifier = Modifier.weight(1f),
            )
            SecondaryMetric(
                label = strings.outOffset,
                value = signed(output.manualOffset),
                modifier = Modifier.weight(1f),
            )
            SecondaryMetric(
                label = strings.outMapping,
                value = output.classCode,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            fmt(
                strings.sentEstApp,
                output.sentTargetA,
                output.sentEstimateA,
                output.appReportedA?.toString() ?: "--",
            ),
            style = McType.caption,
            color = McColors.TextDim,
        )
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(thickness = 1.dp, color = McColors.Divider)
            Spacer(Modifier.height(6.dp))
            KeyValueRow(
                strings.outComputed,
                fmt(strings.computedValue, output.computedTarget, output.baseIntensity, signed(output.manualOffset)),
            )
            KeyValueRow(
                strings.outClamped,
                fmt(strings.clampedValue, output.clampedTarget, output.safetyMax),
            )
            KeyValueRow(strings.outMapping, "${output.classCode} · ${strings.mappingSource(output.source)}")
            KeyValueRow(strings.sentTarget, "${output.sentTargetA} (A)")
            KeyValueRow(strings.appReported, output.appReportedA?.toString() ?: strings.unknown)
            Spacer(Modifier.height(6.dp))
            SectionLabel(
                fmt(strings.manualOffsetLimit, output.manualOffsetLimit),
                color = McColors.TextDim,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                McSlider(
                    value = offsetDraft,
                    onValueChange = {
                        offsetDraft = it
                        onManualOffset(it)
                    },
                    valueRange = -state.mapping.manualLimit..state.mapping.manualLimit,
                    steps = 39,
                    modifier = Modifier.weight(1f),
                    accent = McColors.BlueAccent,
                )
                Spacer(Modifier.width(8.dp))
                McMiniButton(
                    glyph = "0",
                    onClick = {
                        offsetDraft = 0f
                        onManualOffset(0f)
                    },
                )
            }
            Text(
                strings.outputLayersNote,
                style = McType.caption,
                color = McColors.TextDim,
            )
        }
    }
}

/** OUTPUT PROFILE 主读数（TARGET / APPLIED）：高视觉权重。 */
@Composable
private fun PrimaryMetric(
    label: String,
    value: String,
    valueColor: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(McColors.SurfaceInset, RoundedCornerShape(2.dp))
            .border(1.dp, accent, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        SectionLabel(label, color = McColors.TextSecondary)
        Spacer(Modifier.height(1.dp))
        Text(value, style = McType.dataXl, color = valueColor, maxLines = 1)
    }
}

/** OUTPUT PROFILE 次级读数（BASE / OFFSET / MAPPING）：低视觉权重。 */
@Composable
private fun SecondaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(McColors.SurfaceInset, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        SectionLabel(label, color = McColors.TextDim)
        DataText(text = value, style = McType.dataSmall, color = McColors.TextSecondary)
    }
}

private fun signed(value: Int): String = if (value > 0) "+$value" else "$value"

// ───────────────────────────── 系统就绪 ─────────────────────────────

/**
 * 就绪面板：把两个不同概念分开表达，避免一个含义模糊的 READY：
 *  - CONTROL LINK：RELAY / APP / DEVICE（控制链路是否就绪）；
 *  - MOTION ENGINE：MODEL / SCALER / GNSS（运动识别能力是否就绪；GNSS 缺失不阻止控制，但会降低准确率）。
 */
@Composable
private fun ReadinessPanel(state: UiState) {
    val strings = rememberUiStrings()
    val relay = state.link.relayRunning
    val app = state.appConnected
    val device = state.deviceReady
    val model = state.pipeline.modelLoaded
    val scaler = state.pipeline.scalerValid
    val controlReady = relay && app && device
    val motionReady = model && scaler
    val suspended = state.emergencyStopped
    val overallColor = when {
        suspended -> McColors.Danger
        controlReady && motionReady -> McColors.Success
        else -> McColors.Warning
    }
    val gnssColor = when {
        state.pipeline.gpsAvailable -> McColors.Success
        state.pipeline.gpsEverFix -> McColors.Warning
        else -> McColors.TextDim
    }
    McPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(strings.systemReadiness, color = McColors.TextDim)
            Spacer(Modifier.weight(1f))
            McTag(
                when {
                    suspended -> strings.suspended
                    controlReady && motionReady -> strings.statusReady
                    else -> strings.notReady
                },
                overallColor,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(strings.controlLink, color = McColors.SteelBlue, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadinessItem(strings.readinessRelay, relay)
                ReadinessItem(strings.readinessApp, app)
                ReadinessItem(strings.readinessDevice, device)
            }
        }
        Spacer(Modifier.height(5.dp))
        HorizontalDivider(thickness = 1.dp, color = McColors.Divider)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(strings.motionEngine, color = McColors.SteelBlue, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadinessItem(strings.readinessModel, model)
                ReadinessItem(strings.readinessScaler, scaler)
                ReadinessItem(strings.readinessGnss, state.pipeline.gpsAvailable, color = gnssColor)
            }
        }
    }
}

@Composable
private fun ReadinessItem(
    label: String,
    ok: Boolean,
    modifier: Modifier = Modifier,
    color: Color = if (ok) McColors.Success else McColors.TextDim,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusLamp(color = color, size = 6.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            label.uiCase(),
            style = McType.label.cjkTuned(label),
            color = if (ok) McColors.TextSecondary else McColors.TextDim,
            maxLines = 1,
        )
    }
}

// ───────────────────────────── START / STOP ─────────────────────────────

@Composable
private fun StartStopPanel(
    state: UiState,
    controlPhase: ControlPhase,
    startEnabled: Boolean,
    stopEnabled: Boolean,
    blockers: List<String>,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
) {
    val strings = rememberUiStrings()
    val phaseColor = when (controlPhase) {
        ControlPhase.RUNNING -> McColors.Success
        ControlPhase.STARTING, ControlPhase.STOPPING -> McColors.Warning
        ControlPhase.FAULT, ControlPhase.ESTOP_LATCHED -> McColors.Danger
        ControlPhase.STANDBY -> McColors.TextDim
    }
    McPanel(accent = if (state.armed) McColors.BlueAccent.copy(alpha = 0.4f) else null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(strings.controlEngine, color = McColors.TextDim)
            Spacer(Modifier.weight(1f))
            StatusLamp(color = phaseColor, size = 7.dp)
            Spacer(Modifier.width(5.dp))
            val phaseText = strings.controlPhase(controlPhase)
            Text(
                phaseText.uiCase(),
                style = McType.label.cjkTuned(phaseText),
                color = phaseColor,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (!state.armed) {
            TacticalButton(
                text = strings.startControl,
                onClick = onArm,
                enabled = startEnabled,
                height = 54.dp,
                subtitle = if (startEnabled) {
                    fmt(
                        strings.startSubtitle,
                        strings.mappingSource(
                            if (state.manualClass != null) MappingSource.MANUAL else MappingSource.AUTO,
                        ),
                    )
                } else {
                    blockers.firstOrNull() ?: strings.notReady
                },
            )
            if (!startEnabled && blockers.size > 1) {
                Spacer(Modifier.height(3.dp))
                Text(
                    blockers.drop(1).joinToString(" · "),
                    style = McType.caption,
                    color = McColors.Warning,
                )
            }
        } else {
            TacticalButton(
                text = strings.stopControl,
                onClick = onDisarm,
                enabled = stopEnabled,
                variant = McButtonVariant.OUTLINE,
                height = 54.dp,
                subtitle = strings.stopSubtitle,
            )
        }
    }
}

// ───────────────────────────── 急停入口 ─────────────────────────────

/**
 * OPERATION 上的 E-STOP **入口**（唯一职责）。
 *
 * 锁存后由 MainActivity 切换到独立全屏 EMERGENCY MODE；本组件不再承载锁存状态/复位流程。
 * 红黑警报语义与普通 POLAR BLUE 页面隔离：底部常驻红色按钮。
 */
@Composable
private fun EmergencyStopBar(
    state: UiState,
    onEmergencyStop: () -> Unit,
) {
    val strings = rememberUiStrings()
    if (state.emergencyStopped) {
        // 防御性兜底：正常情况下页面已被 EmergencyScreen 替换，不会走到这里。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(McColors.EmergencyRedDim.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                .border(1.dp, McColors.EmergencyRed, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(strings.estopLatched, style = McType.labelLarge, color = McColors.EmergencyRed)
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(McColors.Danger, RoundedCornerShape(2.dp))
            .border(1.dp, McColors.Danger.copy(alpha = 0.9f), RoundedCornerShape(2.dp))
            .clickable { onEmergencyStop() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmergencyStopGlyph()
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    strings.emergencyStop,
                    style = McType.button
                        .copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        .cjkTuned(strings.emergencyStop),
                    color = McColors.TextOnDanger,
                )
                Text(
                    if (state.link.relayRunning) {
                        strings.estopSubtitleOnline
                    } else {
                        strings.estopSubtitleOffline
                    },
                    style = McType.caption,
                    color = McColors.TextOnDanger.copy(alpha = 0.85f),
                )
            }
        }
    }
}
