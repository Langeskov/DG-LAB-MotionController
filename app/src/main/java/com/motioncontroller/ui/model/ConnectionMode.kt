package com.motioncontroller.ui.model

/**
 * 单机 / 多机连接形态。
 *
 * 注意：这里只描述**监听形态**；展示给 DG-LAB APP 的地址必须始终是客户端可访问的地址
 * （由 UiState.address 提供，0.0.0.0 已在 relay/NetworkAddress 中解析成局域网 IP）。
 * 不要把 0.0.0.0 当成可复制的连接地址。
 */
enum class ConnectionMode(val bindHost: String, val labelEn: String, val labelCn: String) {
    SINGLE("127.0.0.1", "SINGLE", "单机模式"),
    MULTI("0.0.0.0", "MULTI", "多机模式"),
    ;

    val isLan: Boolean get() = this == MULTI

    companion object {
        fun ofBindHost(bindHost: String): ConnectionMode {
            val normalized = bindHost.trim()
            return if (
                normalized == MULTI.bindHost ||
                normalized == "::" ||
                normalized == "[::]" ||
                normalized == "*" ||
                normalized.isEmpty()
            ) {
                MULTI
            } else {
                SINGLE
            }
        }
    }
}
