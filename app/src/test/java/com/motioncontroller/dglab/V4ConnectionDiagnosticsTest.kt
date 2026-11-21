package com.motioncontroller.dglab

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 连接诊断字段回归（问题4：偶发「DG-LAB APP 无法连接」排查）：
 *  - [DglabState.handshakeCount] / [DglabState.lastHandshakeSummary]：WS 握手完成即计数并留一行
 *    UA + Connection 摘要（AGENTS 3.2 的 Connection 头陷阱就靠它定位）；
 *  - [DglabState.lastRejectReason]：被拒连接（无 tid / tid 不匹配）原因；
 *  - [DglabState.lastCloseCode] / [DglabState.lastCloseReason]：最近一次 APP 断开；
 *    被拒连接不算 APP 断开；
 *  - relay 重启（start）清空诊断。
 */
class V4ConnectionDiagnosticsTest {

    private var ctl: V4Controller? = null

    @After
    fun tearDown() {
        ctl?.stop()
        ctl = null
    }

    @Test
    fun handshakeCountAndSummaryRecordUaAndConnectionHeader() {
        val ctl = started()
        // 裸 socket 复刻 DG-LAB APP（OkHttp 3.14.9）的真实握手头
        Socket("127.0.0.1", ctl.state.value.port).use { socket ->
            socket.soTimeout = 3_000
            val request = "GET /?tid=${ctl.state.value.targetId} HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${ctl.state.value.port}\r\n" +
                "Upgrade: websocket\r\n" +
                "User-Agent: okhttp/3.14.9\r\n" +
                "Connection: Upgrade; close\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val buf = ByteArray(4096)
            val read = socket.getInputStream().read(buf)
            assertTrue("握手应返回 101", String(buf, 0, read).contains("101"))

            val st = awaitState(ctl) { it.handshakeCount >= 1 }
            assertEquals(1, st.handshakeCount)
            val summary = st.lastHandshakeSummary
            assertNotNull("握手摘要不能为空", summary)
            assertTrue("摘要应含 UA: $summary", summary!!.contains("UA=okhttp/3.14.9"))
            assertTrue("摘要应含 Connection 头: $summary", summary.contains("Connection=Upgrade; close"))
        }
    }

    @Test
    fun rejectReasonRecordsTidMismatchButIsNotAnAppClose() {
        val ctl = started()
        val app = FakeDglabApp(URI("ws://127.0.0.1:${ctl.state.value.port}/?tid=deadbeef"))
        try {
            assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue("应被服务端关闭", app.awaitClosed())
            assertEquals("tid 不匹配应以 4001 关闭", 4001, app.closeCode)

            val st = awaitState(ctl) { it.lastRejectReason != null }
            assertTrue("拒绝原因应记录 tid: ${st.lastRejectReason}", st.lastRejectReason!!.contains("tid=deadbeef"))
            assertTrue("拒绝原因应说明不匹配: ${st.lastRejectReason}", st.lastRejectReason!!.contains("不匹配"))
            assertEquals("被拒连接的握手仍算握手成功", 1, st.handshakeCount)
            assertNull("被拒连接不算 APP 断开，不应记录关闭码", st.lastCloseCode)
            assertNull("被拒连接不算 APP 断开，不应记录关闭原因", st.lastCloseReason)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun rejectReasonRecordsMissingTid() {
        val ctl = started()
        val app = FakeDglabApp(URI("ws://127.0.0.1:${ctl.state.value.port}/"))
        try {
            assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue("应被服务端关闭", app.awaitClosed())
            assertEquals("无 tid 的外部控制端应以 4010 关闭", 4010, app.closeCode)

            val st = awaitState(ctl) { it.lastRejectReason != null }
            assertTrue("拒绝原因应说明无 tid: ${st.lastRejectReason}", st.lastRejectReason!!.contains("无 tid"))
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun appCloseCodeAndReasonAreRecorded() {
        val ctl = started()
        val app = FakeDglabApp(URI("ws://127.0.0.1:${ctl.state.value.port}/?tid=${ctl.state.value.targetId}"))
        try {
            assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            app.awaitFrame { it.str("type") == "controller_attached" }

            app.close(3001, "test-bye")

            val st = awaitState(ctl) { it.lastCloseCode != null }
            assertEquals("最近一次 APP 断开的关闭码", 3001, st.lastCloseCode)
            assertEquals("最近一次 APP 断开的关闭原因", "test-bye", st.lastCloseReason)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun diagnosticsResetWhenRelayRestarts() {
        val ctl = started()
        val url = "ws://127.0.0.1:${ctl.state.value.port}/?tid=${ctl.state.value.targetId}"
        val app = FakeDglabApp(URI(url))
        try {
            assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            app.awaitFrame { it.str("type") == "controller_attached" }
            app.close(3001, "bye")
            awaitState(ctl) { it.lastCloseCode == 3001 && it.handshakeCount >= 1 }
        } finally {
            app.closeQuietly()
        }

        // 引擎快速重启（不显式 stop）：诊断按 relay 会话清零
        ctl.start("127.0.0.1", 0)
        val st = awaitState(ctl) { it.relayRunning }
        assertEquals("重启后握手计数清零", 0, st.handshakeCount)
        assertNull("重启后握手摘要清空", st.lastHandshakeSummary)
        assertNull("重启后拒绝原因清空", st.lastRejectReason)
        assertNull("重启后关闭码清空", st.lastCloseCode)
        assertNull("重启后关闭原因清空", st.lastCloseReason)
    }

    @Test
    fun stateDefaultsKeepDiagnosticsCompatible() {
        // 默认值兼容现有测试/调用方：不传就是 0/null
        val st = DglabState()
        assertEquals(0, st.handshakeCount)
        assertNull(st.lastHandshakeSummary)
        assertNull(st.lastRejectReason)
        assertNull(st.lastCloseCode)
        assertNull(st.lastCloseReason)
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun started(): V4Controller {
        val ctl = V4Controller().also { this.ctl = it }
        ctl.start("127.0.0.1", 0)
        awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        return ctl
    }

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
