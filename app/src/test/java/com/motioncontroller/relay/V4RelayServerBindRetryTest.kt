package com.motioncontroller.relay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException

/**
 * [V4RelayServer.start] 绑定失败重试回归（问题4：引擎快速重启时端口未释放 → 「无法连接」）：
 *  - 端口占用类失败（BindException / "Address already in use"）自动退避重试，之后正常服务；
 *  - 重试用尽才抛错，且不残留运行中的服务端 / controllerId；
 *  - 非绑定类失败不重试。
 *
 * 绑定失败经 [V4RelayServer.bindAttemptHook] 注入：真实"端口被占"在 Windows（SO_REUSEADDR 可劫持）
 * 与 Linux 语义不同，不适合做确定性单测；退避时序经 [BindRetry] 的注入 sleeper 断言，不真睡。
 */
class V4RelayServerBindRetryTest {

    private var server: V4RelayServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun transientBindFailureIsRetriedUntilStartSucceeds() {
        val delays = mutableListOf<Long>()
        val relay = V4RelayServer(NoopListener()).also {
            it.bindRetry = BindRetry(sleeper = { ms -> delays.add(ms) })
            server = it
        }
        var attempts = 0
        relay.bindAttemptHook = { attempt ->
            attempts = attempt + 1
            if (attempt < 2) throw BindException("Address already in use")
        }

        val port = relay.start("127.0.0.1", 0)

        assertTrue("应返回实际端口", port > 0)
        assertTrue("重试成功后 relay 必须运行", relay.isRunning)
        assertEquals("应尝试 3 次（2 次注入失败 + 1 次成功）", 3, attempts)
        assertEquals("退避序列应为 250/500ms", listOf(250L, 500L), delays)
        assertTrue("targetId 应为 8 位 hex", relay.controllerId.matches(Regex("[0-9a-f]{8}")))
    }

    @Test
    fun bindFailureGivesUpAfterThreeRetriesAndLeavesNoResidue() {
        val delays = mutableListOf<Long>()
        val relay = V4RelayServer(NoopListener()).also {
            it.bindRetry = BindRetry(sleeper = { ms -> delays.add(ms) })
            server = it
        }
        var attempts = 0
        relay.bindAttemptHook = { attempt ->
            attempts = attempt + 1
            throw BindException("Address already in use")
        }

        val thrown = runCatching { relay.start("127.0.0.1", 0) }.exceptionOrNull()

        assertNotNull("重试用尽应抛出绑定失败", thrown)
        assertTrue("应包装为 IllegalStateException: $thrown", thrown is IllegalStateException)
        assertTrue(
            "异常应可归类为端口占用（供上层日志/诊断）: $thrown",
            BindRetry.isRetryableBindFailure(thrown!!),
        )
        assertEquals("1 次原始 + 3 次重试", 4, attempts)
        assertEquals("退避 250/500/500ms（指数封顶）", listOf(250L, 500L, 500L), delays)
        assertFalse("失败后 relay 不得处于运行态", relay.isRunning)
        assertEquals("失败后不应残留 controllerId", "", relay.controllerId)
    }

    @Test
    fun nonBindFailureIsNotRetried() {
        val delays = mutableListOf<Long>()
        val relay = V4RelayServer(NoopListener()).also {
            it.bindRetry = BindRetry(sleeper = { ms -> delays.add(ms) })
            server = it
        }
        var attempts = 0
        relay.bindAttemptHook = { attempt ->
            attempts = attempt + 1
            throw IllegalArgumentException("bad host")
        }

        val thrown = runCatching { relay.start("127.0.0.1", 0) }.exceptionOrNull()

        assertTrue("非绑定失败应原样上抛（包装为 IllegalStateException）: $thrown", thrown is IllegalStateException)
        assertEquals("非绑定失败不允许重试", 1, attempts)
        assertTrue("非绑定失败不应退避: $delays", delays.isEmpty())
    }

    @Test
    fun successfulStartAfterRetriesStillServesConnections() {
        val relay = V4RelayServer(NoopListener()).also { server = it }
        relay.bindRetry = BindRetry(sleeper = { ms -> Thread.sleep(ms) })
        var attempts = 0
        relay.bindAttemptHook = { attempt ->
            attempts = attempt + 1
            if (attempt == 0) throw BindException("Address already in use")
        }

        val port = relay.start("127.0.0.1", 0)

        assertEquals(2, attempts)
        // 绑定重试不影响服务：真实 socket 能立刻连上（走完整 WS 握手）
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val request = "GET /?tid=${relay.controllerId} HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val buf = ByteArray(4096)
            val read = socket.getInputStream().read(buf)
            val response = String(buf, 0, read)
            assertTrue("重试后应正常完成握手: ${response.take(120)}", response.contains("101"))
        }
    }

    private class NoopListener : V4RelayListener {}
}
