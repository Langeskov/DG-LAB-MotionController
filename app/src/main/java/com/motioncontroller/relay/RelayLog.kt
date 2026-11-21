package com.motioncontroller.relay

import android.util.Log

/**
 * 统一日志出口。
 *
 * 中继/控制器要能在纯 JVM 单测里离线运行：Android 的 `android.util.Log` 在单测中要么是
 * `returnDefaultValues` 的空实现，要么抛 "not mocked" 的 RuntimeException。这里两条路都兜住，
 * 保证协议逻辑不因为打日志而失败。
 */
internal object RelayLog {

    fun i(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: Throwable) {
            println("[$tag] $message")
        }
    }
}

/**
 * 异常统一描述：类名 + message + 因果链。
 *
 * 审计要求错误日志必须能定位问题（只打 `message` 时 NPE 之类完全没有信息）。
 */
internal fun describeThrowable(t: Throwable, maxDepth: Int = 3): String {
    val builder = StringBuilder()
    builder.append(t.javaClass.simpleName)
    t.message?.let { builder.append(": ").append(it) }
    var cause = t.cause
    var depth = 0
    while (cause != null && cause !== t && depth < maxDepth) {
        builder.append(" <- ").append(cause.javaClass.simpleName)
        cause.message?.let { builder.append(": ").append(it) }
        cause = cause.cause
        depth++
    }
    return builder.toString()
}
