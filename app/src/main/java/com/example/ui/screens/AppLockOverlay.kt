package com.example.ui.screens

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyanPulse
import com.example.ui.theme.EmeraldLink
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OutlineSubtle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppLockGateScreen(
    allowDeviceUnlock: Boolean,
    onVerifyPin: suspend (String) -> Boolean,
    onUnlockSuccess: () -> Unit,
    onOpeningSystemPrompt: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var cooldownSeconds by remember { mutableIntStateOf(0) }
    var isVerifying by remember { mutableStateOf(false) }

    LaunchedEffect(cooldownSeconds) {
        if (cooldownSeconds > 0) {
            delay(1_000L)
            cooldownSeconds -= 1
        }
    }

    val keyguardManager = remember(context) {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
    val canUseDeviceLock = remember(keyguardManager, allowDeviceUnlock) {
        allowDeviceUnlock && (keyguardManager?.isDeviceSecure == true)
    }

    val deviceCredentialLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onOpeningSystemPrompt(false)
        if (result.resultCode == Activity.RESULT_OK) {
            pinInput = ""
            errorMessage = null
            failedAttempts = 0
            onUnlockSuccess()
        }
    }

    val triggerDeviceUnlock = {
        try {
            @Suppress("DEPRECATION")
            val intent = keyguardManager?.createConfirmDeviceCredentialIntent(
                "Unlock Screen Control",
                "Confirm your phone lock or fingerprint to access the app"
            )
            if (intent != null) {
                onOpeningSystemPrompt(true)
                deviceCredentialLauncher.launch(intent)
            } else {
                errorMessage = "Set a screen lock in Phone Settings to use Phone Lock"
            }
        } catch (_: Exception) {
            onOpeningSystemPrompt(false)
            errorMessage = "Phone lock prompt unavailable — enter your App PIN"
        }
    }

    BackHandler(enabled = true) {
        (context as? Activity)?.moveTaskToBack(true)
    }

    val submitPin: () -> Unit = {
        if (cooldownSeconds <= 0 && !isVerifying && pinInput.length >= 4) {
            isVerifying = true
            scope.launch {
                val ok = onVerifyPin(pinInput)
                isVerifying = false
                if (ok) {
                    pinInput = ""
                    errorMessage = null
                    failedAttempts = 0
                    onUnlockSuccess()
                } else {
                    pinInput = ""
                    val nextFails = failedAttempts + 1
                    failedAttempts = nextFails
                    if (nextFails >= 5) {
                        cooldownSeconds = 30
                        failedAttempts = 0
                        errorMessage = "Too many wrong PINs. Wait 30s or use Phone Lock."
                    } else {
                        errorMessage = "Incorrect PIN (${5 - nextFails} attempts left)"
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ObsidianBg, ObsidianSurface, ObsidianBg)
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(CyanPulse.copy(alpha = 0.14f))
                    .border(1.5.dp, CyanPulse.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "App Locked",
                    tint = CyanPulse,
                    modifier = Modifier.size(34.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Screen Control Locked",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Enter your 4–6 digit App PIN to unlock access",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                for (i in 0 until 6) {
                    val filled = i < pinInput.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    errorMessage != null && !filled -> CoralAlert.copy(alpha = 0.25f)
                                    filled -> CyanPulse
                                    else -> ObsidianSurfaceHigh
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    errorMessage != null -> CoralAlert.copy(alpha = 0.7f)
                                    filled -> EmeraldLink
                                    else -> OutlineSubtle
                                },
                                CircleShape
                            )
                    )
                }
            }

            AnimatedContent(targetState = errorMessage ?: if (cooldownSeconds > 0) "Locked for ${cooldownSeconds}s" else "") { msg ->
                if (msg.isNotBlank()) {
                    Text(
                        text = if (cooldownSeconds > 0) "Too many wrong attempts — try again in ${cooldownSeconds}s" else msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = CoralAlert,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Spacer(Modifier.height(18.dp))
                }
            }

            val keypadRows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9")
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                keypadRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)
                    ) {
                        row.forEach { digit ->
                            PinPadButton(
                                label = digit,
                                enabled = cooldownSeconds <= 0 && pinInput.length < 6 && !isVerifying,
                                tag = "lock_key_$digit",
                                onClick = {
                                    errorMessage = null
                                    pinInput += digit
                                }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = {
                            errorMessage = null
                            if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1)
                        },
                        enabled = pinInput.isNotEmpty() && !isVerifying,
                        shape = CircleShape,
                        color = ObsidianSurfaceHigh,
                        modifier = Modifier
                            .size(66.dp)
                            .testTag("lock_key_backspace")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Backspace,
                                contentDescription = "Delete digit",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    PinPadButton(
                        label = "0",
                        enabled = cooldownSeconds <= 0 && pinInput.length < 6 && !isVerifying,
                        tag = "lock_key_0",
                        onClick = {
                            errorMessage = null
                            pinInput += "0"
                        }
                    )

                    Surface(
                        onClick = submitPin,
                        enabled = cooldownSeconds <= 0 && pinInput.length >= 4 && !isVerifying,
                        shape = CircleShape,
                        color = if (pinInput.length >= 4 && cooldownSeconds <= 0) EmeraldLink else ObsidianSurfaceHigh,
                        modifier = Modifier
                            .size(66.dp)
                            .testTag("lock_key_submit")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Unlock app",
                                tint = if (pinInput.length >= 4 && cooldownSeconds <= 0) ObsidianBg else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (canUseDeviceLock) {
                FilledTonalButton(
                    onClick = triggerDeviceUnlock,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("unlock_with_device_lock_button")
                ) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock with Fingerprint / Phone Lock")
                }
            }
        }
    }
}

@Composable
private fun PinPadButton(
    label: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = ObsidianSurfaceHigh,
        modifier = Modifier
            .size(66.dp)
            .border(1.dp, OutlineSubtle, CircleShape)
            .testTag(tag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

enum class PinDialogMode {
    SET_NEW,
    CHANGE_EXISTING,
    DISABLE_EXISTING
}

@Composable
fun AppPinSetupDialog(
    mode: PinDialogMode,
    onDismiss: () -> Unit,
    onVerifyCurrentPin: suspend (String) -> Boolean,
    onSaveNewPin: suspend (String) -> Boolean,
    onDisableLock: suspend (String) -> Boolean
) {
    val scope = rememberCoroutineScope()
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        icon = {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = if (mode == PinDialogMode.DISABLE_EXISTING) CoralAlert else CyanPulse
            )
        },
        title = {
            Text(
                when (mode) {
                    PinDialogMode.SET_NEW -> "Set App Lock PIN"
                    PinDialogMode.CHANGE_EXISTING -> "Change App Lock PIN"
                    PinDialogMode.DISABLE_EXISTING -> "Disable App Lock"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mode != PinDialogMode.SET_NEW) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { raw ->
                            errorText = null
                            currentPin = raw.filter { it.isDigit() }.take(6)
                        },
                        label = { Text("Current PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_current_pin_input")
                    )
                }

                if (mode != PinDialogMode.DISABLE_EXISTING) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { raw ->
                            errorText = null
                            newPin = raw.filter { it.isDigit() }.take(6)
                        },
                        label = { Text("New PIN (4–6 digits)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_new_pin_input")
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { raw ->
                            errorText = null
                            confirmPin = raw.filter { it.isDigit() }.take(6)
                        },
                        label = { Text("Confirm New PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_confirm_pin_input")
                    )
                }

                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = CoralAlert
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving,
                colors = if (mode == PinDialogMode.DISABLE_EXISTING) {
                    ButtonDefaults.buttonColors(containerColor = CoralAlert)
                } else {
                    ButtonDefaults.buttonColors()
                },
                onClick = {
                    scope.launch {
                        isSaving = true
                        try {
                            when (mode) {
                                PinDialogMode.SET_NEW -> {
                                    if (newPin.length < 4) {
                                        errorText = "Enter at least 4 digits"
                                    } else if (newPin != confirmPin) {
                                        errorText = "PINs do not match"
                                    } else if (onSaveNewPin(newPin)) {
                                        onDismiss()
                                    } else {
                                        errorText = "Failed to save PIN"
                                    }
                                }
                                PinDialogMode.CHANGE_EXISTING -> {
                                    if (!onVerifyCurrentPin(currentPin)) {
                                        errorText = "Current PIN is incorrect"
                                    } else if (newPin.length < 4) {
                                        errorText = "Enter at least 4 digits"
                                    } else if (newPin != confirmPin) {
                                        errorText = "New PINs do not match"
                                    } else if (onSaveNewPin(newPin)) {
                                        onDismiss()
                                    } else {
                                        errorText = "Failed to update PIN"
                                    }
                                }
                                PinDialogMode.DISABLE_EXISTING -> {
                                    if (onDisableLock(currentPin)) {
                                        onDismiss()
                                    } else {
                                        errorText = "Current PIN is incorrect"
                                    }
                                }
                            }
                        } finally {
                            isSaving = false
                        }
                    }
                },
                modifier = Modifier.testTag("dialog_pin_confirm_button")
            ) {
                Text(
                    when (mode) {
                        PinDialogMode.DISABLE_EXISTING -> "Disable Lock"
                        else -> "Save PIN"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
