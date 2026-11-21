package com.motioncontroller.ui.emergency

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.components.CheckRow
import com.motioncontroller.ui.components.McPanel
import com.motioncontroller.ui.components.McTag
import com.motioncontroller.ui.components.SectionLabel
import com.motioncontroller.ui.components.WarningBanner
import com.motioncontroller.ui.components.checkStatusColor
import com.motioncontroller.ui.i18n.fmt
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.model.CheckStatus
import com.motioncontroller.ui.model.RecoveryModel
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType
import com.motioncontroller.ui.theme.cjkTuned

/**
 * E-STOP 复位后的 RECOVERY CHECK 页面（纯 UI，不启动/重启服务或连接）。
 *
 * 视觉上完全脱离 EmergencyBorder：边框已在 RELEASED 阶段退出，本页使用稳定的
 * 全屏根布局 + POLAR BLUE 背景；检查项来自 [RecoveryModel]（复用 PreflightModel 的既有规则）。
 * 页面不自行决定导航——是否进入 OPERATION 由 MainActivity 依据 RecoveryModel.canRestore 决定。
 */
@Composable
fun RecoveryScreen(
    state: UiState,
    serviceBound: Boolean,
    backgroundColor: Color,
) {
    val strings = rememberUiStrings()
    val snapshot = RecoveryModel.derive(state, serviceBound, strings)
    val warning = snapshot.checks.firstOrNull { it.id == "estop_recovery" }
    val checks = snapshot.checks.filter { it.id != "estop_recovery" }
    val passed = checks.count { it.status == CheckStatus.PASS }

    Box(Modifier.fillMaxSize().background(backgroundColor)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // ── 标题区域：检查阶段/通过计数（真实计数，不是假进度） ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                McTag(strings.emergencyExitReinitTag, McColors.IceBlue)
                Spacer(Modifier.weight(1f))
                McTag(
                    fmt(strings.recoveryPassCount, passed, checks.size),
                    if (snapshot.canRestore) McColors.Success else McColors.SteelBlue,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = strings.emergencyExitReinitTitle,
                style = McType.title.copy(fontSize = 24.sp)
                    .cjkTuned(strings.emergencyExitReinitTitle),
                color = McColors.IceBlue,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = strings.recoverySessionNote,
                style = McType.body.cjkTuned(strings.recoverySessionNote),
                color = McColors.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))

            // ── 真实检查项列表（服务/中继/APP/设备/模型/Scaler/GNSS/控制会话） ──
            McPanel(
                modifier = Modifier.fillMaxWidth().weight(1f),
                backgroundColor = McColors.Surface,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel(strings.recoveryCheckingTag, color = McColors.TextDim)
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    checks.forEach { check ->
                        CheckRow(
                            label = check.label,
                            statusText = strings.checkStatus(check.status),
                            color = checkStatusColor(check.status),
                            detail = check.detail,
                        )
                    }
                }
            }

            if (warning != null) {
                Spacer(Modifier.height(8.dp))
                WarningBanner(
                    title = warning.label,
                    body = warning.detail,
                    color = McColors.Warning,
                )
            }
            if (!snapshot.canRestore) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = snapshot.blocker?.let { "${it.label}: ${it.detail}" } ?: strings.notReady,
                    style = McType.caption.cjkTuned(strings.notReady),
                    color = McColors.Warning,
                )
            }
        }
    }
}

/** 恢复成功、回到 OPERATION 后的短暂提示（由 MainActivity 覆盖显示约 900ms）。 */
@Composable
fun ControlSessionRestoredBanner(modifier: Modifier = Modifier) {
    val strings = rememberUiStrings()
    McPanel(
        modifier = modifier.fillMaxWidth(),
        accent = McColors.Success.copy(alpha = 0.55f),
        backgroundColor = McColors.SurfaceRaised,
        contentPadding = 10.dp,
    ) {
        McTag(strings.emergencyRestoredTag, McColors.Success, filled = true)
        Spacer(Modifier.height(4.dp))
        Text(
            text = strings.emergencyRestoredTitle,
            style = McType.labelLarge.cjkTuned(strings.emergencyRestoredTitle),
            color = McColors.TextPrimary,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = strings.emergencyRestoredBody,
            style = McType.caption.cjkTuned(strings.emergencyRestoredBody),
            color = McColors.TextSecondary,
        )
    }
}
