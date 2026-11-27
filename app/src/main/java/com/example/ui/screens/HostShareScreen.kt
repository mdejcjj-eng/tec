package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.LocalDeviceConfig
import com.example.data.PairingRecord
import com.example.data.RemoteSession
import com.example.data.SessionStatus
import com.example.service.HostServiceTelemetry
import com.example.ui.theme.AmberTrust
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyanPulse
import com.example.ui.theme.EmeraldLink
import com.example.ui.theme.OutlineSubtle

@Composable
fun HostShareScreen(
    localConfig: LocalDeviceConfig,
    telemetry: HostServiceTelemetry,
    hostPairings: List<PairingRecord>,
    incomingSessions: List<RemoteSession>,
    isBusy: Boolean,
    onToggleAlwaysAllow: (Boolean) -> Unit,
    onChangeStreamMode: (com.example.data.StreamMode) -> Unit,
    onStartScreenShare: () -> Unit,
    onStopScreenShare: () -> Unit,
    onPublishPairingCode: () -> Unit,
    onRotatePairingCode: () -> Unit,
    onApprovePairing: (PairingRecord) -> Unit,
    onApproveSession: (String) -> Unit,
    onEndSession: (String) -> Unit
) {
    val context = LocalContext.current
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.AccessibilityNew,
                    contentDescription = null,
                    tint = CyanPulse
                )
            },
            title = { Text("Enable Remote Touch Control") },
            text = {
                Text(
                    "LinkMirror uses Android Accessibility Service solely to execute taps, swipes, and Back/Home/Recents gestures sent by your authorized trusted controller.\n\n" +
                        "• Screen window content is NOT read by the accessibility service.\n" +
                        "• You can turn off remote control or revoke trusted devices anytime."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityDisclosure = false
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.testTag("confirm_accessibility_settings_button")
                ) {
                    Text("Open Accessibility Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "always_allow_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (localConfig.alwaysAllowControl) EmeraldLink.copy(alpha = 0.55f) else OutlineSubtle,
                        MaterialTheme.shapes.large
                    )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (localConfig.alwaysAllowControl) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LockOpen,
                                contentDescription = null,
                                tint = if (localConfig.alwaysAllowControl) EmeraldLink else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Always Allow Trusted Control",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Once allowed, your trusted Phone 1 can connect and view live anytime without waiting for per-request approval.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = localConfig.alwaysAllowControl,
                        onCheckedChange = onToggleAlwaysAllow,
                        modifier = Modifier.testTag("always_allow_switch")
                    )
                }
            }
        }

        item(key = "screen_share_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.ScreenShare,
                                contentDescription = null,
                                tint = if (telemetry.screenShareRunning) EmeraldLink else CyanPulse
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Live Screen Broadcast",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        StatusPill(
                            text = if (telemetry.screenShareRunning) "ARMED & LIVE" else "STOPPED",
                            active = telemetry.screenShareRunning
                        )
                    }

                    Text(
                        text = if (telemetry.screenShareRunning) {
                            "Foreground broadcast active • ${telemetry.framesSent} frames streamed • ${telemetry.lastActionLabel}"
                        } else {
                            "Start the screen sharing service on Phone 2 so your trusted controller can view this screen anytime."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (telemetry.lastError != null) {
                        Text(
                            text = telemetry.lastError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CoralAlert
                        )
                    }

                    if (telemetry.screenShareRunning) {
                        Button(
                            onClick = onStopScreenShare,
                            colors = ButtonDefaults.buttonColors(containerColor = CoralAlert),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("stop_screen_share_button")
                        ) {
                            Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Stop Screen Sharing")
                        }
                    } else {
                        Button(
                            onClick = onStartScreenShare,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("start_screen_share_button")
                        ) {
                            Icon(Icons.Rounded.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Arm & Start Screen Sharing")
                        }
                    }
                }
            }
        }

        item(key = "stream_mode_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Stream Speed & Smart Data Saver",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Skips identical screen frames automatically. Balanced is recommended for smooth control and long daily usage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        com.example.data.StreamMode.entries.forEach { mode ->
                            val selected = localConfig.streamMode == mode
                            androidx.compose.material3.FilterChip(
                                selected = selected,
                                onClick = { onChangeStreamMode(mode) },
                                label = {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(mode.title, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = when (mode) {
                                                com.example.data.StreamMode.ECO -> "6–8h/day"
                                                com.example.data.StreamMode.BALANCED -> "Smart"
                                                com.example.data.StreamMode.SMOOTH -> "Fastest"
                                            },
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("stream_mode_${mode.name}")
                            )
                        }
                    }
                }
            }
        }

        item(key = "accessibility_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.AccessibilityNew,
                                contentDescription = null,
                                tint = if (telemetry.accessibilityConnected) EmeraldLink else AmberTrust
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Remote Touch Control",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        StatusPill(
                            text = if (telemetry.accessibilityConnected) "ACTIVE" else "SETUP NEEDED",
                            active = telemetry.accessibilityConnected
                        )
                    }

                    Text(
                        text = if (telemetry.accessibilityConnected) {
                            "Accessibility gesture service is active. Executed ${telemetry.commandsExecuted} remote action(s)."
                        } else {
                            "Turn on 'LinkMirror Remote Control' in Android Accessibility Settings to let Phone 1 tap, swipe, and press Back/Home remotely."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilledTonalButton(
                        onClick = { showAccessibilityDisclosure = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_accessibility_button")
                    ) {
                        Icon(
                            imageVector = if (telemetry.accessibilityConnected) Icons.Rounded.CheckCircle else Icons.Rounded.AccessibilityNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (telemetry.accessibilityConnected) "Manage Accessibility Permission"
                            else "Enable Remote Touch Control"
                        )
                    }
                }
            }
        }

        item(key = "pairing_code_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, contentDescription = null, tint = CyanPulse)
                        Spacer(Modifier.width(8.dp))
                        Text("6-Digit Pairing Code", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "Publish this code so another phone (even on a different Google account) can pair with ${localConfig.deviceName}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = localConfig.pairingCode.chunked(3).joinToString("  "),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = CyanPulse,
                            modifier = Modifier.padding(vertical = 16.dp, horizontal = 20.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onPublishPairingCode,
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("publish_code_button")
                        ) {
                            Text("Publish Code")
                        }
                        OutlinedButton(
                            onClick = onRotatePairingCode,
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("rotate_code_button")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("New Code")
                        }
                    }
                }
            }
        }

        val claimed = hostPairings.filter { it.status == "CLAIMED" && it.controllerUid.isNotBlank() }
        if (claimed.isNotEmpty()) {
            item(key = "claimed_pairings_title") {
                Text("Pairing Approvals", style = MaterialTheme.typography.titleMedium)
            }
            items(claimed, key = { it.id }) { record ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CyanPulse.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = record.controllerDeviceName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Claimed code ${record.id}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { onApprovePairing(record) },
                            enabled = !isBusy,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("approve_pairing_${record.id}")
                        ) {
                            Text("Grant Trust")
                        }
                    }
                }
            }
        }

        val pendingHostSessions = incomingSessions.filter {
            it.hostDeviceId == localConfig.deviceId && it.status == SessionStatus.REQUESTED.name
        }
        if (pendingHostSessions.isNotEmpty()) {
            item(key = "pending_sessions_title") {
                Text("Incoming Connection Requests", style = MaterialTheme.typography.titleMedium)
            }
            items(pendingHostSessions, key = { it.id }) { session ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AmberTrust.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(session.controllerDeviceName, style = MaterialTheme.typography.titleMedium)
                            Text("Wants to view & control this device", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onEndSession(session.id) }) { Text("Deny") }
                            Button(onClick = { onApproveSession(session.id) }) { Text("Allow") }
                        }
                    }
                }
            }
        }
    }
}
