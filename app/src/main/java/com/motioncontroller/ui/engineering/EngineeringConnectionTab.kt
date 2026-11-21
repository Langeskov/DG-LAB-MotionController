package com.motioncontroller.ui.engineering

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.motioncontroller.service.UiState
import com.motioncontroller.ui.QrCode
import com.motioncontroller.ui.components.CheckRow
import com.motioncontroller.ui.components.DataText
import com.motioncontroller.ui.components.EmptyHint
import com.motioncontroller.ui.components.KeyValueRow
import com.motioncontroller.ui.components.McButtonVariant
import com.motioncontroller.ui.components.McInputField
import com.motioncontroller.ui.components.McNotice
import com.motioncontroller.ui.components.StatusLamp
import com.motioncontroller.ui.components.TacticalButton
import com.motioncontroller.ui.components.WarningBanner
import com.motioncontroller.ui.i18n.fmt
import com.motioncontroller.ui.i18n.rememberUiStrings
import com.motioncontroller.ui.theme.McColors
import com.motioncontroller.ui.theme.McType

/**
 * CONNECTION：V4 中继绑定、DG-LAB APP 客户端地址、APP clients、郊狼设备、链路诊断。
 *
 * 客户端地址语义（见 relay/NetworkAddress）：UiState.address 永远是 APP 可访问的完整
 * `ws://host:port/?tid=...`；0.0.0.0 只是绑定地址，绝不能展示成客户端地址。
 */
