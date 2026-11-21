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

/** 复位后恢复检查：只读现有 UiState，不重启服务/不重新握手。 */
class RecoveryModelTest {

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
        armed = false,
        pipeline = readyPipeline(),
        scalerValid = true,
        link = DglabState(
            relayRunning = true,
            targetId = "abcd1234",
            appClientIds = listOf("client-1"),
            devices = listOf(DglabDevice(slotId = "slot-1", name = "coyote", type = "COYOTE_030")),
            activeSlotId = "slot-1",
        ),
    )

    @Test
    fun `会话有效且未启动控制时可以恢复 OPERATION`() {
        val snapshot = RecoveryModel.derive(readyState(), serviceBound = true)
        assertTrue(snapshot.canRestore)
        assertNull(snapshot.blocker)
        assertEquals(8, snapshot.totalCount)
        assertEquals(8, snapshot.passCount)
        assertEquals("service", snapshot.checks.first().id)
        assertEquals("control_session", snapshot.checks.last().id)
        assertEquals(CheckStatus.PASS, snapshot.checks.first { it.id == "app" }.status)
        assertEquals(CheckStatus.PASS, snapshot.checks.first { it.id == "control_session" }.status)
    }

    @Test
    fun `APP 实际断连时不能恢复并给出阻塞项`() {
        val state = readyState().copy(link = DglabState(relayRunning = true, targetId = "abcd1234"))
        val snapshot = RecoveryModel.derive(state, serviceBound = true)
        assertFalse(snapshot.canRestore)
        assertEquals("app", snapshot.blocker?.id)
        assertEquals(CheckStatus.DISCONNECTED, snapshot.checks.first { it.id == "app" }.status)
    }

    @Test
    fun `设备未连接不阻塞恢复（与既有 PRE-FLIGHT 门禁一致）`() {
        val state = readyState().copy(
            link = readyState().link.copy(devices = emptyList(), activeSlotId = ""),
        )
        val snapshot = RecoveryModel.derive(state, serviceBound = true)
        assertTrue(snapshot.canRestore)
        assertEquals(CheckStatus.DISCONNECTED, snapshot.checks.first { it.id == "device" }.status)
        assertFalse(snapshot.checks.first { it.id == "device" }.critical)
    }

    @Test
    fun `DEVICE UNVERIFIED 不等于连接失败，不阻塞恢复`() {
        val state = readyState().copy(
            estopDeviceStopUnverified = true,
            link = readyState().link.copy(devices = emptyList(), activeSlotId = ""),
        )
        val snapshot = RecoveryModel.derive(state, serviceBound = true)
        assertTrue(snapshot.canRestore)
        assertTrue(snapshot.checks.any { it.id == "estop_recovery" })
        assertEquals(CheckStatus.WAITING, snapshot.checks.first { it.id == "estop_recovery" }.status)
        assertFalse(snapshot.checks.first { it.id == "estop_recovery" }.critical)
    }

    @Test
    fun `控制仍在运行或急停未解除时拒绝恢复`() {
        val armed = RecoveryModel.derive(readyState().copy(armed = true), serviceBound = true)
        assertFalse(armed.canRestore)
        assertEquals("control_session", armed.blocker?.id)
        assertEquals(CheckStatus.FAIL, armed.checks.first { it.id == "control_session" }.status)

        val latched = RecoveryModel.derive(readyState().copy(emergencyStopped = true), serviceBound = true)
        assertFalse(latched.canRestore)
        assertEquals(CheckStatus.FAIL, latched.checks.first { it.id == "control_session" }.status)
    }

    @Test
    fun `服务未绑定或模型未就绪时等待而不是恢复`() {
        val binding = RecoveryModel.derive(readyState(), serviceBound = false)
        assertFalse(binding.canRestore)
        assertEquals(CheckStatus.WAITING, binding.checks.first { it.id == "service" }.status)

        val noModel = RecoveryModel.derive(
            readyState().copy(pipeline = readyPipeline().copy(modelLoaded = false)),
            serviceBound = true,
        )
        assertFalse(noModel.canRestore)
        assertEquals(CheckStatus.WAITING, noModel.checks.first { it.id == "model" }.status)
    }
}
