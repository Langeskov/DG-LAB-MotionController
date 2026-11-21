package com.motioncontroller.dglab

import com.motioncontroller.relay.PortProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 引擎快速重启顺序回归（问题4：偶发「DG-LAB APP 无法连接」，候选根因之一是
 * stopEngine→startEngine 竞速时旧 relay 端口未释放 → BindException）：
 *  - `start()` 必须在旧 relay **完全停止**（端口释放）后才重新绑定：连续快速重启不允许绑定失败；
 *  - `stop()` 返回后端口立即可重绑（旧 relay 不残留监听）；
 *  - 重启期间旧 APP 收到 `controller_disconnected`（关闭码 4000），targetId 跨重启不变
 *    （APP 里手输的地址不失效），新 APP 可立即接入。
 */
class V4EngineRestartOrderTest {

    private var ctl: V4Controller? = null

    @After
    fun tearDown() {
        ctl?.stop()
        ctl = null
    }

    @Test
    fun rapidRestartOnSamePortNeverHitsBindException() {
        val port = pickFreePort()
        val ctl = V4Controller().also { this.ctl = it }

        // 路径 A：不显式 stop，直接 start（引擎快速重启的最短路径）
        repeat(6) {
            ctl.start("127.0.0.1", port, "a1b2c3d4")
            val st = awaitState(ctl) { it.relayRunning }
            assertEquals("重启后端口必须保持不变", port, st.port)
            assertEquals("targetId 跨重启必须复用（APP 手输地址不失效）", "a1b2c3d4", st.targetId)
        }

        // 路径 B：stop→start 交替（stopEngine→startEngine 竞速形态）
        repeat(3) {
            ctl.stop()
            awaitState(ctl) { !it.relayRunning }
            ctl.start("127.0.0.1", port, "a1b2c3d4")
            val st = awaitState(ctl) { it.relayRunning }
            assertEquals("重启后端口必须保持不变", port, st.port)
            assertEquals("targetId 跨重启必须复用", "a1b2c3d4", st.targetId)
        }
    }

    @Test
    fun stopReleasesPortImmediately() {
        val port = pickFreePort()
        val ctl = V4Controller().also { this.ctl = it }
        ctl.start("127.0.0.1", port, "a1b2c3d4")
        awaitState(ctl) { it.relayRunning }

        ctl.stop()

        assertTrue(
            "stop() 返回后端口 $port 必须已释放（旧 relay 完全停止才算 stop 完成）",
            PortProbe.isPortFree(InetSocketAddress("127.0.0.1", port)),
        )
    }

    @Test
    fun restartClosesOldAppSessionWith4000AndAcceptsNewOne() {
        val port = pickFreePort()
        val ctl = V4Controller().also { this.ctl = it }
        ctl.start("127.0.0.1", port, "a1b2c3d4")
        val first = awaitState(ctl) { it.relayRunning }
        val url = "ws://127.0.0.1:${first.port}/?tid=${first.targetId}"

        val oldApp = FakeDglabApp(URI(url))
        try {
            assertTrue(oldApp.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            oldApp.awaitFrame { it.str("type") == "controller_attached" }

            // 不 stop 直接 start（快速重启）：旧 APP 必须收到 controller_disconnected 并被关闭
            ctl.start("127.0.0.1", port, "a1b2c3d4")
            val second = awaitState(ctl) { it.relayRunning }
            assertEquals("重启后 targetId 不变", "a1b2c3d4", second.targetId)

            assertTrue("旧 APP 应被关闭", oldApp.awaitClosed())
            assertEquals("中继停止应以 4000 controller_disconnected 关闭旧 APP", 4000, oldApp.closeCode)
        } finally {
            oldApp.closeQuietly()
        }

        // 新 relay 立即可用：同地址新 APP 可接入
        val newApp = FakeDglabApp(URI(url))
        try {
            assertTrue(newApp.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            newApp.awaitFrame { it.str("type") == "controller_attached" }
        } finally {
            newApp.closeQuietly()
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun pickFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitState(
        ctl: V4Controller,
        timeoutMs: Long = FakeDglabApp.TIMEOUT_MS,
        predicate: (DglabState) -> Boolean,
    ): DglabState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val snapshot = ctl.state.value
            if (predicate(snapshot)) return snapshot
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("等待状态超时（${timeoutMs}ms），最后状态=$snapshot")
            }
            Thread.sleep(20)
        }
    }
}
