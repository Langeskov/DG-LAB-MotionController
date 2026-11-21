package com.motioncontroller.ui.model

import com.motioncontroller.ml.MotionClass
import com.motioncontroller.service.UiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 工程诊断导出文本（复制日志 / 导出诊断共用）。
 *
 * 只读取真实运行状态，不生成任何虚构数值；时间使用设备本地时钟。
 */
object DiagnosticsText {

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun build(state: UiState, appVersion: String, nowMs: Long = System.currentTimeMillis()): String {
        val time = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).format(stamp)
        val link = state.link
        val pipeline = state.pipeline
        return buildString {
            appendLine("MOTION CONTROLLER — DIAGNOSTIC REPORT")
            appendLine("generated : $time")
            appendLine("version   : $appVersion")
            appendLine()
            appendLine("[ENGINE]")
            appendLine("serviceRunning=${state.serviceRunning} engineLoading=${state.engineLoading}")
            appendLine("engineError=${state.engineError ?: "-"}")
            appendLine("bind=${state.bindHost}:${state.port} address=${state.address.ifBlank { "-" }}")
            appendLine()
            appendLine("[LINK / DG-LAB V4]")
            appendLine("relayRunning=${link.relayRunning} targetId=${link.targetId.ifBlank { "-" }}")
            appendLine("apps=${link.appClientIds.size} activeSlot=${link.activeSlotId ?: "-"}")
            appendLine("devices=${link.devices.joinToString { "${it.name.ifBlank { it.slotId }}(${it.type},connected=${it.connected})" }}")
            appendLine("intensity targetA=${link.targetIntensityA} targetB=${link.targetIntensityB}")
            appendLine("intensity estimateA=${link.estimateIntensityA} estimateB=${link.estimateIntensityB}")
            appendLine("intensity reportedA=${link.reportedIntensityA ?: "-"} reportedB=${link.reportedIntensityB ?: "-"}")
            appendLine("lastOpReason=${link.lastOpResponseReason ?: "-"} lastError=${link.lastError ?: "-"}")
            appendLine("outboundQueue=${link.queuedOutboundCount} droppedOps=${link.droppedOpCount}")
            appendLine()
            appendLine("[EMERGENCY STOP]")
            appendLine("uiLatched=${state.emergencyStopped} controllerLatched=${link.emergencyStopped}")
            appendLine("estopIssued=${link.estopIssued} framesQueued=${link.estopFramesQueued}")
            appendLine("appAcked=${link.estopAppAcked} ackReason=${link.estopAckReason ?: "-"}")
            appendLine("deviceConfirmed=UNVERIFIED (no BLE ack channel)")
            appendLine()
            appendLine("[CONTROL]")
            appendLine("armed=${state.armed} phase=${ControlModel.derive(state).phase}")
            appendLine(
                "mappingSource=${if (state.manualClass != null) "MANUAL" else "AUTO"} " +
                    "manualClass=${state.manualClass?.let { MotionClass.of(it)?.name } ?: "-"}",
            )
            appendLine("modelStable=${MotionClass.of(state.stableClass)?.name ?: "-"} effectiveClass=${MotionClass.of(state.effectiveClass)?.name ?: "-"}")
            appendLine("targetIntensity=${state.targetIntensity} currentIntensity=${state.currentIntensity}")
            appendLine("recognitionPaused=${state.recognitionPaused} restOverride=${state.restOverrideActive}")
            appendLine("predictionAgeMs=${state.predictionAgeMs} prediction=${state.prediction?.motionClass?.name ?: "-"}")
            appendLine()
            appendLine("[MAPPING]")
            appendLine("classBase=${state.mapping.classBase} manualOffset=${state.manualOffset} safetyMax=${state.mapping.safetyMax}")
            appendLine(
                "switchWindows=${state.mapping.switchWindows} probEma=${state.mapping.probEma} " +
                    "slewUp=${state.mapping.slewPerSecond}/s slewDown=${state.mapping.slewDownPerSecond}/s",
            )
            appendLine("restOverrideEnabled=${state.mapping.restOverrideEnabled} restWindows=${state.mapping.restWindows}")
            appendLine()
            appendLine("[MOTION PIPELINE]")
            appendLine("running=${pipeline.running} modelLoaded=${pipeline.modelLoaded} scalerValid=${pipeline.scalerValid}")
            appendLine("gyroAvailable=${pipeline.gyroAvailable} rotationAvailable=${pipeline.rotationAvailable}")
            appendLine("bufferedFrames=${pipeline.bufferedFrames} lastInferenceMs=${pipeline.lastInferenceMs}")
            appendLine(
                "gnss available=${pipeline.gpsAvailable} everFix=${pipeline.gpsEverFix} " +
                    "speedKmh=${pipeline.gpsSpeedKmh} fixAgeMs=${pipeline.gpsFixAgeMs}",
            )
            appendLine("pipelineError=${pipeline.lastError ?: "-"}")
            appendLine()
            appendLine("[RECENT LOGS]")
            state.logs.takeLast(80).forEach { appendLine(it) }
        }
    }
}
