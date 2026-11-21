package com.motioncontroller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.motioncontroller.ui.i18n.uiCase
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType
import com.motioncontroller.ui.theme.cjkTuned

/**
 * 工业面板：1dp 描边 + 深色底，不用阴影/大圆角。
 * [accent] 非空时描边带强调色（表示该面板与当前操作/告警相关）。
 */
@Composable
fun McPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    contentPadding: Dp = 10.dp,
    /** 面板底色：普通页面用 POLAR Surface；E-STOP 传独立红黑 EmergencyPanel。 */
    backgroundColor: Color = McColors.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(2.dp))
            .border(
                width = 1.dp,
                color = accent?.copy(alpha = 0.55f) ?: McColors.Border,
                shape = RoundedCornerShape(2.dp),
            )
            .padding(contentPadding),
        content = content,
    )
}

/** 面板标题：左侧大写丝印标签，右侧可选状态/操作。 */
@Composable
fun McPanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = McColors.TextSecondary,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(
            text = title,
            modifier = Modifier.weight(1f),
            color = color,
        )
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(thickness = 1.dp, color = McColors.Divider)
    Spacer(Modifier.height(8.dp))
}

/** 大写宽字距丝印标签（CJK 文本自动改为紧凑字距、不做 uppercase）。 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = McColors.TextSecondary,
    maxLines: Int = 1,
) {
    Text(
        text = text.uiCase(),
        modifier = modifier,
        style = McType.label.cjkTuned(text),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 数值/地址等等宽文本。 */
@Composable
fun DataText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = McColors.TextPrimary,
    style: androidx.compose.ui.text.TextStyle = McType.dataMedium,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 小尺寸状态标签（描边 + 等宽字）。 */
@Composable
fun McTag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Box(
        modifier = modifier
            .background(
                if (filled) color else color.copy(alpha = 0.10f),
                RoundedCornerShape(2.dp),
            )
            .border(1.dp, color.copy(alpha = if (filled) 1f else 0.55f), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text.uiCase(),
            style = McType.label.cjkTuned(text),
            color = if (filled) McColors.TextOnAccent else color,
            maxLines = 1,
        )
    }
}

/** 状态灯（不闪烁；颜色表达语义）。 */
@Composable
fun StatusLamp(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    hollow: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(if (hollow) Color.Transparent else color, RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.9f), RoundedCornerShape(50)),
    )
}

/** 顶部页眉：返回 + 标题 + 标签 + 尾部动作。 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    tagColor: Color = McColors.BlueAccent,
    onBack: (() -> Unit)? = null,
    backLabel: String,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(McColors.Background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            // 返回是高频操作：≥44dp 触控区、明确可点、语义标注（TalkBack 读"返回"）。
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .border(1.dp, McColors.BorderStrong, RoundedCornerShape(2.dp))
                    .background(McColors.Surface, RoundedCornerShape(2.dp))
                    .clickable(onClick = onBack)
                    .semantics(mergeDescendants = true) {
                        contentDescription = backLabel
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "◀",
                    style = McType.dataSmall,
                    color = McColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
        Text(
            title.uiCase(),
            style = McType.labelLarge.cjkTuned(title),
            color = McColors.TextPrimary,
        )
        if (tag != null) McTag(tag, tagColor)
        Box(Modifier.weight(1f)) {}
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
    HorizontalDivider(thickness = 1.dp, color = McColors.Divider)
}

/** 齿轮入口（用字符绘制，避免引入 material-icons 依赖）。 */
@Composable
fun GearButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .border(1.dp, McColors.BorderStrong, RoundedCornerShape(2.dp))
            .background(McColors.Surface)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text("⚙", style = McType.dataLarge, color = if (enabled) McColors.TextPrimary else McColors.TextDim)
        }
    }
}
