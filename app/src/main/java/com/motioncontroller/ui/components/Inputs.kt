package com.motioncontroller.ui.components

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.motioncontroller.ui.i18n.uiCase
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType
import com.motioncontroller.ui.theme.cjkTuned

/** 宸ョ▼椤佃緭鍏ユ锛堢鍙ｃ€佸湴鍧€绛夛級锛屾柟瑙?+ 绛夊瀛椼€?*/
@Composable
fun McInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label.uiCase(), style = McType.label.cjkTuned(label)) },
        singleLine = true,
        enabled = enabled,
        textStyle = McType.dataMedium,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = McColors.TextPrimary,
            unfocusedTextColor = McColors.TextPrimary,
            focusedBorderColor = McColors.BlueAccent,
            unfocusedBorderColor = McColors.Border,
            focusedLabelColor = McColors.BlueAccent,
            unfocusedLabelColor = McColors.TextDim,
            cursorColor = McColors.BlueAccent,
            disabledTextColor = McColors.TextDim,
            disabledBorderColor = McColors.Border,
            disabledLabelColor = McColors.TextDim,
        ),
    )
}

/** 宸ヤ笟椋庢牸婊戞潌锛堟鑹叉媷鎸?+ 缁嗚建锛夈€?*/
@Composable
fun McSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color = McColors.BlueAccent,
) {
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = McColors.Border,
            disabledThumbColor = McColors.TextDim,
            disabledActiveTrackColor = McColors.TextDim,
            disabledInactiveTrackColor = McColors.Border,
        ),
    )
}

/**
 * 鏄惧紡纭瀵硅瘽妗嗭紙鎬ュ仠澶嶄綅绛夊畨鍏ㄦ搷浣滃繀椤讳簩娆＄‘璁わ級銆? * 鍗遍櫓鎿嶄綔浣跨敤绾㈣壊纭鎸夐挳銆? */
@Composable
fun McConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String,
    danger: Boolean = false,
    /** 对话框底色：E-STOP 复位确认传独立红黑 EmergencyPanel。 */
    containerColor: Color = McColors.SurfaceRaised,
    /** 确认按钮文字色：E-STOP 传 EmergencyBrightRed。 */
    confirmColor: Color = if (danger) McColors.Danger else McColors.BlueAccent,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = containerColor,
        tonalElevation = 0.dp,
        title = {
            Text(title.uiCase(), style = McType.labelLarge.cjkTuned(title), color = McColors.TextPrimary)
        },
        text = { Text(body, style = McType.body, color = McColors.TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel.uiCase(),
                    style = McType.labelLarge.cjkTuned(confirmLabel),
                    color = confirmColor,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    dismissLabel.uiCase(),
                    style = McType.labelLarge.cjkTuned(dismissLabel),
                    color = McColors.TextSecondary,
                )
            }
        },
    )
}

/**
 * Android銆屽噺灏戝姩鎬佹晥鏋溿€嶏細Animator 鏃堕暱缂╂斁涓?0 鏃惰繑鍥?true锛堢郴缁熻缃?/ 寮€鍙戣€呴€夐」锛夈€? * 鎵€鏈夐潪蹇呰鍔ㄧ敾閮藉簲璺宠繃锛屾€ュ仠/鐘舵€佸垏鎹笉鍙楀奖鍝嶃€? */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val animator = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            val transition = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f,
            )
            animator == 0f || transition == 0f
        }.getOrDefault(false)
    }
}

/** 涓よ璇存槑鏂囧瓧锛堟爣棰?+ 姝ｆ枃锛夈€?*/
@Composable
fun McNotice(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    color: Color = McColors.TextSecondary,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(title.uiCase(), style = McType.label.cjkTuned(title), color = color)
        Spacer(Modifier.height(2.dp))
        Text(body, style = McType.caption, color = McColors.TextDim)
    }
}
