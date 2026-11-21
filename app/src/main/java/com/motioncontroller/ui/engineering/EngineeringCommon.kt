package com.motioncontroller.ui.engineering

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.motioncontroller.ui.components.McPanel
import com.motioncontroller.ui.components.McPanelHeader
import com.motioncontroller.ui.theme.McColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ENGINEERING 工程控制台的共享脚手架与格式化工具。
 *
 * 只做展示派生：所有数值都来自 [com.motioncontroller.service.UiState] /
 * [com.motioncontroller.ui.model.DiagnosticsText]，不产生任何虚构值。
 */

/** 工程页统一内容容器：整页纵向滚动 + 12dp 内边距 + 12dp 条目间距。 */
@Composable
internal fun EngineeringTabScaffold(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

/** 工程面板：McPanel + 丝印标题 + 可选右上角状态/动作。 */
@Composable
internal fun EngineeringPanel(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    McPanel(modifier = modifier.fillMaxWidth(), accent = accent) {
        McPanelHeader(title = title, trailing = trailing)
        content()
    }
}

/** 就绪/等待语义色（Success = 可用，Warning = 等待/降级；不用于严重故障）。 */
internal fun availabilityColor(available: Boolean): Color =
    if (available) McColors.Success else McColors.Warning

/** 概率（0..1）→ 百分比文案；NaN/Inf 显示 "--"（不伪造 0%）。 */
internal fun percentText(fraction: Float): String {
    if (!fraction.isFinite()) return "--"
    return "%.1f%%".format(Locale.US, fraction.coerceIn(0f, 1f) * 100f)
}

/** 数值 → 固定小数位文案；NaN/Inf 显示 "--"。 */
internal fun decimalText(value: Float, digits: Int): String {
    if (!value.isFinite()) return "--"
    return "%.${digits}f".format(Locale.US, value)
}

private val engineeringClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** 墙钟时间（System.currentTimeMillis 基准）→ HH:mm:ss.SSS；<=0 显示 "--"。 */
internal fun clockText(epochMs: Long): String {
    if (epochMs <= 0L) return "--"
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(engineeringClock)
}
