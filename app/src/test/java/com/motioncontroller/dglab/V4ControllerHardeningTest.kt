package com.motioncontroller.dglab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * [V4Controller] 审计修复的回归测试（直接 new controller，便于观察 pendingRequests 内部状态）：
 *  - pendingRequests 按 (clientId, reqId) 隔离；
 *  - 未决请求 TTL 到期在 tick 中清理并记录"未响应"；
 *  - 写队列积压时 device.op 被丢弃并计数（不再阻塞 tick/UI）。
 */
class V4ControllerHardeningTest {

    private var controller: V4Controller? = null

    @After
    fun tearDown() {
        controller?.stop()
        controller = null
    }

    @Test
    fun pendingRequestsExpireOnTickAndAreLogged() {
        val ctl = V4Controller(
            tickMs = 25,
            opIntervalMs = 1_000,
            pendingTtlMs = 200,
        ).also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }

        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            app.awaitFrame { it.str("type") == "controller_attached" }
            app.awaitFrame { frame ->
                frame.messageData()?.let { it.str("t") == "req" && it.str("m") == "devices.get" } == true
            }
            // 故意不回复 devices.get → pending 保留，等 TTL 清理
            awaitCondition("pending 应登记 devices.get") { ctl.pendingRequestCount() == 1 }
            awaitCondition("pending TTL 到期后应清空", timeoutMs = 3_000) { ctl.pendingRequestCount() == 0 }
            assertTrue(
                "未响应请求必须打日志: ${ctl.logLines.value.takeLast(5)}",
                ctl.logLines.value.any { "未响应" in it && "devices.get" in it },
            )
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun deviceOpsAreDroppedWhenWriterBacklogForced() {
        // dropThreshold=0 强制所有周期性 device.op 走丢弃路径，验证积压保护不会阻塞 ticker
        val ctl = V4Controller(
            tickMs = 20,
            opIntervalMs = 20,
            writerCapacity = 1,
            writerDropThreshold = 0,
        ).also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }

        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            app.awaitFrame { it.str("type") == "controller_attached" }
            app.awaitFrame { frame ->
                frame.messageData()?.let { it.str("t") == "req" && it.str("m") == "devices.get" } == true
            }
            app.sendEvent("devices.snapshot", snapshotEvent())

            awaitState(ctl) { it.activeSlotId == "slot-1" }
            ctl.setTargetIntensity(0, 50)
            Thread.sleep(400)

            val ops = app.drainFrames().mapNotNull { it.messageData() }
                .filter { it.str("m") == "device.op" }
            assertTrue("积压保护下 device.op 不应真的发出: ${ops.map { it.str("reqId") }}", ops.isEmpty())
            assertTrue(
                "丢弃必须计数并打日志: ${ctl.logLines.value.takeLast(6)}",
                ctl.logLines.value.any { "丢弃" in it },
            )
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun pendingRequestsAreKeyedPerClientSoResponsesOnlyResolveTheirOwner() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000).also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val url = "ws://127.0.0.1:${started.port}/?tid=${started.targetId}"

        val appA = FakeDglabApp(URI(url))
        val appB = FakeDglabApp(URI(url))
        try {
            assertTrue(appA.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val helloA = appA.awaitFrame { it.str("type") == "hello" }
            val clientA = helloA.str("clientId")!!
            appA.awaitFrame { it.str("type") == "controller_attached" }
            val getA = appA.awaitFrame { frame ->
                frame.messageData()?.let { it.str("t") == "req" && it.str("m") == "devices.get" } == true
            }
            val reqA = getA.messageData()!!.str("reqId")!!

            assertTrue(appB.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val helloB = appB.awaitFrame { it.str("type") == "hello" }
            val clientB = helloB.str("clientId")!!
            appB.awaitFrame { it.str("type") == "controller_attached" }
            val getB = appB.awaitFrame { frame ->
                frame.messageData()?.let { it.str("t") == "req" && it.str("m") == "devices.get" } == true
            }
            val reqB = getB.messageData()!!.str("reqId")!!
            assertFalse("两个 APP 的 reqId 不应相同", reqA == reqB)

            awaitCondition("两个 APP 各登记一条 pending") { ctl.pendingRequestCount() == 2 }
            val entries = ctl.pendingRequestEntries().toSet()
            assertTrue("pending 应按 (clientId, reqId) 登记: $entries", entries.contains(clientA to reqA))
            assertTrue("pending 应按 (clientId, reqId) 登记: $entries", entries.contains(clientB to reqB))

            // 只回复 A：B 的 pending 必须保留（1:N 隔离）
            appA.replyOk(reqA)
            awaitCondition("A 的响应只结算 A 的 pending") { ctl.pendingRequestCount() == 1 }
            assertEquals(listOf(clientB to reqB), ctl.pendingRequestEntries())

            appB.replyOk(reqB)
            awaitCondition("B 的响应结算后清空") { ctl.pendingRequestCount() == 0 }
        } finally {
            appA.closeQuietly()
            appB.closeQuietly()
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun snapshotEvent(): JsonObject = buildJsonObject {
        put("devices", buildJsonArray {
            add(buildJsonObject {
                put("slotId", "slot-1")
                put("name", "郊狼 3.0")
                put("type", "COYOTE_030")
                put("props", buildJsonObject {
                    put("connectState", "connected")
                    put("intensityA", 0)
                    put("intensityB", 0)
                })
                put("slotState", buildJsonObject { put("hasDevice", true) })
            })
        })
    }

    private fun awaitState(
        ctl: V4Controller,
        timeoutMs: Long = 5_000,
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

    private fun awaitCondition(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("等待「$message」超时（${timeoutMs}ms）")
    }
}
