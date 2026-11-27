package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.AppLockConfig
import com.example.data.DevicePreferences
import com.example.data.LinkMirrorRepository
import com.example.ui.LinkMirrorApp
import com.example.ui.LinkMirrorViewModel
import com.example.ui.screens.AppLockGateScreen
import com.example.ui.screens.AuthScreen
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
        setContent {
            LinkMirrorTheme {
                AppContent(
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
    onUpdateSecureFlag: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
