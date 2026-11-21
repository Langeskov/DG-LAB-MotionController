package com.motioncontroller.relay

import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 端口释放探测（问题4：引擎快速重启时旧 relay 端口偶发未释放 → `BindException`）。
 *
 * [isPortFree] 用一次「裸 bind」探测端口当前是否可绑定（[ServerSocket.setReuseAddress] = false，
 * 与任何监听者互斥，Windows/Linux 语义一致）：绑定成功立即关闭，不留残留。
 *
 * 用于 [V4RelayServer] 停止流程：`WebSocketServer.stop(timeout)` 返回后再等端口真正释放，
 * 保证「旧 relay 完全停止 → 新 relay 再绑定」的顺序；等不到就交给绑定重试兜底。
 */
internal object PortProbe {

    /** 探测端口当前是否可绑定（即没有监听者）。port ≤ 0 视为自由（系统分配）。 */
    fun isPortFree(address: InetSocketAddress): Boolean {
        if (address.port <= 0) return true
        return try {
            ServerSocket().use { probe ->
                probe.reuseAddress = false
                probe.bind(address, 1)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 等待端口释放，最长 [timeoutMs]（每 [pollMs] 探测一次）。
     *
     * @return true = 已释放；false = 超时（调用方应继续走绑定重试兜底）。
     */
    fun awaitPortReleased(
        address: InetSocketAddress,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollMs: Long = DEFAULT_POLL_MS,
    ): Boolean {
        if (address.port <= 0) return true
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (true) {
            if (isPortFree(address)) return true
            if (System.currentTimeMillis() >= deadline) return false
            try {
                Thread.sleep(pollMs.coerceAtLeast(1L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return isPortFree(address)
            }
        }
    }

    /** stop() 返回后等端口释放的上限（ms）；通常 stop 后立即自由，只有内核回收慢时才用到。 */
    const val DEFAULT_TIMEOUT_MS = 300L

    /** 探测间隔（ms）。 */
    const val DEFAULT_POLL_MS = 25L
}
