package com.motioncontroller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.motioncontroller.ui.model.CheckStatus

/** 鑷/鐘舵€佽锛氱伅 + 鍚嶇О + 鐘舵€佸瓧 + 璇存槑銆?*/
@Composable
fun CheckRow(
    label: String,
    statusText: String,
    color: Color,
    detail: String? = null,
    modifier: Modifier = Modifier,
    hollowLamp: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusLamp(color = color, hollow = hollowLamp)
            Text(
                label.uiCase(),
                style = McType.label.cjkTuned(label),
                color = McColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(statusText.uiCase(), style = McType.labelLarge.cjkTuned(statusText), color = color)
        }
        if (detail != null) {
            Spacer(Modifier.height(1.dp))
            Text(
                detail,
                style = McType.caption,
                color = McColors.TextDim,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** 鑷鐘舵€?鈫?棰滆壊锛堥泦涓畾涔夛紝閬垮厤鍚勯〉闈㈣嚜閫犺壊锛夈€?*/
fun checkStatusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> McColors.Success
    CheckStatus.WAITING -> McColors.Warning
    CheckStatus.FAIL -> McColors.Danger
    CheckStatus.DISCONNECTED -> McColors.TextDim
}

/** 姘村钩寮哄害鏉°€?*/
@Composable
fun MeterBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = McColors.BlueAccent,
    height: Dp = 8.dp,
    trackColor: Color = McColors.SurfaceInset,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(trackColor, RoundedCornerShape(1.dp))
            .border(1.dp, McColors.Border, RoundedCornerShape(1.dp)),
    ) {
        val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

/** 鍛婅鏉★紙榛?璀﹀憡锛岀孩=鍗遍櫓锛夈€備笉浣跨敤鎸佺画鍔ㄧ敾銆?*/
@Composable
fun WarningBanner(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    color: Color = McColors.Warning,
    glyph: String = "!",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(2.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .width(18.dp)
                .height(18.dp)
                .background(color, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, style = McType.label, color = McColors.TextOnAccent)
        }
        Column(Modifier.weight(1f)) {
            Text(title.uiCase(), style = McType.labelLarge.cjkTuned(title), color = color)
            if (body != null) {
                Spacer(Modifier.height(2.dp))
                Text(body, style = McType.caption, color = McColors.TextSecondary)
            }
        }
    }
}

/** 鎸囨爣鏍硷細鏍囩 + 澶ф暟鍊?+ 鍗曚綅锛堝伐绋嬭瘖鏂?/ 杈撳嚭姒傚喌锛夈€?*/
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = McColors.TextPrimary,
    accent: Color = McColors.Border,
) {
    Column(
        modifier = modifier
            .background(McColors.SurfaceInset, RoundedCornerShape(2.dp))
            .border(1.dp, accent, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        SectionLabel(label, color = McColors.TextDim, maxLines = 2)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = McType.dataLarge, color = valueColor, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = McType.dataSmall, color = McColors.TextDim)
            }
        }
    }
}

/** 绌烘暟鎹彁绀恒€?*/
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = McType.caption, color = McColors.TextDim, modifier = modifier)
}

/** 鎬ュ仠鎸夐挳涓婄殑宸ヤ笟璀︾ず鍥惧舰锛堝渾鐜?+ 鎰熷徆鍙凤紝绾?Compose 缁樺埗锛屼笉寮曞叆鍥炬爣渚濊禆锛夈€?*/
@Composable
fun EmergencyStopGlyph(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(
        modifier = modifier
            .size(size)
            .border(2.dp, McColors.TextOnDanger, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Text("!", style = McType.labelLarge, color = McColors.TextOnDanger)
    }
}
