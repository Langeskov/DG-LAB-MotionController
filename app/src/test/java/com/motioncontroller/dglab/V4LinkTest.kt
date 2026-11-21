package com.motioncontroller.dglab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 模拟 DG-LAB APP ↔ V4 中继 的端到端 JVM 单测（127.0.0.1 + 随机端口，无 Android 依赖）。
 *
 * 覆盖：hello / controller_attached / devices.get / devices.snapshot / 强度闭环（t=3 累计）/
 * slots.patch 上报校正 / 急停（t=7 + device.op.clear 锁存）/ 错误 tid 4001 / 无 tid 4010 / idle。
 */
class V4LinkTest {

    private var link: V4Link? = null

    @After
    fun tearDown() {
        link?.stop()
        link = null
    }

    @Test
    fun fullControlFlowFromAttachToEmergencyStop() {
        val link = V4Link().also { this.link = it }
        link.start("127.0.0.1", 0)

        val started = awaitState(link) { it.relayRunning && it.targetId.isNotBlank() }
        val port = started.port
        val targetId = started.targetId
        assertTrue("targetId 应为 8 位 hex: $targetId", targetId.matches(Regex("[0-9a-f]{8}")))

        val url = link.clientUrl()
        assertTrue("clientUrl 应包含 ?tid=<targetId>: $url", url.contains("?tid=$targetId"))
        assertTrue("clientUrl 应以 ws://127.0.0.1:<port>/ 开头: $url", url.startsWith("ws://127.0.0.1:$port/"))
        assertEquals(
            "hostOverride 应优先于绑定地址",
            "ws://192.168.1.5:$port/?tid=$targetId",
            link.clientUrl("192.168.1.5"),
        )

        val app = FakeDglabApp(URI(url))
        try {
            assertTrue("假 APP 连接中继失败", app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))

            // 1) hello + controller_attached
            val hello = app.awaitFrame { it.str("type") == "hello" }
            val appClientId = hello.str("clientId")
            assertNotNull("hello 缺少 clientId", appClientId)
            assertTrue("APP clientId 应为 8 位 hex: $appClientId", appClientId!!.matches(Regex("[0-9a-f]{8}")))

            val attached = app.awaitFrame { it.str("type") == "controller_attached" }
            assertEquals(targetId, attached.str("clientId"))

            // 2) 控制器接入后立刻 devices.get（reqId "1"）
            val devicesGet = app.awaitFrame { frame ->
                frame.messageData()?.let { it.str("t") == "req" && it.str("m") == "devices.get" } == true
            }
            val getData = devicesGet.messageData()!!
            // 真机教训（AGENTS 14.3）：服务端→APP 的 message 不能带外层 clientId；
            // devices.get 这种无参 RPC 不能带 "data": null（参考实现直接省略该字段）。
            assertNull("服务端→APP 的 message 不应带 clientId", devicesGet["clientId"])
            assertFalse("无参 RPC devices.get 不应带 data 字段", getData.containsKey("data"))
            assertEquals("1", getData.str("reqId"))
            app.replyOk(getData.str("reqId"))

            app.sendEvent("devices.snapshot", buildJsonObject {
                put("devices", buildJsonArray {
                    add(device(slotId = "slot-1", intensityA = 0, intensityB = 0))
                })
            })

            // 3) 设备就绪 + activeSlotId
            val ready = awaitState(link) { it.deviceReady }
            assertEquals("slot-1", ready.activeSlotId)
            assertEquals(1, ready.devices.size)
            assertEquals("COYOTE_030", ready.devices[0].type)
            assertTrue(ready.appConnected)
            assertEquals(0, ready.reportedIntensityA)

            // 4) 强度闭环：目标 30 → 若干 t=3，累计 v == 30（每条 |v| ≤ 10），并逐条回 resp
            link.setTargetIntensity(0, 30)
            var sum = 0
            var opCount = 0
            val opsDeadline = System.currentTimeMillis() + FakeDglabApp.TIMEOUT_MS
            while (sum < 30 && System.currentTimeMillis() < opsDeadline) {
                val frame = app.pollFrame(200) ?: continue
                val data = frame.messageData() ?: continue
                if (data.str("t") != "req" || data.str("m") != "device.op") continue
                val op = data.obj("data") ?: continue
                if (op.int("t") != 3 || op.int("c") != 0) continue
                val delta = op.int("v") ?: 0
                assertTrue("单次 AddIntensity 应满足 |v| ≤ 10，实际 $delta", abs(delta) <= 10)
                sum += delta
                opCount++
                app.replyOk(data.str("reqId"))
            }
            assertEquals("累计 AddIntensity 应达到目标 30", 30, sum)
            assertTrue("30 强度应至少分 3 次下发，实际 $opCount", opCount >= 3)
            assertEquals(30, link.state.value.targetIntensityA)

            // 5) slots.patch 上报真实值 → reportedIntensityA
            app.sendEvent("slots.patch", buildJsonObject {
                put("slots", buildJsonArray {
                    add(buildJsonObject {
                        put("slotId", "slot-1")
                        put("props", buildJsonObject { put("intensityA", 30) })
                    })
                })
            })
            val reported = awaitState(link) { it.reportedIntensityA == 30 }
            assertEquals(30, reported.reportedIntensityA)

            // 6) 急停：双通道 t=7 v=0 + device.op.clear，且锁存
            link.emergencyStop()
            val saw = mutableSetOf<String>()
            val estopDeadline = System.currentTimeMillis() + FakeDglabApp.TIMEOUT_MS
            while (saw.size < 3 && System.currentTimeMillis() < estopDeadline) {
                val frame = app.pollFrame(200) ?: continue
                val data = frame.messageData() ?: continue
                if (data.str("t") != "req") continue
                when (data.str("m")) {
                    "device.op" -> {
                        val op = data.obj("data") ?: continue
                        if (op.int("t") == 7 && op.int("v") == 0) saw.add("zero-${op.int("c")}")
                    }
                    "device.op.clear" -> saw.add("clear")
                }
            }
            assertTrue("急停应发出 A/B 归零 + clear，实际收到 $saw", saw.containsAll(setOf("zero-0", "zero-1", "clear")))

            assertTrue(awaitState(link) { it.emergencyStopped }.emergencyStopped)
            assertFalse("急停后 controlAvailable 必须为 false", link.controlAvailable())

            // 锁存期间绝不能再有 t=3
            link.setTargetIntensity(0, 50)
            link.setTargetIntensity(1, 50)
            val after = mutableListOf<JsonObject>()
            val drainUntil = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < drainUntil) {
                app.pollFrame(100)?.let { after.add(it) }
            }
            assertTrue(
                "急停锁存后仍下发了 AddIntensity: $after",
                after.none { frame ->
                    val data = frame.messageData()
                    data != null && data.str("m") == "device.op" && data.obj("data")?.int("t") == 3
                },
            )

