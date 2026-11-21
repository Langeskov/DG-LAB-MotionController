package com.motioncontroller.ui.model

import com.motioncontroller.dglab.DglabState
import com.motioncontroller.ml.MotionClass
import com.motioncontroller.ml.MotionPipelineStatus
import com.motioncontroller.service.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsAndConnectionModelTest {

    @Test
    fun `bind host 通配地址都归一为多机模式`() {
        assertEquals(ConnectionMode.SINGLE, ConnectionMode.ofBindHost("127.0.0.1"))
        assertEquals(ConnectionMode.SINGLE, ConnectionMode.ofBindHost("192.168.1.5"))
        assertEquals(ConnectionMode.MULTI, ConnectionMode.ofBindHost("0.0.0.0"))
        assertEquals(ConnectionMode.MULTI, ConnectionMode.ofBindHost("::"))
        assertEquals(ConnectionMode.MULTI, ConnectionMode.ofBindHost("[::]"))
        assertEquals(ConnectionMode.MULTI, ConnectionMode.ofBindHost("*"))
        assertEquals(ConnectionMode.MULTI, ConnectionMode.ofBindHost(""))
        assertTrue(ConnectionMode.MULTI.isLan)
        assertTrue(!ConnectionMode.SINGLE.isLan)
    }

    @Test
    fun `诊断报告包含关键分区且不崩溃`() {
        val state = UiState(
            serviceRunning = true,
            bindHost = "0.0.0.0",
            port = 9998,
            address = "ws://192.168.1.5:9998/?tid=abcd1234",
            link = DglabState(
                relayRunning = true,
                targetId = "abcd1234",
                targetIntensityA = 20,
                estimateIntensityA = 18,
                reportedIntensityA = 16,
                estopIssued = true,
                estopFramesQueued = true,
                estopAppAcked = true,
                estopAckReason = "cleared",
                queuedOutboundCount = 2,
                droppedOpCount = 3,
            ),
            pipeline = MotionPipelineStatus(
                running = true,
                modelLoaded = true,
                scalerValid = true,
                gpsAvailable = false,
                gpsEverFix = true,
                gpsFixAgeMs = 2_500L,
                bufferedFrames = 150,
                lastInferenceMs = 12,
            ),
            armed = true,
            emergencyStopped = true,
            stableClass = MotionClass.CAR.ordinal,
            effectiveClass = MotionClass.CYCLING.ordinal,
            manualClass = MotionClass.CYCLING.ordinal,
            targetIntensity = 18,
            logs = listOf("line-1", "line-2"),
        )
        val report = DiagnosticsText.build(state, appVersion = "1.0-test", nowMs = 1_700_000_000_000L)
        assertTrue(report.contains("MOTION CONTROLLER — DIAGNOSTIC REPORT"))
        assertTrue(report.contains("version   : 1.0-test"))
        assertTrue(report.contains("[LINK / DG-LAB V4]"))
        assertTrue(report.contains("[EMERGENCY STOP]"))
        assertTrue(report.contains("appAcked=true"))
        assertTrue(report.contains("deviceConfirmed=UNVERIFIED"))
        assertTrue(report.contains("mappingSource=MANUAL"))
        assertTrue(report.contains("[MAPPING]"))
        assertTrue(report.contains("[MOTION PIPELINE]"))
        assertTrue(report.contains("line-1"))
        assertTrue(report.contains("line-2"))
        assertTrue(report.contains("bind=0.0.0.0:9998"))
    }

    @Test
    fun `诊断报告在默认空状态也能生成`() {
        val report = DiagnosticsText.build(UiState(), appVersion = "1.0", nowMs = 0L)
        assertTrue(report.contains("serviceRunning=false"))
        assertTrue(report.contains("relayRunning=false"))
        assertTrue(report.contains("pipelineError=-"))
    }
}
