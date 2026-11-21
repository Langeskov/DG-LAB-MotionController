package com.motioncontroller.ui.model

import com.motioncontroller.dglab.DglabDevice
import com.motioncontroller.dglab.DglabState
import com.motioncontroller.ml.MotionClass
import com.motioncontroller.service.ControlTransition
import com.motioncontroller.service.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlModelTest {

    private fun linkedState(): UiState = UiState(
        serviceRunning = true,
        link = DglabState(
            relayRunning = true,
            appClientIds = listOf("client-1"),
            devices = listOf(DglabDevice(slotId = "slot-1", name = "coyote", type = "COYOTE_030")),
            activeSlotId = "slot-1",
        ),
    )

    @Test
    fun `未启动服务时是 STANDBY 且指出阻塞原因`() {
        val snapshot = ControlModel.derive(UiState())
        assertEquals(ControlPhase.STANDBY, snapshot.phase)
        assertFalse(snapshot.startEnabled)
        assertTrue(snapshot.blockers.isNotEmpty())
    }

    @Test
    fun `链路与设备就绪时可以启动`() {
        val snapshot = ControlModel.derive(linkedState())
        assertEquals(ControlPhase.STANDBY, snapshot.phase)
        assertTrue(snapshot.startEnabled)
        assertTrue(snapshot.blockers.isEmpty())
    }

    @Test
    fun `启动后进入 RUNNING，停止命令窗口是 STOPPING`() {
        val running = ControlModel.derive(linkedState().copy(armed = true))
        assertEquals(ControlPhase.RUNNING, running.phase)
        assertTrue(running.stopEnabled)

        val starting = ControlModel.derive(
            linkedState().copy(armed = true, controlTransition = ControlTransition.STARTING),
        )
        assertEquals(ControlPhase.STARTING, starting.phase)

        val stopping = ControlModel.derive(
            linkedState().copy(armed = false, controlTransition = ControlTransition.STOPPING),
        )
        assertEquals(ControlPhase.STOPPING, stopping.phase)
    }

    @Test
    fun `急停优先于其它状态且禁止启动`() {
        val snapshot = ControlModel.derive(linkedState().copy(armed = true, emergencyStopped = true))
        assertEquals(ControlPhase.ESTOP_LATCHED, snapshot.phase)
        assertFalse(snapshot.startEnabled)
        assertFalse(snapshot.stopEnabled)
        assertFalse(snapshot.estopEnabled)
        assertTrue(snapshot.resetEstopEnabled)
        assertEquals("E-STOP LATCHED — RESET REQUIRED", snapshot.primaryBlocker)
    }

    @Test
    fun `引擎失败是 FAULT 并暴露错误原因`() {
        val snapshot = ControlModel.derive(
            UiState(serviceRunning = false, engineError = "引擎启动失败: bind failed"),
        )
        assertEquals(ControlPhase.FAULT, snapshot.phase)
        assertEquals("引擎启动失败: bind failed", snapshot.primaryBlocker)
    }

    @Test
    fun `APP 未连接与设备未连接分别给出原因`() {
        // 设备信息来自 APP 快照：没有 APP 但快照里仍有设备时，只报 APP 未连接
        val noApp = ControlModel.derive(
            linkedState().copy(
                link = DglabState(
                    relayRunning = true,
                    devices = listOf(DglabDevice(slotId = "slot-1", name = "coyote", type = "COYOTE_030")),
                    activeSlotId = "slot-1",
                ),
            ),
        )
        assertEquals(listOf("DG-LAB APP NOT CONNECTED"), noApp.blockers)

        val appOnly = ControlModel.derive(
            linkedState().copy(
                link = DglabState(relayRunning = true, appClientIds = listOf("client-1")),
            ),
        )
        assertEquals(listOf("DEVICE NOT CONNECTED"), appOnly.blockers)
    }

    @Test
    fun `归还自动控制需要新鲜的模型结果`() {
        val manual = linkedState().copy(
            manualClass = MotionClass.CYCLING.ordinal,
            effectiveClass = MotionClass.CYCLING.ordinal,
            stableClass = MotionClass.CYCLING.ordinal,
            pipeline = linkedState().pipeline.copy(running = true),
            predictionAgeMs = 100L,
        )
        assertNull(ControlModel.returnToAutoBlocker(manual.copy(prediction = fakePrediction())))
        assertTrue(ControlModel.canReturnToAuto(manual.copy(prediction = fakePrediction())))

        // 数据过期
        assertTrue(
            ControlModel.returnToAutoBlocker(
                manual.copy(prediction = fakePrediction(), predictionAgeMs = 5_000L),
            )!!.contains("STALE"),
        )
        // 看门狗暂停
        assertTrue(
            ControlModel.returnToAutoBlocker(
                manual.copy(prediction = fakePrediction(), recognitionPaused = true),
            )!!.contains("PAUSED"),
        )
        // 模型还没有稳定类别
        assertTrue(
            ControlModel.returnToAutoBlocker(
                manual.copy(prediction = fakePrediction(), stableClass = -1),
            )!!.contains("NOT SETTLED"),
        )
        // AUTO 状态没有归还概念
        assertNull(ControlModel.returnToAutoBlocker(manual.copy(manualClass = null)))
    }

    private fun fakePrediction() = com.motioncontroller.ml.MotionPrediction(
        probs = FloatArray(MotionClass.entries.size) { 1f / MotionClass.entries.size },
        classIndex = MotionClass.CYCLING.ordinal,
        timestampMs = 0L,
    )
}
