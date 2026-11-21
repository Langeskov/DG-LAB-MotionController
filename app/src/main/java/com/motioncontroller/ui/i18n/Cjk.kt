package com.motioncontroller.ui.i18n

/**
 * CJK（中日韩）文本感知的小工具（纯 Kotlin，无 Android/Compose 依赖，可 JVM 单测）。
 *
 * 背景：EVA 工业丝印风格对拉丁标签使用大写 + 宽字距；但对中文无效——
 * `uppercase()` 对汉字是恒等变换，宽字距反而会把汉字拉散、破坏阅读节奏。
 * 因此所有做「大写 + 字距」处理的组件都应先经过 [uiCase] 与排版层的 CJK 判断：
 *  - 中文文本：不 uppercase、紧凑字距（见 ui/theme/Type.kt 的 TextStyle.cjkTuned）、行高 1.3~1.5；
 *  - 拉丁文本：保持原有丝印风格不变。
 */

/** 字符是否属于 CJK 表意文字 / 兼容区 / 全角符号（不含假名以外的普通标点）。 */
fun Char.isCjk(): Boolean {
    val c = code
    return (c in 0x2E80..0x303F) || // CJK 部首、假名、CJK 符号
        (c in 0x3040..0x30FF) || // 平假名 / 片假名
        (c in 0x3400..0x4DBF) || // CJK 扩展 A
        (c in 0x4E00..0x9FFF) || // CJK 基本区
        (c in 0xF900..0xFAFF) || // CJK 兼容表意
        (c in 0xFF00..0xFFEF) // 全角字母 / 标点
}

/** 文本是否包含 CJK 字符（决定大写/字距策略）。 */
fun String.hasCjk(): Boolean = any { it.isCjk() }

/**
 * 丝印大小写：拉丁文本照常 uppercase（保留英文小标签风格）；
 * 含 CJK 的文本原样返回（uppercase 对汉字无效，且中英混排以原文为准）。
 */
fun String.uiCase(): String = if (hasCjk()) this else uppercase()
