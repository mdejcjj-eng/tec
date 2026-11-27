package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.auth.FirebaseBootstrap
import com.example.data.AppLockConfig
import com.example.data.DevicePreferences
import com.example.data.LinkMirrorRepository
import com.example.ui.LinkMirrorApp
import com.example.ui.LinkMirrorViewModel
import com.example.ui.screens.AppLockGateScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyanPulse
import com.example.ui.theme.LinkMirrorTheme
import com.example.ui.theme.ObsidianBg
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialFirebaseReady = FirebaseBootstrap.ensureInitialized(applicationContext)
        setContent {
            LinkMirrorTheme {
                AppContent(
                    initialFirebaseReady = initialFirebaseReady,
                    onUpdateSecureFlag = { secure ->
                        if (secure) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AppContent(
    initialFirebaseReady: Boolean,
    onUpdateSecureFlag: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var firebaseReady by remember { mutableStateOf(initialFirebaseReady) }

    if (!firebaseReady) {
        FirebaseRecoveryScreen(
            onConfigured = { firebaseReady = true }
        )
        return
    }

    val preferences = remember(context) { DevicePreferences(context.applicationContext) }
    val appLockConfig by preferences.appLockConfigFlow.collectAsStateWithLifecycle(
        initialValue = AppLockConfig()
    )

    var initialLockEvaluated by rememberSaveable { mutableStateOf(false) }
    var isAppLocked by rememberSaveable { mutableStateOf(false) }
    var suppressNextBackgroundLock by remember { mutableStateOf(false) }

    LaunchedEffect(appLockConfig.isLoaded, appLockConfig.isPinEnabled) {
        if (appLockConfig.isLoaded) {
            if (!initialLockEvaluated) {
                initialLockEvaluated = true
                if (appLockConfig.isPinEnabled) {
                    isAppLocked = true
                }
            } else if (!appLockConfig.isPinEnabled && isAppLocked) {
                isAppLocked = false
            }
        }
    }

    LaunchedEffect(isAppLocked, appLockConfig.privacyScreenShield) {
        onUpdateSecureFlag(isAppLocked || appLockConfig.privacyScreenShield)
    }

    DisposableEffect(lifecycleOwner, appLockConfig.isPinEnabled, appLockConfig.autoLockInBackground) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP &&
                appLockConfig.isPinEnabled &&
                appLockConfig.autoLockInBackground
            ) {
                if (suppressNextBackgroundLock) {
                    suppressNextBackgroundLock = false
                } else {
                    isAppLocked = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    if (!appLockConfig.isLoaded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianBg),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = CyanPulse)
        }
        return
    }

    if (isAppLocked && appLockConfig.isPinEnabled) {
        AppLockGateScreen(
            allowDeviceUnlock = appLockConfig.allowDeviceUnlock,
            onVerifyPin = { pin -> preferences.verifyAppPin(pin) },
            onUnlockSuccess = {
                suppressNextBackgroundLock = false
                isAppLocked = false
            },
            onOpeningSystemPrompt = { opening ->
                suppressNextBackgroundLock = opening
            }
        )
        return
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(
            onAuthSuccess = {
                currentUser = Firebase.auth.currentUser
            }
        )
    } else {
        val uid = user.uid
        val email = user.email.orEmpty()
        key(uid) {
            val viewModel: LinkMirrorViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY]) {
                            "APPLICATION_KEY missing from CreationExtras"
                        }
                        val databaseId = app.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(databaseId)
                        val dm = app.resources.displayMetrics
                        LinkMirrorViewModel(
                            repository = LinkMirrorRepository(db),
                            preferences = DevicePreferences(app),
                            currentUserId = uid,
                            screenWidth = dm.widthPixels.toLong(),
                            screenHeight = dm.heightPixels.toLong()
                        )
                    }
                }
            )
            LinkMirrorApp(
                viewModel = viewModel,
                userEmail = email,
                onLockAppNow = {
                    if (appLockConfig.isPinEnabled) {
                        isAppLocked = true
                    }
                }
            )
        }
    }
}

@Composable
private fun FirebaseRecoveryScreen(
    onConfigured: () -> Unit
) {
    val context = LocalContext.current
    var jsonInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = ObsidianBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Firebase Setup Needed",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CyanPulse
            )
            Text(
                text = "This APK was built without app/google-services.json in the repository, so Firebase was not packaged into the build.\n\n" +
                    "To fix it permanently: add app/google-services.json (or GitHub Secret GOOGLE_SERVICES_JSON) to your repository and rebuild.\n\n" +
                    "Or paste the contents of app/google-services.json below to activate immediately:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = jsonInput,
                onValueChange = {
                    errorMsg = null
                    jsonInput = it
                },
                label = { Text("Paste google-services.json") },
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth()
            )
            if (!errorMsg.isNullOrBlank()) {
                Text(
                    text = errorMsg.orEmpty(),
                    color = CoralAlert,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val res = FirebaseBootstrap.initFromGoogleServicesJson(context, jsonInput)
                    if (res.isSuccess) {
                        onConfigured()
                    } else {
                        errorMsg = res.exceptionOrNull()?.localizedMessage ?: "Invalid google-services.json"
                    }
                },
                enabled = jsonInput.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("Activate & Continue")
            }
        }
    }
}
