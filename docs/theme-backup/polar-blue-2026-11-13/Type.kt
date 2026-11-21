package com.motioncontroller.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.motioncontroller.ui.i18n.hasCjk

/**
 * 工业终端字体规则：
 *  - 数值 / 地址 / 日志 / 时间一律等宽（Monospace），保持数据列对齐；
 *  - 标签 / 标题用无衬线 + 大写 + 字距，模拟设备丝印；
 *  - 不使用花哨字体，不引入第三方字体资源（体积 + 渲染风险）。
 */
object McType {
    /** 速度主数字（汽车仪表盘风格）。 */
    val speedValue = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Light,
        fontSize = 84.sp,
        lineHeight = 88.sp,
        letterSpacing = (-2).sp,
    )

    /** 大号数据：运动模式、目标强度等。 */
    val dataXl = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    )

    val dataLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    )

    val dataMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 19.sp,
    )

    val dataSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** 设备丝印标签：大写 + 适度字距（调用方自行 uppercase；普通文字不过度拉宽）。 */
    val label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,
    )

    val labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
    )

    val title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.9.sp,
    )

    val body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )

    val caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )

    val button = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.1.sp,
    )
}

/**
 * CJK 感知的样式微调：中文/日文文本用紧凑字距与 1.45 倍行高（1.3~1.5 区间），
 * 避免丝印宽字距把汉字拉散；纯拉丁文本原样返回，英文风格不受影响。
 *
 * 与 [com.motioncontroller.ui.i18n.uiCase] 配套使用（中文不做 uppercase）。
 */
fun TextStyle.cjkTuned(text: String): TextStyle =
    if (text.hasCjk()) {
        copy(
            letterSpacing = 0.2.sp,
            lineHeight = (fontSize.value * 1.45f).sp,
        )
    } else {
        this
    }

val Typography = Typography(
    displayLarge = McType.speedValue,
    displayMedium = McType.dataXl,
    headlineMedium = McType.title,
    titleMedium = McType.labelLarge,
    bodyLarge = McType.body,
    bodyMedium = McType.body,
    bodySmall = McType.caption,
    labelLarge = McType.labelLarge,
    labelMedium = McType.label,
    labelSmall = McType.label,
)
