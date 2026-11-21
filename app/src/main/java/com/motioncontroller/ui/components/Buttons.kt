package com.motioncontroller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.motioncontroller.ui.i18n.uiCase
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType
import com.motioncontroller.ui.theme.cjkTuned

enum class McButtonVariant { PRIMARY, OUTLINE, DANGER, GHOST }

/**
 * 宸ヤ笟鏈烘鎸夐挳锛氭柟瑙掋€佸ぇ鍐欍€佸彲甯﹀壇鏍囬锛堢敤浜庤鏄庝笉鍙敤鍘熷洜/鎿嶄綔鎻愮ず锛夈€? *
 * 涓嶅彲鐢ㄦ椂涓嶄細鈥滅畝鍗曞彉鐏扳€濓細璋冪敤鏂瑰簲鎶婂師鍥犻€氳繃 [subtitle] 鎴栫嫭绔嬫枃妗堟樉绀哄嚭鏉ャ€? */
@Composable
fun TacticalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: McButtonVariant = McButtonVariant.PRIMARY,
    subtitle: String? = null,
    height: Dp = 56.dp,
    accent: Color = McColors.BlueAccent,
    /** 禁用态的描边/文字颜色：普通页面用中性 Dim；E-STOP 传暗红，保持红黑主题。 */
    disabledColor: Color = McColors.TextDim,
    /** 禁用态的容器底色：E-STOP 传 EmergencyPanel，避免深蓝面板泄漏到紧急页。 */
    disabledContainerColor: Color = McColors.SurfaceRaised,
) {
    val container = when {
        !enabled -> disabledContainerColor
        variant == McButtonVariant.PRIMARY -> accent
        variant == McButtonVariant.DANGER -> McColors.Danger
        else -> Color.Transparent
    }
    val contentColor = when {
        !enabled -> disabledColor
        variant == McButtonVariant.PRIMARY || variant == McButtonVariant.DANGER -> McColors.TextOnAccent
        variant == McButtonVariant.GHOST -> McColors.TextSecondary
        else -> accent
    }
    val borderColor = when {
        !enabled -> disabledColor.copy(alpha = 0.6f)
        variant == McButtonVariant.OUTLINE || variant == McButtonVariant.GHOST -> accent.copy(alpha = 0.75f)
        else -> container
    }
    Box(
        modifier = modifier
            .height(height)
            .fillMaxWidth()
            .background(container, RoundedCornerShape(2.dp))
            .border(1.dp, borderColor, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text.uiCase(),
                style = McType.button.cjkTuned(text),
                color = contentColor,
                maxLines = 1,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = McType.caption,
                    color = if (enabled) contentColor.copy(alpha = 0.85f) else disabledColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 妯悜鍒嗘閫夋嫨鍣紙A/B 妯″紡銆佸伐绋嬫爣绛鹃〉锛夈€?*/
@Composable
fun SegmentedSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = McColors.BlueAccent,
    height: Dp = 38.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .border(1.dp, McColors.Border, RoundedCornerShape(2.dp)),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(index) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.uiCase(),
                    style = McType.label.cjkTuned(label),
                    color = when {
                        !enabled -> McColors.TextDim
                        selected -> accent
                        else -> McColors.TextSecondary
                    },
                    maxLines = 1,
                )
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(accent),
                    )
                }
            }
        }
    }
}

/** 鍦嗗舰/鏂瑰舰灏忓浘鏍囨寜閽紙宸ョ▼椤电殑 +/-銆佸睍寮€绛夛級銆?*/
@Composable
fun McMiniButton(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = McColors.BlueAccent,
    size: Dp = 32.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .border(1.dp, if (enabled) accent.copy(alpha = 0.7f) else McColors.Border, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = McType.dataMedium, color = if (enabled) accent else McColors.TextDim)
    }
}

/** 鏍囩 + 鏁板€肩殑涓€琛岋紙宸ョ▼椤靛ぇ閲忎娇鐢級銆?*/
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = McColors.TextPrimary,
    labelColor: Color = McColors.TextSecondary,
    valueStyle: androidx.compose.ui.text.TextStyle = McType.dataSmall,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label.uiCase(),
            style = McType.label.cjkTuned(label),
            color = labelColor,
            modifier = Modifier.width(118.dp),
        )
        Text(value, style = valueStyle, color = valueColor)
    }
}
