package com.motioncontroller.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * POLAR BLUE 单一深色主题。
 *
 * 约束（对应 UI V2.0 规范）：
 *  - 本次不实现浅色/深色切换，也不跟随系统主题；
 *  - 不使用 Android 动态取色（dynamic color 会破坏品牌色与对比度）；
 *  - 所有普通页面统一使用深海蓝/冰蓝；E-STOP 页面使用独立红黑 Palette（见 ui/emergency）。
 */
private val PolarBlueScheme = darkColorScheme(
    primary = McColors.BlueAccent,
    onPrimary = McColors.TextOnAccent,
    primaryContainer = McColors.BlueDim,
    onPrimaryContainer = McColors.TextPrimary,
    secondary = McColors.SteelBlue,
    onSecondary = McColors.TextOnAccent,
    tertiary = McColors.Warning,
    onTertiary = McColors.TextOnAccent,
    background = McColors.Background,
    onBackground = McColors.TextPrimary,
    surface = McColors.Surface,
    onSurface = McColors.TextPrimary,
    surfaceVariant = McColors.SurfaceRaised,
    onSurfaceVariant = McColors.TextSecondary,
    surfaceContainer = McColors.Surface,
    surfaceContainerHigh = McColors.SurfaceRaised,
    surfaceContainerHighest = McColors.SurfaceRaised,
    surfaceContainerLow = McColors.SurfaceInset,
    surfaceContainerLowest = McColors.BackgroundDeep,
    outline = McColors.Border,
    outlineVariant = McColors.Divider,
    error = McColors.Danger,
    onError = McColors.TextOnDanger,
    errorContainer = McColors.DangerDim,
    onErrorContainer = McColors.TextPrimary,
)

@Composable
fun MotionControllerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PolarBlueScheme,
        typography = Typography,
        shapes = McShapes,
        content = content,
    )
}
