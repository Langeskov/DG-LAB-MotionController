package com.motioncontroller.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.BindException

/**
 * [BindRetry]（绑定失败重试策略，问题4）的单测：
 * 可注入失败与 sleeper，验证退避序列、重试上限、非绑定失败不重试、异常归类。
 */
class BindRetryTest {

    @Test
    fun retryableBindFailuresBackOffAndThenSucceed() {
        val delays = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val retry = BindRetry(sleeper = { delays.add(it) })
        var attempts = 0

        val result = retry.run(log = { logs.add(it) }) { index ->
            assertEquals("attempt 序号从 0 起递增", attempts, index)
            attempts++
            if (attempts < 3) throw BindException("Address already in use")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals("2 次注入失败 + 1 次成功", 3, attempts)
        assertEquals("退避序列应为 250/500ms", listOf(250L, 500L), delays)
        assertTrue("每次重试都应打日志: $logs", logs.size == 2 && logs.all { "重试" in it })
    }

    @Test
    fun retriesAreCappedAtThreeAndLastFailureIsRethrown() {
        val delays = mutableListOf<Long>()
        val retry = BindRetry(sleeper = { delays.add(it) })
        var attempts = 0

        val thrown = runCatching {
            retry.run<String>(log = {}) {
                attempts++
                throw BindException("Address already in use")
            }
        }.exceptionOrNull()

        assertNotNull("重试用尽应抛出最后一次失败", thrown)
        assertTrue("应原样抛出绑定异常: $thrown", thrown is BindException)
        assertEquals("1 次原始 + 3 次重试", 4, attempts)
        assertEquals("退避 250/500/500ms（指数封顶）", listOf(250L, 500L, 500L), delays)
    }

    @Test
    fun nonRetryableFailuresAreNotRetried() {
        val delays = mutableListOf<Long>()
        val retry = BindRetry(sleeper = { delays.add(it) })
        var attempts = 0

        val thrown = runCatching {
            retry.run<String>(log = {}) {
                attempts++
                throw IllegalArgumentException("bad host")
            }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
        assertEquals("非绑定失败不允许重试", 1, attempts)
        assertTrue("非绑定失败不应退避: $delays", delays.isEmpty())
    }

    @Test
    fun customBackoffScheduleAndRetryCountAreHonoured() {
        val delays = mutableListOf<Long>()
        val retry = BindRetry(maxRetries = 2, backoffMs = listOf(10L, 20L), sleeper = { delays.add(it) })
        var attempts = 0

        runCatching {
            retry.run<String>(log = {}) {
                attempts++
                throw BindException("Address already in use")
            }
        }

        assertEquals("1 次原始 + 2 次重试", 3, attempts)
        assertEquals(listOf(10L, 20L), delays)
    }

    @Test
    fun addressInUseIsDetectedAcrossWrappersAndMessages() {
        assertTrue(BindRetry.isRetryableBindFailure(BindException("Address already in use")))
        assertTrue(BindRetry.isRetryableBindFailure(IOException("bind: Address already in use")))
        assertTrue(
            "被包装的 BindException 也要能识别（V4RelayServer 会包一层 IllegalStateException）",
            BindRetry.isRetryableBindFailure(
                IllegalStateException("V4 中继绑定 127.0.0.1:9998 失败: null", BindException("EADDRINUSE")),
            ),
        )
        assertTrue(BindRetry.isRetryableBindFailure(IOException("Failed to bind: EADDRINUSE")))
        assertFalse(BindRetry.isRetryableBindFailure(IllegalStateException("boom")))
        assertFalse(BindRetry.isRetryableBindFailure(NullPointerException()))
    }
}
