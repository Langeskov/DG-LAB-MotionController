package com.motioncontroller.dglab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 审计修复的端到端回归（真实 WebSocket，127.0.0.1 + 随机端口）：
 *  - 同 tid 重连且旧 socket 还"挂着"（半开）时，会话状态必须重置，device.op 不能再用旧 slotId；
 *  - 两个 APP 同时在线时，device.op 只发给 activeSlotId 的归属 APP；
 *  - 设备 disconnect 后 controlAvailable 必须为 false；
 *  - 成功响应后 lastError 必须清空。
 */
class V4LinkHardeningTest {

    private var link: V4Link? = null

    @After
    fun tearDown() {
        link?.stop()
        link = null
    }

    @Test
    fun reconnectWithLingeringOldSocketResetsSessionAndRoutesOpsToNewApp() {
        val link = V4Link(tickMs = 25, opIntervalMs = 25).also { this.link = it }
        link.start("127.0.0.1", 0)
        val started = awaitState(link) { it.relayRunning && it.targetId.isNotBlank() }
        val url = "ws://127.0.0.1:${started.port}/?tid=${started.targetId}"

        val appA = FakeDglabApp(URI(url))
        val appB = FakeDglabApp(URI(url))
        try {
            // ---- 旧 APP 正常工作 ----
            assertTrue("APP A 连接失败", appA.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            appA.awaitFrame { it.str("type") == "controller_attached" }
            replyDevicesGet(appA)
            appA.sendEvent("devices.snapshot", snapshotEvent(device("slot-a", 0, 0)))
            awaitState(link) { it.activeSlotId == "slot-a" && it.reportedIntensityA == 0 }

            link.setTargetIntensity(0, 10)
            assertEquals("旧 APP 应能收到完整闭环指令", 10, collectOps(appA, "slot-a", 10))

            // ---- 旧 socket 仍然开着（linger），新 APP 连同一个 tid ----
            assertTrue("APP B 连接失败", appB.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            appB.awaitFrame { it.str("type") == "controller_attached" }
            appB.awaitFrame { frame ->
                frame.messageData()?.let { it.str("t") == "req" && it.str("m") == "devices.get" } == true
            }

            // 新会话必须是干净状态：旧 registry/activeSlotId/estimate 都不能残留
            val reset = awaitState(link) {
                it.appClientIds.size == 2 && it.devices.isEmpty() &&
                    it.activeSlotId == null && it.reportedIntensityA == null
            }
            assertEquals("重连后目标强度必须归零", 0, reset.targetIntensityA)
            assertEquals(0, reset.targetIntensityB)

            // ---- 新 APP 上报自己的设备，下发必须只带新 slotId ----
            appB.sendEvent("devices.snapshot", snapshotEvent(device("slot-b", 0, 0)))
            awaitState(link) { it.activeSlotId == "slot-b" }

            link.setTargetIntensity(0, 10)
            assertEquals("新 APP 应能收到完整闭环指令", 10, collectOps(appB, "slot-b", 10))

            // 旧连接不得再收到 device.op（旧 slotId 已作废）
            Thread.sleep(300)
            val staleOps = appA.drainFrames().count { frame ->
                frame.messageData()?.let { it.str("m") == "device.op" } == true
            }
            assertEquals("旧 APP 不应再收到 device.op", 0, staleOps)
        } finally {
            appA.closeQuietly()
            appB.closeQuietly()
        }
    }

    @Test
    fun operationsAreRoutedOnlyToTheAppOwningTheActiveSlot() {
        val link = V4Link(tickMs = 25, opIntervalMs = 25).also { this.link = it }
        link.start("127.0.0.1", 0)
        val started = awaitState(link) { it.relayRunning && it.targetId.isNotBlank() }
        val url = "ws://127.0.0.1:${started.port}/?tid=${started.targetId}"

        val appA = FakeDglabApp(URI(url))
        val appB = FakeDglabApp(URI(url))
        try {
            assertTrue(appA.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            appA.awaitFrame { it.str("type") == "controller_attached" }
            replyDevicesGet(appA)

            assertTrue(appB.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            appB.awaitFrame { it.str("type") == "controller_attached" }
            replyDevicesGet(appB)

            // B 是 activeSlot 的归属者
            appB.sendEvent("devices.snapshot", snapshotEvent(device("slot-b", 0, 0)))
            awaitState(link) { it.devices.size == 1 && it.activeSlotId == "slot-b" }

            // A 用 patch 增加自己的设备（snapshot 是整体替换语义，这里不能用 snapshot）
            appA.sendEvent("devices.patch", buildJsonObject {
                put("added", buildJsonArray { add(device("slot-a", 0, 0)) })
            })
            val both = awaitState(link) { it.devices.size == 2 }
            assertEquals("activeSlot 应保持 B 的设备", "slot-b", both.activeSlotId)

            link.setTargetIntensity(0, 10)
            assertEquals("device.op 必须只发给 slot-b 的归属 APP", 10, collectOps(appB, "slot-b", 10))

            Thread.sleep(300)
            val leakedOps = appA.drainFrames().count { frame ->
                frame.messageData()?.let { it.str("m") == "device.op" } == true
            }
            assertEquals("APP A 不应收到 slot-b 的 device.op", 0, leakedOps)
        } finally {
            appA.closeQuietly()
            appB.closeQuietly()
        }
    }

    @Test
    fun disconnectedDeviceIsNotControlAvailable() {
        val link = V4Link(tickMs = 25, opIntervalMs = 25).also { this.link = it }
        link.start("127.0.0.1", 0)
        val started = awaitState(link) { it.relayRunning && it.targetId.isNotBlank() }

        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            app.awaitFrame { it.str("type") == "controller_attached" }
            replyDevicesGet(app)
            app.sendEvent("devices.snapshot", snapshotEvent(device("slot-1", 0, 0)))
            awaitState(link) { it.activeSlotId == "slot-1" }
            awaitCondition("连接中的设备应可控制") { link.controlAvailable() }

            // 设备断开：即使还在 devices 列表里，也不能再算"可控制"
            app.sendEvent("slots.patch", buildJsonObject {
                put("slots", buildJsonArray {
                    add(buildJsonObject {
                        put("slotId", "slot-1")
                        put("props", buildJsonObject {
                            put("connectState", "disconnected")
                            put("intensityA", 0)
                        })
                    })
                })
            })
            val disconnected = awaitState(link) { it.activeSlotId == null }
            assertFalse("设备断开后 deviceReady 必须为 false", disconnected.deviceReady)
            assertFalse("设备断开后 controlAvailable 必须为 false", link.controlAvailable())
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun successfulResponseClearsLastError() {
        val link = V4Link(tickMs = 25, opIntervalMs = 25).also { this.link = it }
        link.start("127.0.0.1", 0)
        val started = awaitState(link) { it.relayRunning && it.targetId.isNotBlank() }

        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
            app.awaitFrame { it.str("type") == "controller_attached" }
            replyDevicesGet(app)
            app.sendEvent("devices.snapshot", snapshotEvent(device("slot-1", 0, 0)))
            awaitState(link) { it.activeSlotId == "slot-1" }

            link.setTargetIntensity(0, 20)
            val firstOp = app.awaitFrame { frame ->
                frame.messageData()?.let { it.str("m") == "device.op" } == true
            }
            app.sendAppData(buildJsonObject {
                put("t", "resp")
                put("reqId", firstOp.messageData()!!.str("reqId"))
                put("error", buildJsonObject { put("code", "boom") })
            })
            val errored = awaitState(link) { it.lastError != null }
            assertTrue("错误应记录: ${errored.lastError}", errored.lastError!!.contains("boom"))

            val nextOp = app.awaitFrame { frame ->
                frame.messageData()?.let { it.str("m") == "device.op" } == true
            }
            app.replyOk(nextOp.messageData()!!.str("reqId"))

            val cleared = awaitState(link) { it.lastError == null }
            assertNull(cleared.lastError)
        } finally {
            app.closeQuietly()
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun replyDevicesGet(app: FakeDglabApp) {
        val frame = app.awaitFrame { candidate ->
            candidate.messageData()?.let { it.str("t") == "req" && it.str("m") == "devices.get" } == true
        }
        app.replyOk(frame.messageData()!!.str("reqId"))
    }

    /** 收下发帧，累计 [expected] 强度后返回；顺带回 resp 维持闭环。 */
    private fun collectOps(app: FakeDglabApp, expectedSlot: String, expected: Int): Int {
        var sum = 0
        val deadline = System.currentTimeMillis() + FakeDglabApp.TIMEOUT_MS
        while (sum != expected && System.currentTimeMillis() < deadline) {
            val frame = app.pollFrame(200) ?: continue
            val data = frame.messageData() ?: continue
            if (data.str("m") != "device.op") continue
            val op = data.obj("data") ?: continue
            if (op.int("t") != 3) continue
            assertEquals("device.op 必须带 activeSlot 的 slotId", expectedSlot, op.str("s"))
            sum += op.int("v") ?: 0
            app.replyOk(data.str("reqId"))
        }
        return sum
    }

    private fun device(slotId: String, intensityA: Int, intensityB: Int): JsonObject = buildJsonObject {
        put("slotId", slotId)
        put("name", "郊狼 3.0")
        put("type", "COYOTE_030")
        put("props", buildJsonObject {
            put("connectState", "connected")
            put("intensityA", intensityA)
            put("intensityB", intensityB)
        })
        put("slotState", buildJsonObject { put("hasDevice", true) })
    }

    private fun snapshotEvent(device: JsonObject): JsonObject = buildJsonObject {
        put("devices", buildJsonArray { add(device) })
    }

    private fun awaitState(
        link: V4Link,
        timeoutMs: Long = FakeDglabApp.TIMEOUT_MS,
        predicate: (DglabState) -> Boolean,
    ): DglabState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val snapshot = link.state.value
            if (predicate(snapshot)) return snapshot
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("等待状态超时（${timeoutMs}ms），最后状态=$snapshot")
            }
            Thread.sleep(20)
        }
    }

    private fun awaitCondition(message: String, timeoutMs: Long = FakeDglabApp.TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("等待「$message」超时（${timeoutMs}ms）")
    }
}
