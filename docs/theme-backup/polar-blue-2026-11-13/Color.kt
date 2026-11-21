package com.motioncontroller.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * MotionController UI V2.0 — **POLAR BLUE** 视觉系统（深海蓝 + 冰蓝）。
 *
 * 语义约定（不要在各页面重新硬编码相近颜色）：
 *  - 背景/面板：深海蓝层级（Background / Surface / SurfaceRaised / SurfaceInset），**不使用大面积纯黑**；
 *  - [IceBlue] / [IceBlueBright]：当前选中、重要实时数据、控制权强调；
 *  - [BlueAccent]：用户可执行的主要操作（按钮/滑块/活动指示）；
 *  - [SteelBlue]：辅助信息（工程数据、次级标签），不做安全语义；
 *  - [Warning]：等待连接 / 数据过期 / 普通警告；
 *  - [Success]：连接成功 / 自检通过 / 状态确认；
 *  - [Danger]：严重故障；**E-STOP 使用独立红黑 Emergency Palette**（见文件末尾）。
 *
 * 旧 EVA 色彩 Token 保留为**兼容别名**（Orange/Purple/Yellow/Red/Green…），
 * 值已映射到 POLAR BLUE；新代码请使用语义 Token。这样未在本轮逐页改写的组件
 * 也能立即统一到新主题，避免大范围改接口。
 */
object McColors {
    // ───────────────────────── 背景 / 面板层级 ─────────────────────────
    val Background = Color(0xFF142C40)
    val BackgroundDeep = Color(0xFF0E2233)
    /** 渐变顶部（比 Background 略亮，用于主界面的轻微纵深，不引入大面积纯黑）。 */
    val BackgroundElevated = Color(0xFF183850)
    val BackgroundGradientTop = BackgroundElevated

    val Surface = Color(0xFF19344A)
    val SurfaceRaised = Color(0xFF24445B)
    val SurfaceInset = Color(0xFF0C2031)

    val Border = Color(0xFF34536A)
    val BorderStrong = Color(0xFF49677D)
    val Divider = Color(0xFF294255)

    // ───────────────────────── 冰蓝 / 主强调 ─────────────────────────
    /** 主要强调：选中状态、重要实时数据、控制权高亮。 */
    val IceBlue = Color(0xFFA6E9F8)
    /** 高亮强调（比 IceBlue 更亮，用于关键读数/活动标签）。 */
    val IceBlueBright = Color(0xFFD0F5FC)
    /** 操作按钮与活动状态（可点击的主要操作）。 */
    val BlueAccent = Color(0xFF62CDEB)
    /** 次级强调（边框、轨道、辅助图形）。 */
    val BlueDim = Color(0xFF397D9F)
    val BlueGlow = Color(0x3362CDEB)

    /** 辅助信息色（替代旧的紫色辅助色；仅工程/次级标签使用）。 */
    val SteelBlue = Color(0xFF9DB9C9)
    val SteelBlueDim = Color(0xFF5E7F93)

    // ───────────────────────── 语义状态 ─────────────────────────
    val Success = Color(0xFF50C9A0)
    val SuccessDim = Color(0xFF1E5A47)
    val Warning = Color(0xFFE8B65B)
    val WarningDim = Color(0xFF6E5518)
    val Danger = Color(0xFFF04452)
    val DangerDim = Color(0xFF7E1F28)
    val DangerGlow = Color(0x40F04452)

    // ───────────────────────── 文本 ─────────────────────────
    val TextPrimary = Color(0xFFEDF6FA)
    val TextSecondary = Color(0xFFB5C8D3)
    /** 第三级文字：用于说明/工程标签，仍保证在深蓝底上可读。 */
    val TextDim = Color(0xFF7A93A3)
    /** 填充按钮（冰蓝底）上的深色文字。 */
    val TextOnAccent = Color(0xFF0B1A26)
    val TextOnDanger = Color(0xFFFFFFFF)

    // ───────────────────────── E-STOP 独立红黑 Palette ─────────────────────────
    // 急停页面不跟随 POLAR BLUE：保持独立、强烈的红黑工业警报语义。
    val EmergencyBlack = Color(0xFF050506)
    val EmergencyPanel = Color(0xFF160A0A)
    val EmergencyStrip = Color(0xFF220707)
    val EmergencyRed = Color(0xFFE01F1F)
    val EmergencyRedDim = Color(0xFF7E1010)
    val EmergencyBrightRed = Color(0xFFFF3B30)

    // ───────────────────────── 旧 Token 兼容别名（值 = POLAR BLUE） ─────────────────────────
    // 仅用于尚未迁移的页面/组件；新代码请直接用上面的语义 Token。
    val Orange = BlueAccent
    val OrangeBright = IceBlueBright
    val OrangeDim = BlueDim
    val OrangeGlow = BlueGlow
    val Purple = SteelBlue
    val PurpleDim = SteelBlueDim
    val Yellow = Warning
    val YellowDim = WarningDim
    val Red = Danger
    val RedDim = DangerDim
    val RedGlow = DangerGlow
    val BrightRed = EmergencyBrightRed
    val Green = Success
    val GreenDim = SuccessDim
}
