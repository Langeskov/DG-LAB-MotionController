package com.motioncontroller.relay

import com.motioncontroller.dglab.FakeDglabApp
import com.motioncontroller.dglab.str
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [V4RelayServer] 审计修复的回归测试：
 *  - 握手帧（hello / controller_attached）必须先于任何控制器消息入队，APP 才不会把首帧当垃圾；
 *  - 坏 JSON / 未知类型 / 二进制帧要记录日志并计数，不能静默丢弃。
 *
 * 直接 new 中继 + 假 listener（不用 V4Link），可以精确观察"attach 回调里立刻发帧"的时序。
 */
class V4RelayServerHardeningTest {

    private var server: V4RelayServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun handshakeFramesAreQueuedBeforeAppIsPublished() {
        val listener = ProbeListener()
        val relay = V4RelayServer(
            listener = listener,
            idleTimeoutMs = 60_000,
            heartbeatIntervalMs = 0,
        )
        listener.server = relay
        server = relay
        relay.start("127.0.0.1", 0)

        val app = FakeDglabApp(URI("ws://127.0.0.1:${relay.port}/?tid=${relay.controllerId}"))
        try {
            assertTrue("假 APP 连接失败", app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))

            val first = app.frames.poll(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertNotNull("应收到 hello", first)
            assertEquals("第一帧必须是 hello（先入队再发布 APP）", "hello", first.str("type"))

            val second = app.frames.poll(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertNotNull("应收到 controller_attached", second)
            assertEquals("第二帧必须是 controller_attached", "controller_attached", second.str("type"))

            val third = app.frames.poll(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertNotNull("应收到 attach 回调里发出的探测帧", third)
            assertEquals("控制器消息不能越过握手帧", "message", third.str("type"))
            val data = third["data"] as? JsonObject
            assertEquals("probe", data?.str("m"))
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun malformedUnknownAndBinaryFramesAreLoggedAndCounted() {
        val listener = ProbeListener()
        val relay = V4RelayServer(
            listener = listener,
            idleTimeoutMs = 60_000,
            heartbeatIntervalMs = 0,
        )
        listener.server = relay
        server = relay
        relay.start("127.0.0.1", 0)

        val app = FakeDglabApp(URI("ws://127.0.0.1:${relay.port}/?tid=${relay.controllerId}"))
        try {
            assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            app.awaitFrame { it.str("type") == "controller_attached" }

            app.send("{ definitely-not-json")
            app.send("""{"type":"totally-unknown-type"}""")
            app.send(byteArrayOf(1, 2, 3, 4))

            val invalidJson = awaitLog(listener, "无效 JSON")
            assertTrue("坏 JSON 日志应带累计计数: $invalidJson", invalidJson.contains("累计"))

            val unknown = awaitLog(listener, "未知帧类型")
            assertTrue("未知类型日志应带累计计数: $unknown", unknown.contains("累计"))
            assertTrue("未知类型日志应包含类型名: $unknown", unknown.contains("totally-unknown-type"))

            val binary = awaitLog(listener, "二进制帧")
            assertTrue("二进制帧日志应带累计计数: $binary", binary.contains("累计"))
        } finally {
            app.closeQuietly()
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private class ProbeListener : V4RelayListener {
        @Volatile
        var server: V4RelayServer? = null

        val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun onAppAttached(clientId: String) {
            // 模拟控制器：一 attach 就发帧。旧实现此时还没入队 hello，探测帧会跑到握手帧前面。
            server?.sendToApp(
                clientId,
                jsonObject("t" to jsonOf("req"), "m" to jsonOf("probe")),
            )
        }

        override fun onRelayLog(line: String) {
            logs.add(line)
        }
    }

    private fun awaitLog(listener: ProbeListener, needle: String, timeoutMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(listener.logs) {
                listener.logs.firstOrNull { needle in it }?.let { return it }
            }
            Thread.sleep(20)
        }
        throw AssertionError("等待日志「$needle」超时，已有: ${listener.logs.takeLast(10)}")
    }
}
