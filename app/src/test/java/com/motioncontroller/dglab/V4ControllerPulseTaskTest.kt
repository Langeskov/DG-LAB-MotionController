package com.motioncontroller.dglab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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

/**
 * Phase 4A：t=0 AppendPulseData 协议层回归。
 *
 * 注意：本测试只验证「帧结构 / 下发 / 终态映射 / 安全门禁」，不验证波形内容安全；
 * 实验能力默认关闭，只有构造时显式 `pulseTasksEnabled = true` 才会入队。
 */
class V4ControllerPulseTaskTest {

    private var controller: V4Controller? = null

    @After
    fun tearDown() {
        controller?.stop()
        controller = null
    }

    // ───────────────────────── 校验器（纯 JVM） ─────────────────────────

    @Test
    fun validatorAcceptsWellFormedV3AndV2Frames() {
        val v3 = PulseTaskSpec(
            channel = 0,
            frames = listOf(List(8) { 10 }),
            version = PulseFrameVersion.V3,
            durationMs = 1_000,
        )
        assertNull(PulseTaskSpecValidator.validate(v3))

        val v2 = PulseTaskSpec(
            channel = 1,
            frames = listOf(listOf(1, 2, 3)),
            version = PulseFrameVersion.V2,
        )
        assertNull(PulseTaskSpecValidator.validate(v2))
    }

    @Test
    fun validatorRejectsMalformedSpecs() {
        val base = PulseTaskSpec(channel = 0, frames = listOf(List(8) { 1 }))
        assertNotNull(PulseTaskSpecValidator.validate(base.copy(frames = emptyList())))
        assertNotNull(PulseTaskSpecValidator.validate(base.copy(channel = 2)))
        assertNotNull(
            PulseTaskSpecValidator.validate(
                base.copy(frames = listOf(List(7) { 1 })),
            ),
        )
        assertNotNull(
            PulseTaskSpecValidator.validate(
                base.copy(frames = listOf(listOf(1, 2, 3))),
            ),
        )
        assertNotNull(PulseTaskSpecValidator.validate(base.copy(durationMs = -1)))
        assertNotNull(
            PulseTaskSpecValidator.validate(
                base.copy(durationMs = PulseTaskSpecValidator.MAX_DURATION_MS + 1),
            ),
        )
        assertNotNull(
            PulseTaskSpecValidator.validate(
                base.copy(frames = List(PulseTaskSpecValidator.MAX_FRAMES + 1) { List(8) { 1 } }),
            ),
        )
        assertNotNull(
            PulseTaskSpecValidator.validate(
                base.copy(frames = listOf(List(8) { 256 })),
            ),
        )
    }

    // ───────────────────────── 安全门禁 ─────────────────────────

