package com.motioncontroller.ui.model

import com.motioncontroller.dglab.DglabDevice
import com.motioncontroller.dglab.DglabState
import com.motioncontroller.ml.MotionPipelineStatus
import com.motioncontroller.service.EstopResetCheck
import com.motioncontroller.service.EstopResetCheckId
import com.motioncontroller.service.EstopResetCheckStatus
import com.motioncontroller.service.EstopResetFailure
import com.motioncontroller.service.EstopResetFailureCode
import com.motioncontroller.service.EstopResetPhase
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.i18n.EnUiStrings
import com.motioncontroller.ui.i18n.ZhUiStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EMERGENCY MODE 展示模型单测（纯 JVM）：
 *  - 停止命令链四阶段由真实 DglabState 派生；
 *  - APP ACK 绝不能推导出“设备已停止”（DEVICE 永远 UNVERIFIED）；
 *  - 复位资格只在链路/服务在线时成立（否则给出真实阻塞原因）；
 *  - 复位阶段/失败/自检清单真实透传。
 */
class EmergencyModelTest {

    private fun link(
        estopIssued: Boolean = true,
        framesQueued: Boolean = true,
        appAcked: Boolean = false,
        appConnected: Boolean = true,
        deviceReady: Boolean = true,
        relayRunning: Boolean = true,
    ) = DglabState(
        relayRunning = relayRunning,
        appClientIds = if (appConnected) listOf("client-1") else emptyList(),
        devices = if (deviceReady) {
            listOf(DglabDevice(slotId = "slot-1", name = "coyote", type = "COYOTE_030"))
        } else {
            emptyList()
        },
        activeSlotId = if (deviceReady) "slot-1" else null,
        emergencyStopped = true,
        estopIssued = estopIssued,
        estopFramesQueued = framesQueued,
        estopAppAcked = appAcked,
        estopAckReason = if (appAcked) "cleared" else null,
        targetIntensityA = 0,
        estimateIntensityA = 0,
        reportedIntensityA = 0,
    )

    private fun latchedState(
        link: DglabState = link(),
        serviceRunning: Boolean = true,
        resetPhase: EstopResetPhase = EstopResetPhase.NONE,
        checks: List<EstopResetCheck> = emptyList(),
        failure: EstopResetFailure? = null,
        completedAt: Long = 0L,
    ) = UiState(
        serviceRunning = serviceRunning,
        emergencyStopped = true,
        link = link,
        pipeline = MotionPipelineStatus(running = true, modelLoaded = true, scalerValid = true),
        estopResetPhase = resetPhase,
        estopResetChecks = checks,
        estopResetFailure = failure,
        estopResetCompletedAtMs = completedAt,
        estopDeviceStopUnverified = true,
    )

    @Test
    fun `停止命令链按真实字段派生`() {
        val base = EmergencyModel.derive(latchedState())
        assertEquals(EmergencyNodeStatus.ISSUED, base.status(EmergencyNodeId.COMMAND))
        assertEquals(EmergencyNodeStatus.QUEUED, base.status(EmergencyNodeId.FRAMES))
        assertEquals(EmergencyNodeStatus.PENDING, base.status(EmergencyNodeId.APP_ACK))
        assertEquals(EmergencyNodeStatus.UNVERIFIED, base.status(EmergencyNodeId.DEVICE))

        val acked = EmergencyModel.derive(latchedState(link = link(appAcked = true)))
        assertEquals(EmergencyNodeStatus.ACKED, acked.status(EmergencyNodeId.APP_ACK))
        // 关键安全断言：APP 已确认 ≠ 设备已停止
        assertEquals(EmergencyNodeStatus.UNVERIFIED, acked.status(EmergencyNodeId.DEVICE))
        assertFalse(acked.deviceStopVerified)
    }

    @Test
    fun `没有引擎时命令标记 FAILED 而不是伪造已发出`() {
        val noEngine = EmergencyModel.derive(
            latchedState(link = link(estopIssued = false, framesQueued = false), serviceRunning = false),
        )
        assertEquals(EmergencyNodeStatus.FAILED, noEngine.status(EmergencyNodeId.COMMAND))
        assertEquals(EmergencyNodeStatus.NOT_SENT, noEngine.status(EmergencyNodeId.FRAMES))
        assertFalse(noEngine.deviceStopVerified)
    }

    @Test
    fun `已发出但帧未入队时显示 PENDING 而非 ACKED`() {
        val pending = EmergencyModel.derive(latchedState(link = link(framesQueued = false)))
        assertEquals(EmergencyNodeStatus.PENDING, pending.status(EmergencyNodeId.FRAMES))
        assertEquals(EmergencyNodeStatus.PENDING, pending.status(EmergencyNodeId.APP_ACK))
    }

    @Test
    fun `设备确认永远是 UNVERIFIED 即使 APP ACK 且链路正常`() {
        val snapshot = EmergencyModel.derive(latchedState(link = link(appAcked = true)))
        assertNotEquals(EmergencyNodeStatus.ACKED, snapshot.status(EmergencyNodeId.DEVICE))
        assertEquals(EmergencyNodeStatus.UNVERIFIED, snapshot.status(EmergencyNodeId.DEVICE))
        assertFalse(snapshot.deviceStopVerified)
    }

