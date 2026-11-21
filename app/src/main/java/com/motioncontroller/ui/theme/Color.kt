package com.motioncontroller.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * MotionController UI V2.0 — **POLAR BLUE** 视觉系统（深海蓝 + 冰蓝）。
 *
 * 语义约定（不要在各页面重新硬编码相近颜色）：
 *  - 背景/面板：深海蓝层级（Background / Surface / SurfaceRaised / SurfaceInset），**不使用大面积纯黑**；
 *  - [IceBlue]：当前选中、重要实时数据、控制权强调；
 *  - [BlueAccent]：用户可执行的主要操作（按钮/滑块/活动指示）；
 *  - [SteelBlue]：辅助信息（工程数据、次级标签），不做安全语义；
 *  - [Warning]：等待连接 / 数据过期 / 普通警告；
 *  - [Success]：连接成功 / 自检通过 / 状态确认；
 *  - [Danger]：严重故障；**E-STOP 使用独立红黑 Emergency Palette**（见文件末尾）。
 *
 * 旧 EVA 色彩 Token（Orange/Purple/Yellow/Red/Green…）与零引用的语义 Token 已于
 * 2026-11-15 死代码清理中移除（全仓零引用，历史值见 `docs/theme-backup/`）。
 */
object McColors {
    // ───────────────────────── 背景 / 面板层级 ─────────────────────────
    // 2026-11-14 精修：普通界面主背景统一为 #203348（深海蓝），与系统栏保持一致；
    // POLAR 层级整体按同一色相上移，保证面板/输入区仍有清晰层次。
    val Background = Color(0xFF203348)
    val BackgroundDeep = Color(0xFF1A2B3D)
    /** 渐变顶部：普通界面统一使用 #203348，与状态栏/导航栏保持无接缝连续。 */
    val BackgroundGradientTop = Background

    val Surface = Color(0xFF263D52)
    val SurfaceRaised = Color(0xFF2D465C)
    val SurfaceInset = Color(0xFF182B3D)

    val Border = Color(0xFF40586D)
    val BorderStrong = Color(0xFF52708A)
    val Divider = Color(0xFF31495E)

    // ───────────────────────── 冰蓝 / 主强调 ─────────────────────────
    /** 主要强调：选中状态、重要实时数据、控制权高亮。 */
    val IceBlue = Color(0xFFA6E9F8)
    /** 操作按钮与活动状态（可点击的主要操作）。 */
    val BlueAccent = Color(0xFF62CDEB)
    /** 次级强调（边框、轨道、辅助图形）。 */
    val BlueDim = Color(0xFF397D9F)

    /** 辅助信息色（工程/次级标签使用）。 */
    val SteelBlue = Color(0xFF9DB9C9)
    val SteelBlueDim = Color(0xFF5E7F93)

    // ───────────────────────── 语义状态 ─────────────────────────
    val Success = Color(0xFF50C9A0)
    val Warning = Color(0xFFE8B65B)
    val Danger = Color(0xFFF04452)
    val DangerDim = Color(0xFF7E1F28)

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
    val EmergencyRed = Color(0xFFE01F1F)
    val EmergencyRedDim = Color(0xFF7E1010)
    val EmergencyBrightRed = Color(0xFFFF3B30)
}