    @Test
    fun disabledByDefaultRefusesPulseTask() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000).also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            val accepted = ctl.startPulseTask(validSpec())
            assertFalse("Phase 4A 默认必须关闭 t=0", accepted)
            assertTrue(ctl.state.value.pulseTasks.isEmpty())
            assertTrue(
                "必须记录拒绝原因: ${ctl.logLines.value.takeLast(3)}",
                ctl.logLines.value.any { "实验能力未启用" in it },
            )
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun enabledControllerRefusesPulseTaskWhileEstopLatched() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000, pulseTasksEnabled = true)
            .also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            ctl.emergencyStop()
            assertFalse("急停锁存中禁止下发 t=0", ctl.startPulseTask(validSpec()))
            assertTrue(ctl.state.value.pulseTasks.isEmpty())
        } finally {
            app.closeQuietly()
        }
    }

    // ───────────────────────── 下发与终态 ─────────────────────────

    @Test
    fun enabledControllerSendsT0FrameAndTracksCompleted() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000, pulseTasksEnabled = true)
            .also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            val frames = listOf(listOf(1, 2, 3, 4, 5, 6, 7, 8), listOf(8, 7, 6, 5, 4, 3, 2, 1))
            assertTrue(ctl.startPulseTask(PulseTaskSpec(channel = 0, frames = frames, durationMs = 300)))

            val frame = awaitPulseFrame(app, channel = 0)
            val data = frame.messageData()!!.obj("data")!!
            assertEquals(0, data.int("t"))
            assertEquals(0, data.int("c"))
            assertEquals(300, data.int("d"))
            assertEquals(3, data.int("ver"))
            assertEquals(true, (data["im"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull)
            assertEquals("slot-1", data.str("s"))
            val v = data.arr("v")!!
            assertEquals(2, v.size)
            assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), (v[0] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() })
            assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1), (v[1] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() })

            val running = ctl.state.value.pulseTasks.single()
            assertEquals(0, running.channel)
            assertEquals(PulseTaskState.RUNNING, running.state)
            assertNotNull(running.reqId)

            app.replyReason(running.reqId, "completed")
            awaitState(ctl) { state ->
                state.pulseTasks.singleOrNull()?.state == PulseTaskState.COMPLETED
            }
            assertEquals(PulseTaskState.COMPLETED, ctl.state.value.pulseTasks.single().state)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun t0TerminalReasonsMapToProtocolStates() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000, pulseTasksEnabled = true)
            .also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            for ((reason, expected) in listOf(
                "completed" to PulseTaskState.COMPLETED,
                "cleared" to PulseTaskState.CLEARED,
                "replaced" to PulseTaskState.REPLACED,
                "cancelled" to PulseTaskState.CANCELLED,
            )) {
                assertTrue(ctl.startPulseTask(validSpec(replace = false)))
                val frame = awaitPulseFrame(app, channel = 0)
                val reqId = frame.messageData()!!.str("reqId")
                app.replyReason(reqId, reason)
                awaitState(ctl) { state -> state.pulseTasks.single().state == expected }
                assertEquals(reason, expected, ctl.state.value.pulseTasks.single().state)
            }

            // 错误响应 → FAILED
            assertTrue(ctl.startPulseTask(validSpec(replace = false)))
            val bad = awaitPulseFrame(app, channel = 0)
            app.replyError(bad.messageData()!!.str("reqId"), "invalid_operate")
            awaitState(ctl) { state -> state.pulseTasks.single().state == PulseTaskState.FAILED }
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun emergencyStopCancelsPulseTask() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000, pulseTasksEnabled = true)
            .also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            assertTrue(ctl.startPulseTask(validSpec()))
            val frame = awaitPulseFrame(app, channel = 0)
            val reqId = frame.messageData()!!.str("reqId")

            ctl.emergencyStop()
            val cancelled = ctl.state.value.pulseTasks.single()
            assertEquals(PulseTaskState.CANCELLED, cancelled.state)
            assertEquals("emergency_stop", cancelled.reason)

            // 迟到的 completed 回执不能把已取消的任务改回完成
            app.replyReason(reqId, "completed")
            Thread.sleep(300)
            assertEquals(PulseTaskState.CANCELLED, ctl.state.value.pulseTasks.single().state)
        } finally {
            app.closeQuietly()
        }
    }

    @Test
    fun clearPulseTasksSendsClearAndResetsLocalState() {
        val ctl = V4Controller(tickMs = 20, opIntervalMs = 1_000, pulseTasksEnabled = true)
            .also { controller = it }
        ctl.start("127.0.0.1", 0)
        val started = awaitState(ctl) { it.relayRunning && it.targetId.isNotBlank() }
        val app = FakeDglabApp(URI("ws://127.0.0.1:${started.port}/?tid=${started.targetId}"))
        try {
            connectAndSnapshot(app, ctl)
            assertTrue(ctl.startPulseTask(validSpec()))
            awaitPulseFrame(app, channel = 0)

            ctl.clearPulseTasks()
            assertTrue("本地任务状态应清空", ctl.state.value.pulseTasks.isEmpty())

            // clear 帧（device.op.clear）应已发出
            val deadline = System.currentTimeMillis() + 3_000
            var sawClear = false
            while (System.currentTimeMillis() < deadline && !sawClear) {
                val frame = app.pollFrame(300) ?: continue
                val data = frame.messageData() ?: continue
                if (data.str("m") == "device.op.clear") sawClear = true
            }
            assertTrue("clearPulseTasks 必须先发 device.op.clear", sawClear)
        } finally {
            app.closeQuietly()
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun validSpec(replace: Boolean = true): PulseTaskSpec =
        PulseTaskSpec(
            channel = 0,
            frames = listOf(List(8) { 5 }),
            durationMs = 1_000,
            replace = replace,
        )

    private fun connectAndSnapshot(app: FakeDglabApp, ctl: V4Controller) {
        assertTrue(app.connectBlocking(FakeDglabApp.TIMEOUT_MS, TimeUnit.MILLISECONDS))
        app.awaitFrame { it.str("type") == "controller_attached" }
        val getReq = app.awaitFrame { frame ->
            frame.messageData()?.let { it.str("t") == "req" && it.str("m") == "devices.get" } == true
        }
        app.sendEvent(
            "devices.snapshot",
            buildJsonObject {
                put("devices", buildJsonArray {
                    add(buildJsonObject {
                        put("slotId", "slot-1")
                        put("name", "郊狼 3.0")
                        put("type", "COYOTE_030")
                        put("props", buildJsonObject { put("connectState", "connected") })
                        put("slotState", buildJsonObject { put("hasDevice", true) })
                    })
                })
            },
        )
        awaitState(ctl) { it.activeSlotId == "slot-1" }
        app.replyOk(getReq.messageData()?.str("reqId"))
    }

    private fun awaitPulseFrame(app: FakeDglabApp, channel: Int): JsonObject {
        val deadline = System.currentTimeMillis() + FakeDglabApp.TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val frame = app.pollFrame(300) ?: continue
            val data = frame.messageData() ?: continue
            if (data.str("m") == "device.op" && data.obj("data")?.int("t") == 0 &&
                data.obj("data")?.int("c") == channel
            ) {
                return frame
            }
        }
        throw AssertionError("等待 t=0 波形帧超时（channel=$channel）")
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