            link.clearEmergencyStop()
            assertFalse(awaitState(link) { !it.emergencyStopped }.emergencyStopped)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun unknownTargetIdIsRejectedWithCloseCode4001() {
        val link = V4Link().also { this.link = it }
        link.start("127.0.0.1", 0)
        val started = awaitState(link) { it.relayRunning && it.port > 0 }

        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=deadbeef"))
        try {
            app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val error = app.awaitFrame { it.str("type") == "error" }
            assertEquals("controller_not_found", error.str("code"))
            assertTrue("错误 tid 的连接应被关闭", app.awaitClosed(FakeDglabApp.TIMEOUT_MS))
            assertEquals(4001, app.closeCode)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun externalControllerWithoutTidIsRejected() {
        val link = V4Link().also { this.link = it }
        link.start("127.0.0.1", 0)
        val started = awaitState(link) { it.relayRunning && it.port > 0 }

        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/"))
        try {
            app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val error = app.awaitFrame { it.str("type") == "error" }
            assertEquals("controller_not_found", error.str("code"))
            assertTrue("无 tid 的控制端连接应被关闭", app.awaitClosed(FakeDglabApp.TIMEOUT_MS))
            assertEquals(4010, app.closeCode)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun pingGetsPong() {
        val link = V4Link().also { this.link = it }
        link.start("127.0.0.1", 0)
        val started = awaitState(link) { it.relayRunning && it.port > 0 }

        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            app.send("""{"type":"ping"}""")
            val pong = app.awaitFrame { it.str("type") == "pong" }
            assertNotNull("pong 应带 ts", pong["ts"])
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun appDisconnectZeroesController() {
        val link = V4Link().also { this.link = it }
        link.start("127.0.0.1", 0)
        val started = awaitState(link) { it.relayRunning && it.port > 0 }

        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            app.awaitFrame { it.str("type") == "controller_attached" }
            app.sendEvent("devices.snapshot", buildJsonObject {
                put("devices", buildJsonArray {
                    add(device(slotId = "slot-1", intensityA = 30, intensityB = 10))
                })
            })
            val ready = awaitState(link) { it.deviceReady && it.reportedIntensityA == 30 }
            assertEquals(30, ready.reportedIntensityA)
            assertEquals(10, ready.reportedIntensityB)

            app.closeQuietly()

            // APP 断开 → 控制器归零、设备清空、不再具备下发条件
            val disconnected = awaitState(link) { !it.appConnected }
            assertEquals(0, disconnected.targetIntensityA)
            assertEquals(0, disconnected.targetIntensityB)
            assertEquals(null, disconnected.reportedIntensityA)
            assertEquals(null, disconnected.reportedIntensityB)
            assertEquals(null, disconnected.activeSlotId)
            assertTrue(disconnected.devices.isEmpty())
            assertFalse(link.controlAvailable())
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun idleTimeoutIsLoggedWithoutStoppingRelay() {
        val link = V4Link(idleTimeoutMs = 300).also { this.link = it }
        link.start("127.0.0.1", 0)
        awaitState(link) { it.relayRunning }

        val deadline = System.currentTimeMillis() + FakeDglabApp.TIMEOUT_MS
        while ((!link.isRelayIdle || link.logLines.value.none { "idle_timeout" in it }) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertTrue("无 APP 接入超时后应标记 idle", link.isRelayIdle)
        assertTrue("单机形态不应主动停止中继", link.state.value.relayRunning)
        assertTrue(
            "idle 应有日志: ${link.logLines.value.takeLast(5)}",
            link.logLines.value.any { "idle_timeout" in it },
        )
    }

    @Test
    fun preferredTargetIdIsReusedAcrossRestart() {
        val link = V4Link().also { this.link = it }
        link.start("127.0.0.1", 0, "0a1b2c3d")
        val first = awaitState(link) { it.relayRunning && it.targetId.isNotBlank() }
        assertEquals("应复用传入的 targetId", "0a1b2c3d", first.targetId)

        link.stop()
        link.start("127.0.0.1", 0, "0a1b2c3d")
        val second = awaitState(link) { it.relayRunning && it.targetId.isNotBlank() }
        assertEquals("重启后 targetId 必须保持不变（APP 手输地址不失效）", "0a1b2c3d", second.targetId)
    }

    @Test
    fun invalidPreferredTargetIdFallsBackToRandomHex() {
        val link = V4Link().also { this.link = it }
        link.start("127.0.0.1", 0, "not-a-valid-id")
        val started = awaitState(link) { it.relayRunning && it.targetId.isNotBlank() }
        assertTrue("非法 preferred id 应回退为 8 位 hex: ${started.targetId}", started.targetId.matches(Regex("[0-9a-f]{8}")))
    }

    @Test
    fun handshakeResponseUsesPlainUpgradeConnectionHeader() {
        val link = V4Link().also { this.link = it }
        link.start("127.0.0.1", 0)
        val started = awaitState(link) { it.relayRunning && it.targetId.isNotBlank() }

        java.net.Socket("127.0.0.1", started.port).use { socket ->
            socket.soTimeout = 3_000
            val request = "GET /?tid=${started.targetId} HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${started.port}\r\n" +
                "Upgrade: websocket\r\n" +
                // DG-LAB APP(OkHttp 3.14.9) 真实发送的头；服务端绝不能原样回显到响应
                "Connection: Upgrade; close\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val buf = ByteArray(4096)
            val read = socket.getInputStream().read(buf)
            val response = String(buf, 0, read)
            assertTrue("应返回 101，实际: ${response.take(160)}", response.contains("101"))
            val connectionLine = response.lineSequence()
                .firstOrNull { it.startsWith("Connection:", ignoreCase = true) }
                ?.trim()
            assertEquals(
                "握手响应必须是 Connection: Upgrade（Java-WebSocket 默认会回显请求里的 'Upgrade; close'）",
                "Connection: Upgrade",
                connectionLine,
            )
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

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
}
