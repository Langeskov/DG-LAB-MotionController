package com.motioncontroller.relay

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ThreadFactory

/**
 * 展示用地址选择：绑定 `0.0.0.0`（双机/热点形态）时，给 DG-LAB APP 的 URL 必须是一个局域网 IP，
 * 不能是通配地址，也不能是 VPN/蜂窝地址（APP 在另一台手机上未必能路由到）。
 */
object NetworkAddress {

    /** 绑定的 host 是否属于"所有网卡"通配地址 */
    fun isWildcard(bindHost: String): Boolean {
        val host = bindHost.trim()
        return host == "0.0.0.0" || host == "::" || host == "[::]" || host == "*" || host.isEmpty()
    }

    /**
     * 给 APP 看的 host：显式绑定的地址原样返回；通配地址则换成最合适的非回环 IPv4
     * （优先 Wi-Fi/以太网，其次其它，最后才 VPN/蜂窝），找不到退回 127.0.0.1。
     */
    fun displayHostFor(bindHost: String): String {
        val host = bindHost.trim()
        if (!isWildcard(host)) return host
        return firstNonLoopbackIpv4() ?: "127.0.0.1"
    }

    fun firstNonLoopbackIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var bestRank = Int.MAX_VALUE
            var bestAddress: String? = null
            for (nic in interfaces) {
                if (!nic.isUp || nic.isLoopback) continue
                val rank = interfaceRank(nic.name)
                for (address in nic.inetAddresses) {
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val hostAddress = address.hostAddress ?: continue
                    // 同 rank 保留枚举顺序（Android 上 wlan 通常在前面）
                    if (rank < bestRank) {
                        bestRank = rank
                        bestAddress = hostAddress
                    }
                }
            }
            bestAddress
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 网卡优先级：越小越优先。
     *
     * - 0 = Wi-Fi / 以太网 / 热点（`wlan*`、`eth*`、`ap*`、`swlan*`）
     * - 1 = 其它（未知类型，保留兜底）
     * - 2 = VPN / 蜂窝（`tun*`、`ppp*`、`rmnet*`、`ccmni*` 等）
     *
     * 双机场景里用 VPN/蜂窝地址生成的二维码，另一台手机大概率连不上，所以必须排在最后。
     */
    internal fun interfaceRank(name: String): Int {
        val normalized = name.trim().lowercase()
        return when {
            normalized.startsWith("wlan") ||
                normalized.startsWith("eth") ||
                normalized.startsWith("wifi") ||
                normalized.startsWith("ap") ||
                normalized.startsWith("swlan") -> 0
            normalized.startsWith("tun") ||
                normalized.startsWith("tap") ||
                normalized.startsWith("ppp") ||
                normalized.startsWith("rmnet") ||
                normalized.startsWith("rev_rmnet") ||
                normalized.startsWith("ccmni") ||
                normalized.startsWith("v4-") ||
                normalized.startsWith("dummy") ||
                normalized.startsWith("clat") -> 2
            else -> 1
        }
    }
}

/** 守护线程池工厂：中继/控制器的后台线程不能阻止 JVM 退出（JVM 单测必须能自然结束）。 */
internal fun daemonThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
    Thread(runnable, name).apply { isDaemon = true }
}