    @Test
    fun `链路就绪时允许请求复位`() {
        val snapshot = EmergencyModel.derive(latchedState())
        assertTrue(snapshot.canReset)
        assertNull(snapshot.resetBlockerCode)
    }

    @Test
    fun `停止帧尚未入队时仍允许请求复位（复位流程会先补发停止命令）`() {
        val snapshot = EmergencyModel.derive(
            latchedState(link = link(framesQueued = false, appAcked = false)),
        )
        assertTrue(snapshot.latched)
        assertTrue(snapshot.canReset)
        assertNull(snapshot.resetBlockerCode)
        assertEquals(EmergencyNodeStatus.PENDING, snapshot.status(EmergencyNodeId.FRAMES))
    }

    @Test
    fun `链路缺失时复位被阻塞并给出真实原因`() {
        assertEquals(
            EstopResetFailureCode.APP_NOT_CONNECTED,
            EmergencyModel.derive(
                latchedState(link = link(appConnected = false)),
            ).resetBlockerCode,
        )
        assertEquals(
            EstopResetFailureCode.DEVICE_NOT_CONNECTED,
            EmergencyModel.derive(
                latchedState(link = link(deviceReady = false)),
            ).resetBlockerCode,
        )
        assertEquals(
            EstopResetFailureCode.RELAY_OFFLINE,
            EmergencyModel.derive(
                latchedState(link = link(relayRunning = false)),
            ).resetBlockerCode,
        )
        assertEquals(
            EstopResetFailureCode.SERVICE_OFFLINE,
            EmergencyModel.derive(
                latchedState(serviceRunning = false),
            ).resetBlockerCode,
        )
        assertFalse(EmergencyModel.derive(latchedState(link = link(appConnected = false))).canReset)
    }

    @Test
    fun `复位进行中时不可再次请求复位`() {
        val resetting = EmergencyModel.derive(
            latchedState(resetPhase = EstopResetPhase.RESETTING),
        )
        assertTrue(resetting.resetInProgress)
        assertFalse(resetting.canReset)
    }

    @Test
    fun `复位阶段 失败原因 与 自检清单 真实透传`() {
        val checks = listOf(
            EstopResetCheck(EstopResetCheckId.SERVICE, EstopResetCheckStatus.PASS),
            EstopResetCheck(EstopResetCheckId.APP_ACK, EstopResetCheckStatus.WAITING),
            EstopResetCheck(EstopResetCheckId.DEVICE_CONFIRM, EstopResetCheckStatus.UNVERIFIED),
        )
        val failure = EstopResetFailure(EstopResetFailureCode.STOP_COMMAND_NOT_ACKED, "pending")
        val failed = EmergencyModel.derive(
            latchedState(resetPhase = EstopResetPhase.FAILED, checks = checks, failure = failure),
        )
        assertEquals(EstopResetPhase.FAILED, failed.resetPhase)
        assertEquals(failure, failed.resetFailure)
        assertEquals(checks, failed.resetChecks)
        assertTrue(failed.latched)

        val completed = EmergencyModel.derive(latchedState(completedAt = 123L))
        assertTrue(completed.resetCompleted)
    }

    @Test
    fun `未锁存时节点不是已发出`() {
        val idle = EmergencyModel.derive(UiState())
        assertFalse(idle.latched)
        assertEquals(EmergencyNodeStatus.NOT_SENT, idle.status(EmergencyNodeId.COMMAND))
        // 该页面本不该显示；即使被渲染，也绝不显示 ACKED/已停止
        assertNotEquals(EmergencyNodeStatus.ACKED, idle.status(EmergencyNodeId.DEVICE))
    }

    @Test
    fun `中英文案映射完整且状态语义不会混淆`() {
        listOf(ZhUiStrings, EnUiStrings).forEach { strings ->
            EmergencyNodeId.entries.forEach { id ->
                assertTrue(strings.emergencyNodeLabel(id).isNotBlank())
            }
            EmergencyNodeStatus.entries.forEach { status ->
                assertTrue(strings.emergencyStatusLabel(status).isNotBlank())
            }
            EstopResetCheckId.entries.forEach { id ->
                assertTrue(strings.resetCheckLabel(id).isNotBlank())
            }
            EstopResetFailureCode.entries.forEach { code ->
                assertTrue(
                    strings.estopResetFailureText(EstopResetFailure(code)).isNotBlank(),
                )
            }
            EstopResetCheckStatus.entries.forEach { status ->
                assertTrue(strings.estopResetCheckStatus(status).isNotBlank())
            }
        }
        // “已确认(APP)” 与 “设备无法验证” 必须是不同文案，避免把 APP ACK 读成设备已停止
        assertNotEquals(
            ZhUiStrings.emergencyStatusAcked,
            ZhUiStrings.emergencyStatusUnverified,
        )
        assertNotEquals(
            EnUiStrings.emergencyStatusAcked,
            EnUiStrings.emergencyStatusUnverified,
        )
    }
}
