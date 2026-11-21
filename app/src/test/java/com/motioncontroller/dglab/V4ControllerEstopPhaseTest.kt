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
 * 急停传递阶段的回归测试：
 * 控制器必须区分「已发出 / 帧已入队 / APP 已回 resp」，且不把 ACK 当成设备端确认。
 */
class V4ControllerEstopPhaseTest {

    private var controller: V4Controller? = null

    @After
    fun tearDown() {
        controller?.stop()
        controller = null
    }

    @Test
    fun estopTracksIssuedQueuedAndAppAck() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000).also { controller = it }
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

            // 急停前状态干净
            assertFalse(ctl.state.value.estopIssued)
            assertFalse(ctl.state.value.estopFramesQueued)
            assertFalse(ctl.state.value.estopAppAcked)

            ctl.emergencyStop()
            val issued = ctl.state.value
            assertTrue("急停入口被调用即应标记 issued", issued.estopIssued)
            assertTrue("链路可用时归零/clear 帧应入队", issued.estopFramesQueued)
            assertTrue("控制器应锁存", issued.emergencyStopped)
            assertFalse("尚未收到 APP resp 不能算 ACK", issued.estopAppAcked)

            // 收到的前三个 device.op / clear 帧按实现顺序：A 归零、B 归零、clear
            val opFrames = ArrayList<JsonObject>()
            while (opFrames.size < 3) {
                val frame = app.pollFrame(FakeDglabApp.TIMEOUT_MS) ?: break
                val data = frame.messageData() ?: continue
                if (data.str("m") == "device.op" || data.str("m") == "device.op.clear") {
                    opFrames.add(data)
                }
            }
            assertTrue("应收到 A/B 归零 + clear 三帧", opFrames.size == 3)
            assertTrue(opFrames.any { it.str("m") == "device.op.clear" })
            val setIntensityFrames = opFrames.filter { it.str("m") == "device.op" }
            assertTrue("两个通道的 SetIntensity(v=0)", setIntensityFrames.size == 2)

            // APP 回 resp（带 reason）→ 只代表 APP 已处理，不代表 BLE 设备确认
            // Phase 3 修正：A 归零 / B 归零 / clear 三条命令必须分别回执，单条 resp 不能算整条命令链确认。
            val aZero = opFrames.first { it.str("m") == "device.op" && it.obj("data")?.int("c") == 0 }
            val bZero = opFrames.first { it.str("m") == "device.op" && it.obj("data")?.int("c") == 1 }
            val clear = opFrames.first { it.str("m") == "device.op.clear" }

            app.reply(aZero.str("reqId"), buildJsonObject {})
            awaitState(ctl) { state ->
                state.estopCommandResults.find { it.kind == EstopCommandKind.A_ZERO }?.state ==
                    EstopCommandAckState.ACKED
            }
            assertFalse("只确认 A 归零不能算整条急停命令链已确认", ctl.state.value.estopAppAcked)

            app.replyReason(bZero.str("reqId"), "completed")
            awaitState(ctl) { state ->
                state.estopCommandResults.find { it.kind == EstopCommandKind.B_ZERO }?.state ==
                    EstopCommandAckState.ACKED
            }
            assertFalse("A/B 归零都确认、clear 尚未回执时不能算 ACK", ctl.state.value.estopAppAcked)

            app.replyReason(clear.str("reqId"), "cleared")
            awaitState(ctl) { it.estopAppAcked }
            assertTrue(ctl.state.value.estopAppAcked)
            assertTrue(ctl.state.value.emergencyStopped)
            assertEquals(3, ctl.state.value.estopCommandResults.size)
            assertTrue(ctl.state.value.estopCommandResults.all { it.state == EstopCommandAckState.ACKED })
            assertTrue(
                "回执摘要应逐条可查: ${ctl.state.value.estopAckReason}",
                ctl.state.value.estopAckReason?.contains("A_ZERO:ACKED") == true,
            )

            // 解除急停：锁存与 ACK 状态复位，但不会自动开始控制
            ctl.clearEmergencyStop()
            val cleared = ctl.state.value
            assertFalse(cleared.emergencyStopped)
            assertFalse(cleared.estopAppAcked)
            assertFalse(cleared.estopFramesQueued || cleared.estopIssued)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun estopWithoutLinkStillLatchesButCannotQueueFrames() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000).also { controller = it }
        ctl.start("127.0.0.1", 0)
        awaitState(ctl) { it.relayRunning }

        ctl.emergencyStop()
        val state = ctl.state.value
        assertTrue(state.emergencyStopped)
        assertTrue(state.estopIssued)
        assertFalse("没有 APP/设备时不可能入队归零帧", state.estopFramesQueued)
        assertFalse(state.estopAppAcked)
        assertTrue(
            "必须记录无法下发的风险: ${ctl.logLines.value.takeLast(5)}",
            ctl.logLines.value.any { "无法下发归零" in it || "没有可下发" in it },
        )
    }

    @Test
    fun repeatedEstopIsIdempotentAndNormalOutputStaysLocked() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000).also { controller = it }
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

            ctl.emergencyStop()
            awaitState(ctl) { it.estopFramesQueued }
            val first = ctl.state.value
            assertTrue(first.emergencyStopped)
            assertTrue(first.estopIssued)

            // 重复触发急停：必须幂等（保持锁存、仍可重新排队安全帧），不能抛异常/解除锁存
            ctl.emergencyStop()
            val second = ctl.state.value
            assertTrue(second.emergencyStopped)
            assertTrue(second.estopIssued)
            assertTrue(second.estopFramesQueued)
            assertFalse(second.estopAppAcked)

            // 锁存期间普通强度指令必须被忽略（UI 层锁定之外的后端防御）
            ctl.setTargetIntensity(0, 60)
            assertEquals(0, ctl.state.value.targetIntensityA)
            ctl.setTargetIntensity(1, 60)
            assertEquals(0, ctl.state.value.targetIntensityB)

            // 解除锁存后标志复位，目标保持 0（不自动开始控制）
            ctl.clearEmergencyStop()
            val cleared = ctl.state.value
            assertFalse(cleared.emergencyStopped)
            assertFalse(cleared.estopIssued)
            assertFalse(cleared.estopAppAcked)
            assertEquals(0, cleared.targetIntensityA)
            assertEquals(0, cleared.targetIntensityB)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun estopReplacedReasonAndErrorResponseAreNotAcks() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000).also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            ctl.emergencyStop()
            awaitState(ctl) { it.estopFramesQueued }
            val opFrames = collectEstopFrames(app)
            val aZero = opFrames.first { it.str("m") == "device.op" && it.obj("data")?.int("c") == 0 }
            val bZero = opFrames.first { it.str("m") == "device.op" && it.obj("data")?.int("c") == 1 }
            val clear = opFrames.first { it.str("m") == "device.op.clear" }

            // clear 回 reason=replaced：不是成功语义，必须记为 UNCONFIRMED
            app.replyReason(aZero.str("reqId"), "completed")
            app.replyReason(bZero.str("reqId"), "completed")
            app.replyReason(clear.str("reqId"), "replaced")
            awaitState(ctl) { state ->
                state.estopCommandResults.find { it.kind == EstopCommandKind.CLEAR }?.state ==
                    EstopCommandAckState.UNCONFIRMED
            }
            assertFalse("reason=replaced 不能冒充 clear 回执成功", ctl.state.value.estopAppAcked)
            assertTrue(
                "摘要必须暴露未确认原因: ${ctl.state.value.estopAckReason}",
                ctl.state.value.estopAckReason?.contains("NOT_ACKED") == true,
            )

            // 错误响应同样不能算确认（重新急停以得到新的待回执命令链）
            ctl.emergencyStop()
            awaitState(ctl) { it.estopFramesQueued }
            val retryFrames = collectEstopFrames(app)
            val retryA = retryFrames.first { it.str("m") == "device.op" && it.obj("data")?.int("c") == 0 }
            app.replyError(retryA.str("reqId"), "invalid_operate")
            awaitState(ctl) { state ->
                state.estopCommandResults.find { it.kind == EstopCommandKind.A_ZERO }?.state ==
                    EstopCommandAckState.UNCONFIRMED
            }
            assertFalse("error 响应不能冒充 A 归零成功", ctl.state.value.estopAppAcked)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun estopTimeoutMarksUnconfirmedAndNeverAcks() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000, pendingTtlMs = 250)
            .also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            ctl.emergencyStop()
            awaitState(ctl) { it.estopFramesQueued }
            collectEstopFrames(app)
            // 完全不回执：TTL 到期后三条命令都必须变成 UNCONFIRMED，且绝不能置 estopAppAcked
            awaitState(ctl, timeoutMs = 5_000) { state ->
                state.estopCommandResults.size == 3 &&
                    state.estopCommandResults.all { it.state == EstopCommandAckState.UNCONFIRMED }
            }
            assertFalse(ctl.state.value.estopAppAcked)
            assertTrue(
                "超时原因可查: ${ctl.state.value.estopAckReason}",
                ctl.state.value.estopAckReason?.contains("timeout") == true,
            )
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun estopAppDisconnectLeavesPendingCommandsUnconfirmed() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000).also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            ctl.emergencyStop()
            awaitState(ctl) { it.estopFramesQueued }
            val opFrames = collectEstopFrames(app)
            val aZero = opFrames.first { it.str("m") == "device.op" && it.obj("data")?.int("c") == 0 }
            app.reply(aZero.str("reqId"), buildJsonObject {})
            awaitState(ctl) { state ->
                state.estopCommandResults.find { it.kind == EstopCommandKind.A_ZERO }?.state ==
                    EstopCommandAckState.ACKED
            }

            // APP 在 B 归零 / clear 回执前断线：剩余命令必须变 UNCONFIRMED，锁存保持
            app.closeQuietly()
            awaitState(ctl) { it.appClientIds.isEmpty() }
            awaitState(ctl) { state ->
                state.estopCommandResults
                    .filter { it.kind != EstopCommandKind.A_ZERO }
                    .all { it.state == EstopCommandAckState.UNCONFIRMED }
            }
            assertFalse(ctl.state.value.estopAppAcked)
            assertTrue(ctl.state.value.emergencyStopped)
            assertEquals(0, ctl.state.value.targetIntensityA)
            assertEquals(0, ctl.state.value.targetIntensityB)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun repeatedEstopInvalidatesPreviousCommandChainResponses() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000).also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            ctl.emergencyStop()
            awaitState(ctl) { it.estopFramesQueued }
            val oldFrames = collectEstopFrames(app)
            val oldA = oldFrames.first { it.str("m") == "device.op" && it.obj("data")?.int("c") == 0 }

            // 复位重发/引擎锁存重放会再次调用 emergencyStop：旧命令链的 pending 必须一起作废，
            // 否则迟到的旧 resp 会把新链的 A 归零误标为 ACKED（Phase 3 审计 B2）。
            ctl.emergencyStop()
            awaitState(ctl) { it.estopFramesQueued }
            val newFrames = collectEstopFrames(app)
            val newA = newFrames.first { it.str("m") == "device.op" && it.obj("data")?.int("c") == 0 }

            app.replyReason(oldA.str("reqId"), "completed")
            Thread.sleep(300)
            assertFalse("旧命令链的迟到回执不能确认新链", ctl.state.value.estopAppAcked)
            assertEquals(
                EstopCommandAckState.PENDING,
                ctl.state.value.estopCommandResults.first { it.kind == EstopCommandKind.A_ZERO }.state,
            )

            val newClear = newFrames.first { it.str("m") == "device.op.clear" }
            val newB = newFrames.first { it.str("m") == "device.op" && it.obj("data")?.int("c") == 1 }
            app.reply(newA.str("reqId"), buildJsonObject {})
            app.replyReason(newB.str("reqId"), "completed")
            app.replyReason(newClear.str("reqId"), "cleared")
            awaitState(ctl) { it.estopAppAcked }
            assertTrue(ctl.state.value.estopAppAcked)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun resetAllIntensitiesQueuesBothChannelsInOnePriorityBatch() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000).also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            ctl.resetAllIntensities()
            val frames = ArrayList<JsonObject>()
            while (frames.size < 2) {
                val frame = app.pollFrame(FakeDglabApp.TIMEOUT_MS) ?: break
                val data = frame.messageData() ?: continue
                if (data.str("m") == "device.op") frames.add(data)
            }
            assertEquals("STOP 必须同时下发 A/B 两条 t=7 归零", 2, frames.size)
            assertEquals(setOf(0, 1), frames.map { it.obj("data")?.int("c") }.toSet())
            assertTrue(frames.all { it.obj("data")?.int("t") == 7 && it.obj("data")?.int("v") == 0 })
            assertEquals(
                "两条归零命令都应处于待回执（没有幽灵/丢失）",
                2,
                ctl.pendingRequestEntries().size,
            )
        } finally {
            app.closeQuietly()
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun connectAndSnapshot(app: FakeDglabApp, ctl: V4Controller) {
        assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
        app.awaitFrame { it.str("type") == "controller_attached" }
        val getReq = app.awaitFrame { frame ->
            frame.messageData()?.let { it.str("t") == "req" && it.str("m") == "devices.get" } == true
        }
        app.sendEvent("devices.snapshot", snapshotEvent())
        awaitState(ctl) { it.activeSlotId == "slot-1" }
        // 回 devices.get 的 resp（避免未决请求污染后续 pending 断言）
        app.replyOk(getReq.messageData()?.str("reqId"))
    }

    /** 依序收下急停的 A 归零 / B 归零 / clear 三条命令（顺序由控制器保证）。 */
    private fun collectEstopFrames(app: FakeDglabApp): List<JsonObject> {
        val frames = ArrayList<JsonObject>()
        while (frames.size < 3) {
            val frame = app.pollFrame(FakeDglabApp.TIMEOUT_MS) ?: break
            val data = frame.messageData() ?: continue
            if (data.str("m") == "device.op" || data.str("m") == "device.op.clear") {
                frames.add(data)
            }
        }
        assertTrue("应收到 A/B 归零 + clear 三帧", frames.size == 3)
        return frames
    }

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
}
