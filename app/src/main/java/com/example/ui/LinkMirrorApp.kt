package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.auth.signOutUser
import com.example.data.CommandType
import com.example.data.UiState
import com.example.service.ScreenCaptureBus
import com.example.service.ScreenShareService
import com.example.ui.screens.ControlScreen
import com.example.ui.screens.HostShareScreen
import com.example.ui.screens.PhotosScreen
import com.example.ui.screens.SecurityScreen
import com.example.ui.theme.CyanPulse
import com.example.ui.theme.EmeraldLink
import kotlinx.coroutines.launch

enum class LinkMirrorTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    CONTROL("Control", Icons.Rounded.Devices, Icons.Outlined.Devices, "tab_control"),
    HOST("Share Host", Icons.Rounded.ScreenShare, Icons.Outlined.ScreenShare, "tab_host"),
    PHOTOS("Photos", Icons.Rounded.PhotoLibrary, Icons.Outlined.PhotoLibrary, "tab_photos"),
    SECURITY("Security", Icons.Rounded.Security, Icons.Outlined.Security, "tab_security")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkMirrorApp(
    viewModel: LinkMirrorViewModel,
    userEmail: String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var currentTab by remember { mutableStateOf(LinkMirrorTab.CONTROL) }

    val localConfig by viewModel.localConfig.collectAsStateWithLifecycle()
    val devicesState by viewModel.trustedDevices.collectAsStateWithLifecycle()
    val sessionsState by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val recentCommands by viewModel.sessionCommands.collectAsStateWithLifecycle()
    val photosState by viewModel.sharedPhotos.collectAsStateWithLifecycle()
    val hostPairings by viewModel.hostPairings.collectAsStateWithLifecycle()
    val telemetry by viewModel.serviceTelemetry.collectAsStateWithLifecycle()
    val isBusy by viewModel.isActionBusy.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabled = ScreenCaptureBus.isAccessibilityServiceEnabled(context)
                viewModel.refreshAccessibilityStatus(enabled)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenShareService.start(context, result.resultCode, data)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Screen sharing permission was cancelled")
            }
        }
    }

    val launchScreenCapture: () -> Unit = {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        launchScreenCapture()
    }

    val requestStartScreenShare: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchScreenCapture()
        }
    }

    if (currentTab != LinkMirrorTab.CONTROL) {
        BackHandler { currentTab = LinkMirrorTab.CONTROL }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.img_launcher_art),
                                contentDescription = "Screen Control logo",
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .border(
                                        1.dp,
                                        Brush.linearGradient(listOf(CyanPulse, EmeraldLink)),
                                        RoundedCornerShape(9.dp)
                                    ),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Screen Control",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (telemetry.screenShareRunning || activeSession != null) EmeraldLink
                                        else CyanPulse
                                    )
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        LinkMirrorTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label, maxLines = 1) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        LinkMirrorTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                    ) {
                        when (currentTab) {
                            LinkMirrorTab.CONTROL -> ControlScreen(
                                localConfig = localConfig,
                                devicesState = devicesState,
                                activeSession = activeSession,
                                recentCommands = recentCommands,
                                isBusy = isBusy,
                                onClaimCode = { viewModel.claimPairingCode(it) },
                                onConnectDevice = { viewModel.connectToHostDevice(it) },
                                onSendCommand = { type, x, y, ex, ey ->
                                    viewModel.sendRemoteCommand(type, x, y, ex, ey)
                                },
                                onEndSession = { viewModel.endSession(it) },
                                onNavigateToPhotos = { currentTab = LinkMirrorTab.PHOTOS }
                            )
                            LinkMirrorTab.HOST -> HostShareScreen(
                                localConfig = localConfig,
                                telemetry = telemetry,
                                hostPairings = hostPairings,
                                incomingSessions = (sessionsState as? UiState.Success)?.data.orEmpty(),
                                isBusy = isBusy,
                                onToggleAlwaysAllow = { viewModel.setAlwaysAllowControl(it) },
                                onChangeStreamMode = { viewModel.setStreamMode(it) },
                                onStartScreenShare = requestStartScreenShare,
                                onStopScreenShare = { ScreenShareService.stop(context) },
                                onPublishPairingCode = { viewModel.publishCurrentPairingCode() },
                                onRotatePairingCode = { viewModel.generateAndPublishPairingCode() },
                                onApprovePairing = { viewModel.approveClaimedPairing(it) },
                                onApproveSession = { viewModel.approveOrResumeSession(it) },
                                onEndSession = { viewModel.endSession(it) }
                            )
                            LinkMirrorTab.PHOTOS -> PhotosScreen(
                                activeSession = activeSession,
                                photosState = photosState,
                                isBusy = isBusy,
                                onSharePhotoBase64 = { title, b64 ->
                                    viewModel.sharePhotoBase64(title, b64)
                                },
                                onCaptureRemoteSnapshot = {
                                    viewModel.sendRemoteCommand(CommandType.CAPTURE_SNAPSHOT)
                                }
                            )
                            LinkMirrorTab.SECURITY -> SecurityScreen(
                                userEmail = userEmail,
                                currentUserId = viewModel.currentUserId,
                                localConfig = localConfig,
                                devicesState = devicesState,
                                sessionsState = sessionsState,
                                onUpdateDeviceName = { viewModel.updateDeviceName(it) },
                                onChangeRoleMode = { viewModel.setRoleMode(it) },
                                onRevokeTrustedUid = { viewModel.revokeTrustedController(it) },
                                onSelectSession = { sid ->
                                    viewModel.selectSession(sid)
                                    currentTab = LinkMirrorTab.CONTROL
                                },
                                onEndSession = { viewModel.endSession(it) },
                                onSignOut = {
                                    ScreenShareService.stop(context)
                                    signOutUser(credentialManager, scope)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
