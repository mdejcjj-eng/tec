package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.CommandType
import com.example.data.DeviceEntity
import com.example.data.LocalDeviceConfig
import com.example.data.RemoteCommand
import com.example.data.RemoteSession
import com.example.data.SessionStatus
import com.example.data.UiState
import com.example.service.ImageCodec
import com.example.ui.theme.AmberTrust
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyanPulse
import com.example.ui.theme.EmeraldLink
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.OutlineSubtle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ControlScreen(
    localConfig: LocalDeviceConfig,
    devicesState: UiState<List<DeviceEntity>>,
    activeSession: RemoteSession?,
    recentCommands: List<RemoteCommand>,
    isBusy: Boolean,
    onClaimCode: (String) -> Unit,
    onConnectDevice: (DeviceEntity) -> Unit,
    onSendCommand: (CommandType, Double, Double, Double, Double) -> Unit,
    onEndSession: (String) -> Unit,
    onNavigateToPhotos: () -> Unit
) {
    var pairingInput by remember { mutableStateOf("") }
    var touchControlEnabled by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }

    if (isFullscreen && activeSession != null) {
        BackHandler { isFullscreen = false }
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianBg)
                    .systemBarsPadding(),
                color = ObsidianBg
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SessionHeaderBar(
                        session = activeSession,
                        touchControlEnabled = touchControlEnabled,
                        isFullscreen = true,
                        onToggleTouch = { touchControlEnabled = !touchControlEnabled },
                        onToggleFullscreen = { isFullscreen = false },
                        onEndSession = {
                            isFullscreen = false
                            onEndSession(activeSession.id)
                        }
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        RemoteMirrorCanvas(
                            session = activeSession,
                            touchControlEnabled = touchControlEnabled,
                            onSendCommand = onSendCommand,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    RemoteActionDock(
                        remoteControlEnabled = activeSession.remoteControlEnabled && touchControlEnabled,
                        onSendCommand = onSendCommand,
                        onOpenPhotos = {
                            isFullscreen = false
                            onNavigateToPhotos()
                        }
                    )
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (activeSession != null && activeSession.status != SessionStatus.ENDED.name) {
            item(key = "active_viewer") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CyanPulse.copy(alpha = 0.45f), MaterialTheme.shapes.large)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SessionHeaderBar(
                            session = activeSession,
                            touchControlEnabled = touchControlEnabled,
                            isFullscreen = false,
                            onToggleTouch = { touchControlEnabled = !touchControlEnabled },
                            onToggleFullscreen = { isFullscreen = true },
                            onEndSession = { onEndSession(activeSession.id) }
                        )

                        RemoteMirrorCanvas(
                            session = activeSession,
                            touchControlEnabled = touchControlEnabled,
                            onSendCommand = onSendCommand,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp)
                        )

                        RemoteActionDock(
                            remoteControlEnabled = activeSession.remoteControlEnabled && touchControlEnabled,
                            onSendCommand = onSendCommand,
                            onOpenPhotos = onNavigateToPhotos
                        )

                        val lastCmd = recentCommands.lastOrNull()
                        if (lastCmd != null) {
                            Text(
                                text = "Last command: ${lastCmd.type} (${lastCmd.status})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item(key = "pair_by_code") {
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
                        Icon(
                            imageVector = Icons.Rounded.AddLink,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Pair Another Phone (6-Digit Code)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        text = "Enter the 6-digit code shown on Phone 2's Share Host tab to link and allow remote access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = pairingInput,
                            onValueChange = { raw ->
                                pairingInput = raw.filter { it.isDigit() }.take(6)
                            },
                            label = { Text("6-Digit Code") },
                            placeholder = { Text("e.g. 482910") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pairing_code_input")
                        )
                        Button(
                            onClick = {
                                onClaimCode(pairingInput)
                                pairingInput = ""
                            },
                            enabled = pairingInput.length == 6 && !isBusy,
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("pair_device_button")
                        ) {
                            Text("Pair")
                        }
                    }
                }
            }
        }

        item(key = "trusted_devices_header") {
            Text(
                text = "Trusted Target Devices",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        when (devicesState) {
            is UiState.Loading -> {
                item(key = "devices_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            is UiState.Error -> {
                item(key = "devices_error") {
                    Surface(
                        color = CoralAlert.copy(alpha = 0.14f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = devicesState.message,
                            color = CoralAlert,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
            is UiState.Success -> {
                val remoteDevices = devicesState.data.filter { it.id != localConfig.deviceId }
                if (remoteDevices.isEmpty()) {
                    item(key = "devices_empty") {
                        EmptyDevicesCard()
                    }
                } else {
                    items(remoteDevices, key = { it.id }) { device ->
                        TrustedDeviceCard(
                            device = device,
                            isCurrentlyConnected = activeSession?.hostDeviceId == device.id &&
                                activeSession.status != SessionStatus.ENDED.name,
                            isBusy = isBusy,
                            onConnect = { onConnectDevice(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHeaderBar(
    session: RemoteSession,
    touchControlEnabled: Boolean,
    isFullscreen: Boolean,
    onToggleTouch: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onEndSession: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = when (SessionStatus.fromRaw(session.status)) {
                    SessionStatus.ACTIVE -> EmeraldLink
                    SessionStatus.REQUESTED -> AmberTrust
                    else -> CoralAlert
                }
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = session.hostDeviceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = if (session.status == SessionStatus.ACTIVE.name) {
                    "Live • Frame #${session.frameSeq} • ${if (session.remoteControlEnabled) "Remote Touch Ready" else "View Only"}"
                } else {
                    "Status: ${session.status} — waiting for host stream"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onToggleTouch,
                modifier = Modifier.testTag("toggle_touch_control_button")
            ) {
                Icon(
                    imageVector = if (touchControlEnabled) Icons.Rounded.TouchApp else Icons.Rounded.Visibility,
                    contentDescription = if (touchControlEnabled) "Interactive control enabled" else "View only mode",
                    tint = if (touchControlEnabled) CyanPulse else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier.testTag("toggle_fullscreen_button")
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    contentDescription = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen"
                )
            }
            IconButton(
                onClick = onEndSession,
                modifier = Modifier.testTag("end_session_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Disconnect session",
                    tint = CoralAlert
                )
            }
        }
    }
}

@Composable
private fun RemoteMirrorCanvas(
    session: RemoteSession,
    touchControlEnabled: Boolean,
    onSendCommand: (CommandType, Double, Double, Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val frameBitmap by produceState<Bitmap?>(initialValue = null, session.latestFrameBase64) {
        value = withContext(Dispatchers.Default) {
            ImageCodec.decodeBase64ToBitmap(session.latestFrameBase64)
        }
    }

    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    var lastTouchNorm by remember { mutableStateOf<Offset?>(null) }
    var dragEndNorm by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(lastTouchNorm) {
        if (lastTouchNorm != null) {
            delay(550L)
            lastTouchNorm = null
            dragEndNorm = null
        }
    }

    val aspect = remember(session.frameWidth, session.frameHeight) {
        if (session.frameWidth > 0 && session.frameHeight > 0) {
            (session.frameWidth.toFloat() / session.frameHeight.toFloat()).coerceIn(0.35f, 1.8f)
        } else {
            9f / 19.5f
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ObsidianBg)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (frameBitmap != null) {
            Box(
                modifier = Modifier
                    .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(session.id, touchControlEnabled) {
                        if (!touchControlEnabled) return@pointerInput
                        detectTapGestures(
                            onTap = { pos ->
                                val nx = (pos.x / canvasSize.width.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
                                val ny = (pos.y / canvasSize.height.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
                                lastTouchNorm = Offset(nx.toFloat(), ny.toFloat())
                                onSendCommand(CommandType.TAP, nx, ny, nx, ny)
                            },
                            onDoubleTap = { pos ->
                                val nx = (pos.x / canvasSize.width.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
                                val ny = (pos.y / canvasSize.height.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
                                lastTouchNorm = Offset(nx.toFloat(), ny.toFloat())
                                onSendCommand(CommandType.DOUBLE_TAP, nx, ny, nx, ny)
                            },
                            onLongPress = { pos ->
                                val nx = (pos.x / canvasSize.width.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
                                val ny = (pos.y / canvasSize.height.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
                                lastTouchNorm = Offset(nx.toFloat(), ny.toFloat())
                                onSendCommand(CommandType.LONG_PRESS, nx, ny, nx, ny)
                            }
                        )
                    }
                    .pointerInput(session.id, touchControlEnabled) {
                        if (!touchControlEnabled) return@pointerInput
                        var startPos = Offset.Zero
                        var endPos = Offset.Zero
                        detectDragGestures(
                            onDragStart = { start ->
                                startPos = start
                                endPos = start
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                endPos = change.position
                            },
                            onDragEnd = {
                                val w = canvasSize.width.coerceAtLeast(1).toFloat()
                                val h = canvasSize.height.coerceAtLeast(1).toFloat()
                                val sx = (startPos.x / w).toDouble().coerceIn(0.0, 1.0)
                                val sy = (startPos.y / h).toDouble().coerceIn(0.0, 1.0)
                                val ex = (endPos.x / w).toDouble().coerceIn(0.0, 1.0)
                                val ey = (endPos.y / h).toDouble().coerceIn(0.0, 1.0)
                                lastTouchNorm = Offset(sx.toFloat(), sy.toFloat())
                                dragEndNorm = Offset(ex.toFloat(), ey.toFloat())
                                onSendCommand(CommandType.SWIPE, sx, sy, ex, ey)
                            }
                        )
                    }
            ) {
                Image(
                    bitmap = frameBitmap!!.asImageBitmap(),
                    contentDescription = "Live mirrored screen of ${session.hostDeviceName}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val start = lastTouchNorm
                    if (start != null) {
                        val p1 = Offset(start.x * size.width, start.y * size.height)
                        drawCircle(
                            color = CyanPulse.copy(alpha = 0.35f),
                            radius = 28f,
                            center = p1
                        )
                        drawCircle(
                            color = CyanPulse,
                            radius = 28f,
                            center = p1,
                            style = Stroke(width = 3f)
                        )
                        val end = dragEndNorm
                        if (end != null && (end != start)) {
                            val p2 = Offset(end.x * size.width, end.y * size.height)
                            drawLine(
                                color = EmeraldLink,
                                start = p1,
                                end = p2,
                                strokeWidth = 5f
                            )
                            drawCircle(
                                color = EmeraldLink,
                                radius = 14f,
                                center = p2
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = CyanPulse)
                Text(
                    text = "Waiting for live frames from ${session.hostDeviceName}...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = { onSendCommand(CommandType.WAKE_STREAM, 0.5, 0.5, 0.5, 0.5) },
                    modifier = Modifier.testTag("wake_stream_button")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Request Live Frame")
                }
            }
        }
    }
}

@Composable
private fun RemoteActionDock(
    remoteControlEnabled: Boolean,
    onSendCommand: (CommandType, Double, Double, Double, Double) -> Unit,
    onOpenPhotos: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(visible = !remoteControlEnabled) {
            Text(
                text = "View-Only Mode (enable Touch Control & Host Accessibility for gestures)",
                style = MaterialTheme.typography.labelSmall,
                color = AmberTrust
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                DockActionChip(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    label = "Back",
                    enabled = remoteControlEnabled,
                    tag = "cmd_back",
                    onClick = { onSendCommand(CommandType.BACK, 0.5, 0.5, 0.5, 0.5) }
                )
            }
            item {
                DockActionChip(
                    icon = Icons.Rounded.Home,
                    label = "Home",
                    enabled = remoteControlEnabled,
                    tag = "cmd_home",
                    onClick = { onSendCommand(CommandType.HOME, 0.5, 0.5, 0.5, 0.5) }
                )
            }
            item {
                DockActionChip(
                    icon = Icons.Rounded.Apps,
                    label = "Recents",
                    enabled = remoteControlEnabled,
                    tag = "cmd_recents",
                    onClick = { onSendCommand(CommandType.RECENTS, 0.5, 0.5, 0.5, 0.5) }
                )
            }
            item {
                DockActionChip(
                    icon = Icons.Rounded.Notifications,
                    label = "Shade",
                    enabled = remoteControlEnabled,
                    tag = "cmd_notifications",
                    onClick = { onSendCommand(CommandType.NOTIFICATIONS, 0.5, 0.5, 0.5, 0.5) }
                )
            }
            item {
                DockActionChip(
                    icon = Icons.Rounded.KeyboardArrowUp,
                    label = "Scroll Up",
                    enabled = remoteControlEnabled,
                    tag = "cmd_scroll_up",
                    onClick = { onSendCommand(CommandType.SCROLL_UP, 0.5, 0.7, 0.5, 0.3) }
                )
            }
            item {
                DockActionChip(
                    icon = Icons.Rounded.KeyboardArrowDown,
                    label = "Scroll Dn",
                    enabled = remoteControlEnabled,
                    tag = "cmd_scroll_down",
                    onClick = { onSendCommand(CommandType.SCROLL_DOWN, 0.5, 0.3, 0.5, 0.7) }
                )
            }
            item {
                DockActionChip(
                    icon = Icons.Rounded.CameraAlt,
                    label = "Snapshot",
                    enabled = true,
                    tag = "cmd_snapshot",
                    onClick = { onSendCommand(CommandType.CAPTURE_SNAPSHOT, 0.5, 0.5, 0.5, 0.5) }
                )
            }
            item {
                DockActionChip(
                    icon = Icons.Rounded.PhoneAndroid,
                    label = "Photos",
                    enabled = true,
                    tag = "cmd_open_photos",
                    onClick = onOpenPhotos
                )
            }
        }
    }
}

@Composable
private fun DockActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier
            .height(48.dp)
            .testTag(tag)
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TrustedDeviceCard(
    device: DeviceEntity,
    isCurrentlyConnected: Boolean,
    isBusy: Boolean,
    onConnect: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (device.screenShareActive) EmeraldLink.copy(alpha = 0.55f) else OutlineSubtle,
                MaterialTheme.shapes.large
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (device.screenShareActive) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhoneAndroid,
                            contentDescription = null,
                            tint = if (device.screenShareActive) EmeraldLink else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = device.deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${device.screenWidth}×${device.screenHeight} • Code ${device.pairingCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                StatusPill(
                    text = if (device.screenShareActive) "LIVE HOST" else "STANDBY",
                    active = device.screenShareActive
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (device.alwaysAllowControl) {
                    StatusPill(text = "ALWAYS ALLOW", active = true)
                }
                if (device.accessibilityActive) {
                    StatusPill(text = "TOUCH CONTROL", active = true)
                }
            }

            Button(
                onClick = onConnect,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("connect_device_${device.id}"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCurrentlyConnected) EmeraldLink else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (device.alwaysAllowControl) Icons.Rounded.LockOpen else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        isCurrentlyConnected -> "Connected • Viewing Live"
                        device.alwaysAllowControl -> "Connect & View Anytime"
                        else -> "Request Live View"
                    }
                )
            }
        }
    }
}

@Composable
private fun EmptyDevicesCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OutlineSubtle, MaterialTheme.shapes.large)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_devices),
                contentDescription = "No paired remote devices yet",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
            Text(
                text = "No Remote Devices Linked Yet",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Install LinkMirror on Phone 2 and sign in with the same Google account, or enter Phone 2's 6-digit pairing code above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusPill(text: String, active: Boolean) {
    Surface(
        color = if (active) EmeraldLink.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) EmeraldLink else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
