package com.motioncontroller.ui.engineering

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motioncontroller.mapping.MappingConfig
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.components.ScreenHeader
import com.motioncontroller.ui.components.SegmentedSelector
import com.motioncontroller.ui.i18n.LanguageMode
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.theme.McColors

/**
 * ENGINEERING 工程控制台入口：顶部页眉 + 4 区域分段选择 + 滚动内容区。
 *
 * 四个区域（顺序即 SegmentedSelector 索引）：
 *  - CONNECTION：中继绑定、客户端地址/二维码、APP clients、设备、链路诊断；
 *  - MOTION：模型/类别/置信度/概率、IMU/GNSS、推理管线；
 *  - OUTPUT：classBase / manualOffset / safetyMax、只读映射参数、A/B 三态强度；
 *  - DIAGNOSTICS：导出、控制状态、急停传递阶段、性能、错误、最近事件、界面语言。
 *
 * 所有数据来自 [UiState]；本页面不做任何控制会话操作（arm/estop 在 OPERATION 页）。
 */
@Composable
fun EngineeringScreen(
    state: UiState,
    appVersion: String,
    languageMode: LanguageMode,
    onLanguageModeChange: (LanguageMode) -> Unit,
    onBack: () -> Unit,
    onCopyAddress: () -> Unit,
    onRestartRelay: (host: String, port: Int) -> Unit,
    onApplyMapping: (MappingConfig) -> Unit,
    onManualOffset: (Float) -> Unit,
    onCopyLogs: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val strings = rememberUiStrings()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tab = selectedTab.coerceIn(0, TAB_COUNT - 1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(McColors.Background),
    ) {
        ScreenHeader(
            title = strings.engineeringTitle,
            tag = strings.diagnosticConsole,
            onBack = onBack,
            backLabel = strings.navBack,
        )
        SegmentedSelector(
            options = engineeringTabLabels(strings),
            selectedIndex = tab,
            onSelect = { selectedTab = it },
            accent = McColors.BlueAccent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (tab) {
                0 -> EngineeringConnectionTab(
                    state = state,
                    onCopyAddress = onCopyAddress,
                    onRestartRelay = onRestartRelay,
                )

                1 -> EngineeringMotionTab(state = state)

                2 -> EngineeringOutputTab(
                    state = state,
                    onApplyMapping = onApplyMapping,
                    onManualOffset = onManualOffset,
                )

                else -> EngineeringDiagnosticsTab(
                    state = state,
                    appVersion = appVersion,
                    languageMode = languageMode,
                    onLanguageModeChange = onLanguageModeChange,
                    onCopyLogs = onCopyLogs,
                    onExportDiagnostics = onExportDiagnostics,
                )
            }
        }
    }
}

/** 四个工程区域（不要新增/重排：SegmentedSelector 以索引选择内容）。 */
private const val TAB_COUNT = 4

/** 工程区域标签（顺序即索引；文案来自 UiStrings）。 */
private fun engineeringTabLabels(strings: com.motioncontroller.ui.i18n.UiStrings): List<String> =
    listOf(strings.tabConnection, strings.tabMotion, strings.tabOutput, strings.tabDiagnostics)
