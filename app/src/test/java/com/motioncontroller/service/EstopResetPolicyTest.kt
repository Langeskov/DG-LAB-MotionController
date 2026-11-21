package com.motioncontroller.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E-STOP 复位策略单测（纯 JVM）：
 *  - 链路/自检失败必须阻止清锁存；
 *  - APP ACK 不代表设备已停止，DEVICE_CONFIRM 永远 UNVERIFIED；
 *  - 检查清单状态由真实快照派生，不能伪造 PASS。
 */
class EstopResetPolicyTest {

    private fun good() = EstopResetLinkSnapshot(
        serviceRunning = true,
        engineAvailable = true,
        relayRunning = true,
        appConnected = true,
        deviceReady = true,
        stopIssued = true,
        framesQueued = true,
        appAcked = true,
        ackReason = "cleared",
        modelLoaded = true,
        scalerValid = true,
    )

    @Test
    fun `链路与自检全部通过且 APP 已回执时才允许清锁存`() {
        assertNull(EstopResetPolicy.evaluateLink(good()))
        assertNull(EstopResetPolicy.evaluateSelfCheck(good()))
        assertTrue(EstopResetPolicy.canClearLatch(good()))

        // 没有 APP 回执：不能清锁存（停止命令尚未确认送达）
        assertFalse(EstopResetPolicy.canClearLatch(good().copy(appAcked = false)))
    }

    @Test
    fun `链路缺失按优先级返回失败原因`() {
        assertEquals(
            EstopResetFailureCode.SERVICE_OFFLINE,
            EstopResetPolicy.evaluateLink(good().copy(serviceRunning = false))?.code,
        )
        assertEquals(
            EstopResetFailureCode.SERVICE_OFFLINE,
            EstopResetPolicy.evaluateLink(good().copy(engineAvailable = false))?.code,
        )
        assertEquals(
            EstopResetFailureCode.RELAY_OFFLINE,
            EstopResetPolicy.evaluateLink(good().copy(relayRunning = false))?.code,
        )
        assertEquals(
            EstopResetFailureCode.APP_NOT_CONNECTED,
            EstopResetPolicy.evaluateLink(good().copy(appConnected = false))?.code,
        )
        assertEquals(
            EstopResetFailureCode.DEVICE_NOT_CONNECTED,
            EstopResetPolicy.evaluateLink(good().copy(deviceReady = false))?.code,
        )
        // 链路在线但停止帧没能入队：不能当作已下发
        assertEquals(
            EstopResetFailureCode.STOP_COMMAND_NOT_SENT,
            EstopResetPolicy.evaluateLink(good().copy(framesQueued = false))?.code,
        )
    }

    @Test
    fun `启动自检检查模型与 scaler`() {
        assertEquals(
            EstopResetFailureCode.MODEL_NOT_READY,
            EstopResetPolicy.evaluateSelfCheck(good().copy(modelLoaded = false))?.code,
        )
        assertEquals(
            EstopResetFailureCode.SCALER_INVALID,
            EstopResetPolicy.evaluateSelfCheck(good().copy(scalerValid = false))?.code,
        )
        // 模型问题优先于 scaler 问题
        assertEquals(
            EstopResetFailureCode.MODEL_NOT_READY,
            EstopResetPolicy.evaluateSelfCheck(good().copy(modelLoaded = false, scalerValid = false))?.code,
        )
    }

    @Test
    fun `链路不可用时不重发停止命令，可用但未回执或未入队时才重发`() {
        assertFalse(EstopResetPolicy.needsStopReissue(good()))
        assertTrue(EstopResetPolicy.needsStopReissue(good().copy(appAcked = false)))
        // 帧还没入队（例如急停时链路离线、之后才恢复）：必须先补发停止命令
        assertTrue(EstopResetPolicy.needsStopReissue(good().copy(framesQueued = false)))
        assertFalse(EstopResetPolicy.needsStopReissue(good().copy(appAcked = false, appConnected = false)))
        assertFalse(EstopResetPolicy.needsStopReissue(good().copy(appAcked = false, deviceReady = false)))
    }

    @Test
    fun `链路可用性检查只覆盖服务中继APP设备`() {
        assertNull(EstopResetPolicy.evaluateLinks(good().copy(framesQueued = false, appAcked = false)))
        assertEquals(
            EstopResetFailureCode.APP_NOT_CONNECTED,
            EstopResetPolicy.evaluateLinks(good().copy(appConnected = false))?.code,
        )
        // 完整检查在链路可用后还要检查停止命令是否入队
        assertEquals(
            EstopResetFailureCode.STOP_COMMAND_NOT_SENT,
            EstopResetPolicy.evaluateLink(good().copy(framesQueued = false))?.code,
        )
    }

    @Test
    fun `检查清单覆盖全部阶段且设备确认永远 UNVERIFIED`() {
        val checks = EstopResetPolicy.checks(good())
        assertEquals(EstopResetCheckId.entries.toList(), checks.map { it.id })
        assertEquals(9, checks.size)
        checks.forEach { check ->
            if (check.id == EstopResetCheckId.DEVICE_CONFIRM) {
                assertEquals(EstopResetCheckStatus.UNVERIFIED, check.status)
            } else {
                assertEquals(EstopResetCheckStatus.PASS, check.status)
            }
        }
        // APP ACK = PASS 也不能把设备确认变成 PASS
        val ackedChecks = EstopResetPolicy.checks(good().copy(appAcked = true))
        assertEquals(
            EstopResetCheckStatus.UNVERIFIED,
            ackedChecks.first { it.id == EstopResetCheckId.DEVICE_CONFIRM }.status,
        )
    }

    @Test
    fun `检查清单如实反映失败与等待状态`() {
        val snapshot = good().copy(
            serviceRunning = false,
            relayRunning = false,
            appConnected = false,
            deviceReady = false,
            framesQueued = false,
            appAcked = false,
            modelLoaded = false,
            scalerValid = false,
        )
        val byId = EstopResetPolicy.checks(snapshot).associateBy { it.id }
        assertEquals(EstopResetCheckStatus.FAIL, byId.getValue(EstopResetCheckId.SERVICE).status)
        assertEquals(EstopResetCheckStatus.FAIL, byId.getValue(EstopResetCheckId.RELAY).status)
        assertEquals(EstopResetCheckStatus.FAIL, byId.getValue(EstopResetCheckId.APP_LINK).status)
        assertEquals(EstopResetCheckStatus.FAIL, byId.getValue(EstopResetCheckId.DEVICE_LINK).status)
        assertEquals(EstopResetCheckStatus.FAIL, byId.getValue(EstopResetCheckId.STOP_COMMAND).status)
        assertEquals(EstopResetCheckStatus.WAITING, byId.getValue(EstopResetCheckId.APP_ACK).status)
        assertEquals(EstopResetCheckStatus.FAIL, byId.getValue(EstopResetCheckId.MODEL).status)
        assertEquals(EstopResetCheckStatus.FAIL, byId.getValue(EstopResetCheckId.SCALER).status)
    }

    @Test
    fun `停止命令尚未发出时检查项是 WAITING 而不是 PASS`() {
        val checks = EstopResetPolicy.checks(
            good().copy(stopIssued = false, framesQueued = false),
        ).associateBy { it.id }
        assertEquals(
            EstopResetCheckStatus.WAITING,
            checks.getValue(EstopResetCheckId.STOP_COMMAND).status,
        )
    }
}
