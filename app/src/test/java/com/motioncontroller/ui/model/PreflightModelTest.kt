package com.motioncontroller.ui.model

import com.motioncontroller.dglab.DglabDevice
import com.motioncontroller.dglab.DglabState
import com.motioncontroller.ml.MotionPipelineStatus
import com.motioncontroller.service.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightModelTest {

    private fun readyPipeline() = MotionPipelineStatus(
        running = true,
        modelLoaded = true,
        scalerValid = true,
        gpsAvailable = true,
        gpsSpeedKmh = 12.5f,
        gpsFixAgeMs = 200L,
        gpsEverFix = true,
        bufferedFrames = 150,
    )

    private fun readyState(): UiState = UiState(
        serviceRunning = true,
        pipeline = readyPipeline(),
        scalerValid = true,
        bindHost = "127.0.0.1",
        port = 9998,
        address = "ws://127.0.0.1:9998/?tid=abcd1234",
        link = DglabState(
            relayRunning = true,
            targetId = "abcd1234",
            appClientIds = listOf("client-1"),
            devices = listOf(DglabDevice(slotId = "slot-1", name = "coyote", type = "COYOTE_030")),
            activeSlotId = "slot-1",
        ),
    )

    @Test
    fun `全部关键项通过时允许进入并允许启动动效`() {
        val snapshot = PreflightModel.derive(readyState(), serviceBound = true)
        assertTrue(snapshot.canEnter)
        assertTrue(snapshot.bootSequenceAllowed)
        assertFalse(snapshot.criticalFailure)
        assertFalse(snapshot.waiting)
        assertNull(snapshot.blocker)
        assertEquals(CheckStatus.PASS, snapshot.check("service")?.status)
        assertEquals(CheckStatus.PASS, snapshot.check("relay")?.status)
        assertEquals(CheckStatus.PASS, snapshot.check("model")?.status)
        assertEquals(CheckStatus.PASS, snapshot.check("scaler")?.status)
        assertEquals(CheckStatus.PASS, snapshot.check("app")?.status)
    }

    @Test
    fun `APP 未连接阻塞进入控制页（真机反馈：未连接不得进 OPERATION）`() {
        val state = readyState().copy(
            link = DglabState(relayRunning = true, targetId = "abcd1234"),
        )
        val snapshot = PreflightModel.derive(state, serviceBound = true)
        assertFalse(snapshot.canEnter)
        assertFalse(snapshot.bootSequenceAllowed)
        assertEquals(CheckStatus.DISCONNECTED, snapshot.check("app")?.status)
        assertTrue(snapshot.check("app")!!.critical)
        assertEquals("app", snapshot.blocker?.id)
    }

    @Test
    fun `设备未连接不阻塞进入（START 会另行阻塞并显示原因）`() {
        val state = readyState().copy(
            link = DglabState(
                relayRunning = true,
                targetId = "abcd1234",
                appClientIds = listOf("client-1"),
            ),
        )
        val snapshot = PreflightModel.derive(state, serviceBound = true)
        assertTrue(snapshot.canEnter)
        assertEquals(CheckStatus.DISCONNECTED, snapshot.check("device")?.status)
        assertFalse(snapshot.check("device")!!.critical)
    }

    @Test
    fun `关键失败不能进入也不能伪造 READY`() {
        val state = readyState().copy(
            serviceRunning = false,
            engineError = "引擎启动失败: bind failed",
        )
        val snapshot = PreflightModel.derive(state, serviceBound = true)
        assertFalse(snapshot.canEnter)
        assertFalse(snapshot.bootSequenceAllowed)
        assertTrue(snapshot.criticalFailure)
        assertEquals(CheckStatus.FAIL, snapshot.check("service")?.status)
        assertEquals(CheckStatus.FAIL, snapshot.check("relay")?.status)
    }

    @Test
    fun `服务尚未绑定或模型加载中是 WAITING 而不是失败`() {
        val binding = PreflightModel.derive(readyState(), serviceBound = false)
        assertFalse(binding.canEnter)
        assertTrue(binding.waiting)
        assertFalse(binding.criticalFailure)
        assertEquals(CheckStatus.WAITING, binding.check("service")?.status)

        val loading = PreflightModel.derive(
            readyState().copy(pipeline = MotionPipelineStatus(running = true)),
            serviceBound = true,
        )
        assertFalse(loading.canEnter)
        assertTrue(loading.waiting)
        assertEquals(CheckStatus.WAITING, loading.check("model")?.status)
    }

    @Test
    fun `scaler 无效是关键失败（推理不可信）`() {
        val state = readyState().copy(
            pipeline = readyPipeline().copy(scalerValid = false),
            scalerValid = false,
        )
        val snapshot = PreflightModel.derive(state, serviceBound = true)
        assertTrue(snapshot.criticalFailure)
        assertFalse(snapshot.canEnter)
        assertEquals(CheckStatus.FAIL, snapshot.check("scaler")?.status)
    }

    @Test
    fun `GNSS 丢失只是等待态不阻塞`() {
        val state = readyState().copy(
            pipeline = readyPipeline().copy(
                gpsAvailable = false,
                gpsFixAgeMs = 2_400L,
                gpsEverFix = true,
            ),
        )
        val snapshot = PreflightModel.derive(state, serviceBound = true)
        assertTrue(snapshot.canEnter)
        assertEquals(CheckStatus.WAITING, snapshot.check("gnss")?.status)
        assertFalse(snapshot.check("gnss")!!.critical)
    }

    @Test
    fun `blocker 优先级 FAIL 高于 DISCONNECTED 高于 WAITING`() {
        // APP 未连接（DISCONNECTED）+ scaler 无效（FAIL）→ blocker 必须是 FAIL 的 scaler
        val state = readyState().copy(
            pipeline = readyPipeline().copy(scalerValid = false),
            scalerValid = false,
            link = DglabState(relayRunning = true, targetId = "abcd1234"),
        )
        val snapshot = PreflightModel.derive(state, serviceBound = true)
        assertEquals("scaler", snapshot.blocker?.id)

        // 仅 APP 未连接（DISCONNECTED）+ 模型加载中（WAITING）→ blocker 是 DISCONNECTED 的 app
        val loading = PreflightModel.derive(
            readyState().copy(
                pipeline = MotionPipelineStatus(running = true, modelLoaded = true, scalerValid = true),
                link = DglabState(relayRunning = true, targetId = "abcd1234"),
            ),
            serviceBound = true,
        )
        assertEquals("app", loading.blocker?.id)
    }

    @Test
    fun `多机模式自检通过时自检详情不含监听通配地址`() {
        val state = readyState().copy(bindHost = "0.0.0.0", port = 9998)
        val snapshot = PreflightModel.derive(state, serviceBound = true)
        assertTrue(snapshot.canEnter)
        // 自检详情只说明中继在监听 + tid，不把 0.0.0.0 当作客户端连接地址展示
        assertTrue(snapshot.check("relay")!!.detail.contains("tid"))
        assertFalse(snapshot.check("relay")!!.detail.contains("0.0.0.0"))
        assertEquals(ConnectionMode.MULTI, ConnectionMode.ofBindHost(state.bindHost))
        assertTrue(state.address.startsWith("ws://127.0.0.1"))
    }
}