@Composable
internal fun EngineeringConnectionTab(
    state: UiState,
    onCopyAddress: () -> Unit,
    onRestartRelay: (host: String, port: Int) -> Unit,
) {
    val strings = rememberUiStrings()
    val link = state.link
    val linkError = link.lastError
    val engineError = state.engineError
    val estopLatched = state.emergencyStopped || link.emergencyStopped

    var portText by rememberSaveable(state.port) { mutableStateOf(state.port.toString()) }
    var showQr by rememberSaveable { mutableStateOf(false) }

    val effectivePort = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: state.port
    val clientAddress = state.address.trim().takeIf { it.isNotEmpty() && !it.contains("0.0.0.0") }
    val addressAvailable = clientAddress != null
    val qrBitmap = remember(state.qrPayload) { QrCode.bitmap(state.qrPayload) }
    val qrImage = remember(qrBitmap) { qrBitmap?.asImageBitmap() }

    EngineeringTabScaffold {
        EngineeringPanel(title = strings.linkStatus) {
            CheckRow(
                label = strings.checkLocalService,
                statusText = when {
                    state.engineLoading -> strings.statusWaiting
                    state.serviceRunning -> strings.statusOnline
                    else -> strings.statusOffline
                },
                color = when {
                    state.engineLoading -> McColors.Warning
                    state.serviceRunning -> McColors.Success
                    else -> McColors.Warning
                },
                detail = if (state.engineLoading) strings.engineRestartInProgress else null,
            )
            CheckRow(
                label = strings.checkV4Relay,
                statusText = if (link.relayRunning) strings.statusListening else strings.statusOffline,
                color = availabilityColor(link.relayRunning),
                detail = "${state.bindHost}:${state.port}",
            )
            CheckRow(
                label = strings.checkDgLabApp,
                statusText = if (link.appConnected) strings.statusConnected else strings.statusWaiting,
                color = availabilityColor(link.appConnected),
                detail = fmt(strings.clientsCount, link.appClientIds.size),
            )
            CheckRow(
                label = strings.coyoteDevice,
                statusText = if (link.deviceReady) strings.statusReady else strings.statusWaiting,
                color = availabilityColor(link.deviceReady),
                detail = link.activeSlotId?.let { fmt(strings.activeSlotValue, it) },
            )
            CheckRow(
                label = strings.estopUi,
                statusText = if (estopLatched) strings.statusLatched else strings.statusClear,
                color = if (estopLatched) McColors.Danger else McColors.TextDim,
            )
        }

        EngineeringPanel(
            title = strings.serverEndpoint,
            accent = if (state.engineLoading) McColors.Warning else null,
        ) {
            KeyValueRow(strings.bindHost, state.bindHost.ifBlank { "--" })
            KeyValueRow(strings.bindPort, state.port.toString())
            KeyValueRow(
                label = strings.relayState,
                value = if (link.relayRunning) strings.statusListening else strings.statusStopped,
                valueColor = availabilityColor(link.relayRunning),
            )
            Spacer(Modifier.height(6.dp))
            McInputField(
                value = portText,
                onValueChange = { raw -> portText = raw.filter(Char::isDigit).take(5) },
                label = strings.portFieldLabel,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.engineLoading,
            )
            Spacer(Modifier.height(4.dp))
            DataText(
                text = fmt(strings.restartWillUsePort, effectivePort),
                color = if (effectivePort == state.port) McColors.TextDim else McColors.BlueAccent,
                style = McType.dataSmall,
            )
            Spacer(Modifier.height(8.dp))
            if (state.engineLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusLamp(color = McColors.Warning)
                    DataText(
                        text = strings.waitingRelayRestart,
                        color = McColors.Warning,
                        style = McType.dataSmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TacticalButton(
                    text = "127.0.0.1",
                    onClick = { onRestartRelay("127.0.0.1", effectivePort) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.engineLoading,
                    variant = McButtonVariant.OUTLINE,
                    subtitle = strings.sameDevice,
                    height = 48.dp,
                )
                TacticalButton(
                    text = "0.0.0.0",
                    onClick = { onRestartRelay("0.0.0.0", effectivePort) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.engineLoading,
                    variant = McButtonVariant.OUTLINE,
                    subtitle = strings.multiDevice,
                    height = 48.dp,
                )
            }
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.restartRelayTitle,
                body = strings.restartRelayBody,
            )
        }

        EngineeringPanel(title = strings.appConnectionAddress, accent = McColors.BlueAccent) {
            KeyValueRow(
                label = strings.targetId,
                value = link.targetId.ifBlank { "--" },
                valueColor = McColors.BlueAccent,
            )
            Spacer(Modifier.height(2.dp))
            KeyValueRow(
                label = strings.clientUrl,
                value = clientAddress ?: "--",
                valueColor = if (addressAvailable) McColors.TextPrimary else McColors.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TacticalButton(
                    text = strings.copy,
                    onClick = onCopyAddress,
                    modifier = Modifier.weight(1f),
                    enabled = addressAvailable,
                    variant = McButtonVariant.PRIMARY,
                    subtitle = strings.addressLabel,
                    height = 48.dp,
                )
                TacticalButton(
                    text = if (showQr) strings.hideQr else strings.showQr,
                    onClick = { showQr = !showQr },
                    modifier = Modifier.weight(1f),
                    enabled = state.qrPayload.isNotBlank(),
                    variant = McButtonVariant.OUTLINE,
                    subtitle = if (state.qrPayload.isNotBlank()) strings.pairingCode else strings.noPayload,
                    height = 48.dp,
                )
            }
            if (showQr) {
                Spacer(Modifier.height(10.dp))
                val image = qrImage
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = strings.qrContentDescription,
                        modifier = Modifier
                            .size(220.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                } else {
                    EmptyHint(
                        text = if (state.qrPayload.isBlank()) {
                            strings.qrPayloadEmpty
                        } else {
                            strings.qrGenerationFailed
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.addressSemanticsTitle,
                body = strings.addressSemanticsBody,
            )
        }

        EngineeringPanel(title = strings.appClients) {
            KeyValueRow(
                label = strings.clients,
                value = link.appClientIds.size.toString(),
                valueColor = availabilityColor(link.appConnected),
            )
            KeyValueRow(
                label = strings.activeSlot,
                value = link.activeSlotId ?: "--",
                valueColor = if (link.activeSlotId != null) McColors.SteelBlue else McColors.TextDim,
            )
            Spacer(Modifier.height(4.dp))
            if (link.appClientIds.isEmpty()) {
                EmptyHint(strings.noAppClient)
            } else {
                link.appClientIds.take(8).forEach { clientId ->
                    DataText(
                        text = "· $clientId",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        color = McColors.TextSecondary,
                        style = McType.dataSmall,
                    )
                }
                if (link.appClientIds.size > 8) {
                    DataText(
                        text = fmt(strings.moreClients, link.appClientIds.size - 8),
                        color = McColors.TextDim,
                        style = McType.dataSmall,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.routingTitle,
                body = strings.routingBody,
            )
        }

        EngineeringPanel(title = strings.devicesTitle) {
            if (link.devices.isEmpty()) {
                EmptyHint(strings.noDeviceSnapshot)
            } else {
                link.devices.forEach { device ->
                    CheckRow(
                        label = device.name.ifBlank { device.slotId },
                        statusText = if (device.connected) strings.statusConnected else strings.statusOffline,
                        color = if (device.connected) McColors.Success else McColors.TextDim,
                        detail = fmt(
                            strings.deviceDetail,
                            device.type.ifBlank { "?" },
                            device.slotId,
                        ),
                    )
                }
            }
        }

        EngineeringPanel(
            title = strings.linkDiagnostics,
            accent = if (linkError != null || engineError != null) McColors.Warning else null,
        ) {
            KeyValueRow(
                label = strings.lastOpReason,
                value = link.lastOpResponseReason ?: "--",
                valueColor = if (link.lastOpResponseReason != null) McColors.SteelBlue else McColors.TextDim,
            )
            KeyValueRow(
                label = strings.outboundQueue,
                value = link.queuedOutboundCount.toString(),
                valueColor = if (link.queuedOutboundCount > 0) McColors.Warning else McColors.TextSecondary,
            )
            KeyValueRow(
                label = strings.droppedOps,
                value = link.droppedOpCount.toString(),
                valueColor = if (link.droppedOpCount > 0L) McColors.Warning else McColors.TextSecondary,
            )
            KeyValueRow(
                label = strings.lastError,
                value = linkError ?: "--",
                valueColor = if (linkError != null) McColors.Warning else McColors.TextDim,
            )
            KeyValueRow(
                label = strings.engineErrorLabel,
                value = engineError ?: "--",
                valueColor = if (engineError != null) McColors.Warning else McColors.TextDim,
            )
            if (linkError != null) {
                Spacer(Modifier.height(8.dp))
                WarningBanner(title = strings.linkErrorTitle, body = linkError, color = McColors.Warning)
            }
            if (engineError != null) {
                Spacer(Modifier.height(8.dp))
                WarningBanner(title = strings.engineErrorLabel, body = engineError, color = McColors.Warning)
            }
            Spacer(Modifier.height(6.dp))
            McNotice(
                title = strings.backpressureTitle,
                body = strings.backpressureBody,
            )
        }
    }
}
