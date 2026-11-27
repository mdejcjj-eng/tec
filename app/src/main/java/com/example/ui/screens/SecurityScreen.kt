package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.AppLockConfig
import com.example.data.DeviceEntity
import com.example.data.LocalDeviceConfig
import com.example.data.RemoteSession
import com.example.data.RoleMode
import com.example.data.SessionStatus
import com.example.data.UiState
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyanPulse
import com.example.ui.theme.EmeraldLink
import com.example.ui.theme.OutlineSubtle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityScreen(
    userEmail: String,
    currentUserId: String,
    localConfig: LocalDeviceConfig,
    appLockConfig: AppLockConfig,
    devicesState: UiState<List<DeviceEntity>>,
    sessionsState: UiState<List<RemoteSession>>,
    onSaveAppPin: suspend (String) -> Boolean,
    onVerifyAppPin: suspend (String) -> Boolean,
    onDisableAppLock: suspend (String) -> Boolean,
    onUpdateAppLockOptions: (Boolean?, Boolean?, Boolean?) -> Unit,
    onLockAppNow: () -> Unit,
    onUpdateDeviceName: (String) -> Unit,
    onChangeRoleMode: (RoleMode) -> Unit,
    onRevokeTrustedUid: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    onEndSession: (String) -> Unit,
    onSignOut: () -> Unit
) {
    var nameDraft by remember(localConfig.deviceName) { mutableStateOf(localConfig.deviceName) }
    var activePinDialog by remember { mutableStateOf<PinDialogMode?>(null) }

    val thisDevice = (devicesState as? UiState.Success)?.data?.firstOrNull { it.id == localConfig.deviceId }
    val externalTrustedUids = thisDevice?.trustedUids?.filter { it != currentUserId }.orEmpty()

    activePinDialog?.let { mode ->
        AppPinSetupDialog(
            mode = mode,
            onDismiss = { activePinDialog = null },
            onVerifyCurrentPin = onVerifyAppPin,
            onSaveNewPin = onSaveAppPin,
            onDisableLock = onDisableAppLock
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "app_lock_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (appLockConfig.isPinEnabled) EmeraldLink.copy(alpha = 0.55f) else OutlineSubtle,
                        MaterialTheme.shapes.large
                    )
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
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = if (appLockConfig.isPinEnabled) EmeraldLink else CyanPulse
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("App Lock & Touch Shield", style = MaterialTheme.typography.titleMedium)
                        }
                        StatusPill(
                            text = if (appLockConfig.isPinEnabled) "LOCKED" else "OFF",
                            active = appLockConfig.isPinEnabled
                        )
                    }
                    Text(
                        text = if (appLockConfig.isPinEnabled) {
                            "Only you can open or touch inside Screen Control using your PIN or phone lock/fingerprint."
                        } else {
                            "Protect Screen Control with a 4–6 digit PIN and phone fingerprint lock so nobody else can open or touch inside the app."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!appLockConfig.isPinEnabled) {
                        Button(
                            onClick = { activePinDialog = PinDialogMode.SET_NEW },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("setup_app_pin_button")
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Enable App Lock PIN")
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { activePinDialog = PinDialogMode.CHANGE_EXISTING },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("change_app_pin_button")
                            ) {
                                Text("Change PIN")
                            }
                            OutlinedButton(
                                onClick = { activePinDialog = PinDialogMode.DISABLE_EXISTING },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("disable_app_pin_button")
                            ) {
                                Text("Turn Off", color = CoralAlert)
                            }
                        }

                        Button(
                            onClick = onLockAppNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("lock_app_now_button")
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Lock App Now")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Lock When Leaving App", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Require unlock every time you switch back",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = appLockConfig.autoLockInBackground,
                                onCheckedChange = { onUpdateAppLockOptions(it, null, null) },
                                modifier = Modifier.testTag("auto_lock_bg_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Unlock with Phone Fingerprint / Lock", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Allow device biometric or screen lock to open",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = appLockConfig.allowDeviceUnlock,
                                onCheckedChange = { onUpdateAppLockOptions(null, it, null) },
                                modifier = Modifier.testTag("device_unlock_switch")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Privacy Shield (Hide Recents & Screenshots)", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Blocks local screenshots and hides preview in Recent Apps",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = appLockConfig.privacyScreenShield,
                            onCheckedChange = { onUpdateAppLockOptions(null, null, it) },
                            modifier = Modifier.testTag("privacy_shield_switch")
                        )
                    }
                }
            }
        }

        item(key = "identity_card") {
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
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = EmeraldLink)
                        Spacer(Modifier.width(8.dp))
                        Text("Account & Device Identity", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = userEmail.ifBlank { "Google Authenticated User" },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Device ID: ${localConfig.deviceId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it.take(60) },
                            label = { Text("Device Name") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("device_name_input")
                        )
                        Button(
                            onClick = { onUpdateDeviceName(nameDraft) },
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("save_device_name_button")
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Save device name", modifier = Modifier.size(18.dp))
                        }
                    }

                    Text("Operating Role", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RoleMode.entries.forEach { mode ->
                            FilterChip(
                                selected = localConfig.roleMode == mode,
                                onClick = { onChangeRoleMode(mode) },
                                label = { Text(mode.name) },
                                modifier = Modifier.testTag("role_chip_${mode.name}")
                            )
                        }
                    }
                }
            }
        }

        item(key = "trusted_access_card") {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = CyanPulse)
                        Spacer(Modifier.width(8.dp))
                        Text("External Trusted Accounts", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "Your own Google devices are trusted automatically. External paired accounts appear below and can be revoked anytime.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (externalTrustedUids.isEmpty()) {
                        Text(
                            text = "No external accounts paired.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        externalTrustedUids.forEach { uid ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "UID: ${uid.take(14)}...",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = { onRevokeTrustedUid(uid) },
                                    modifier = Modifier.testTag("revoke_uid_$uid")
                                ) {
                                    Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = CoralAlert, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Revoke", color = CoralAlert)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (sessionsState is UiState.Success && sessionsState.data.isNotEmpty()) {
            item(key = "session_audit_header") {
                Text("Session Audit Log", style = MaterialTheme.typography.titleMedium)
            }
            items(sessionsState.data.take(10), key = { it.id }) { session ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, OutlineSubtle, MaterialTheme.shapes.medium)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${session.controllerDeviceName} → ${session.hostDeviceName}",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = "Status: ${session.status} • Frames: ${session.frameSeq}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (session.status != SessionStatus.ENDED.name) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onSelectSession(session.id) }) {
                                    Text("Open")
                                }
                                OutlinedButton(onClick = { onEndSession(session.id) }) {
                                    Text("End", color = CoralAlert)
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "sign_out_card") {
            Button(
                onClick = onSignOut,
                colors = ButtonDefaults.buttonColors(containerColor = CoralAlert),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign Out of Google Account")
            }
        }
    }
}
