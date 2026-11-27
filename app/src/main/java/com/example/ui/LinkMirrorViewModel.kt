package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppLockConfig
import com.example.data.CommandType
import com.example.data.DeviceEntity
import com.example.data.DevicePreferences
import com.example.data.LinkMirrorRepository
import com.example.data.LocalDeviceConfig
import com.example.data.PairingRecord
import com.example.data.PhotoSource
import com.example.data.RemoteCommand
import com.example.data.RemoteSession
import com.example.data.RoleMode
import com.example.data.SessionStatus
import com.example.data.SharedPhoto
import com.example.data.UiState
import com.example.service.HostServiceTelemetry
import com.example.service.ScreenCaptureBus
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LinkMirrorViewModel(
    private val repository: LinkMirrorRepository,
    private val preferences: DevicePreferences,
    val currentUserId: String,
    private val screenWidth: Long,
    private val screenHeight: Long
) : ViewModel() {

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    private val _isActionBusy = MutableStateFlow(false)
    val isActionBusy: StateFlow<Boolean> = _isActionBusy.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

    val serviceTelemetry: StateFlow<HostServiceTelemetry> = ScreenCaptureBus.telemetry

    val appLockConfig: StateFlow<AppLockConfig> = preferences.appLockConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AppLockConfig()
        )

    val localConfig: StateFlow<LocalDeviceConfig> = preferences.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = LocalDeviceConfig(
                deviceId = "",
                deviceName = "Android Device",
                pairingCode = "000000",
                roleMode = RoleMode.DUAL,
                alwaysAllowControl = true
            )
        )

    val trustedDevices: StateFlow<UiState<List<DeviceEntity>>> =
        repository.observeTrustedDevices(currentUserId)
            .map<List<DeviceEntity>, UiState<List<DeviceEntity>>> { UiState.Success(it) }
            .catch { error -> emit(unwrapFirestoreError("devices", error)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val sessions: StateFlow<UiState<List<RemoteSession>>> =
        repository.observeSessions(currentUserId)
            .map<List<RemoteSession>, UiState<List<RemoteSession>>> { list ->
                val myDeviceId = localConfig.value.deviceId
                val currentSel = _selectedSessionId.value
                if (currentSel != null) {
                    val selectedObj = list.firstOrNull { it.id == currentSel }
                    if (selectedObj != null && selectedObj.status == SessionStatus.ENDED.name) {
                        _selectedSessionId.value = null
                    }
                }
                if (_selectedSessionId.value == null) {
                    val autoPick = list.firstOrNull { s ->
                        (s.status == SessionStatus.ACTIVE.name || s.status == SessionStatus.REQUESTED.name) &&
                            (myDeviceId.isBlank() || s.hostDeviceId != myDeviceId)
                    }
                    if (autoPick != null) {
                        _selectedSessionId.value = autoPick.id
                    }
                }
                UiState.Success(list)
            }
            .catch { error -> emit(unwrapFirestoreError("sessions", error)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val activeSession: StateFlow<RemoteSession?> =
        _selectedSessionId.flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(null)
            else repository.observeSession(id, currentUserId)
        }.catch { error ->
            val fsErr = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (fsErr == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing active session", fsErr ?: error)
            emit(null)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val sessionCommands: StateFlow<List<RemoteCommand>> =
        _selectedSessionId.flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else repository.observeCommands(id, currentUserId)
        }.catch { error ->
            val fsErr = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (fsErr == null && error is CancellationException) throw error
            emit(emptyList())
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val sharedPhotos: StateFlow<UiState<List<SharedPhoto>>> =
        _selectedSessionId.flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(UiState.Success(emptyList()))
            else repository.observeSharedPhotos(id, currentUserId)
                .map<List<SharedPhoto>, UiState<List<SharedPhoto>>> { UiState.Success(it) }
                .catch { error -> emit(unwrapFirestoreError("sharedPhotos", error)) }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Success(emptyList())
        )

    val hostPairings: StateFlow<List<PairingRecord>> =
        repository.observeHostPairings(currentUserId)
            .catch { error ->
                val fsErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && error is CancellationException) throw error
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList()
            )

    init {
        viewModelScope.launch {
            val cfg = preferences.getOrInitConfig(currentUserId)
            syncThisDevice(cfg)
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                val latestCfg = preferences.getOrInitConfig(currentUserId)
                syncThisDevice(latestCfg)
            }
        }
    }

    fun refreshAccessibilityStatus(enabled: Boolean) {
        ScreenCaptureBus.setAccessibilityConnected(enabled)
        viewModelScope.launch {
            val cfg = preferences.getOrInitConfig(currentUserId)
            syncThisDevice(cfg, accessibilityEnabled = enabled)
        }
    }

    private suspend fun syncThisDevice(
        cfg: LocalDeviceConfig,
        accessibilityEnabled: Boolean = serviceTelemetry.value.accessibilityConnected
    ) {
        repository.registerOrUpdateDevice(
            deviceId = cfg.deviceId,
            deviceName = cfg.deviceName,
            pairingCode = cfg.pairingCode,
            roleMode = cfg.roleMode,
            alwaysAllowControl = cfg.alwaysAllowControl,
            screenShareActive = serviceTelemetry.value.screenShareRunning,
            accessibilityActive = accessibilityEnabled,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }

    fun setAlwaysAllowControl(enabled: Boolean) {
        viewModelScope.launch {
            preferences.updateAlwaysAllow(enabled)
            val cfg = preferences.getOrInitConfig(currentUserId).copy(alwaysAllowControl = enabled)
            syncThisDevice(cfg)
            _snackbarEvents.tryEmit(
                if (enabled) "Anytime trusted access enabled for paired devices"
                else "Manual approval required for new connections"
            )
        }
    }

    fun setRoleMode(mode: RoleMode) {
        viewModelScope.launch {
            preferences.updateRoleMode(mode)
            val cfg = preferences.getOrInitConfig(currentUserId).copy(roleMode = mode)
            syncThisDevice(cfg)
        }
    }

    fun setStreamMode(mode: com.example.data.StreamMode) {
        viewModelScope.launch {
            preferences.updateStreamMode(mode)
            _snackbarEvents.tryEmit("Stream mode set to ${mode.title} (${mode.subtitle})")
        }
    }

    suspend fun saveAppPin(pin: String): Boolean {
        val ok = preferences.setAppPin(pin)
        if (ok) {
            _snackbarEvents.tryEmit("App Lock PIN enabled")
        }
        return ok
    }

    suspend fun verifyAppPin(pin: String): Boolean = preferences.verifyAppPin(pin)

    suspend fun disableAppLock(currentPin: String): Boolean {
        val verified = preferences.verifyAppPin(currentPin)
        if (verified) {
            preferences.clearAppPin()
            _snackbarEvents.tryEmit("App Lock PIN disabled")
        }
        return verified
    }

    fun updateAppLockOptions(
        autoLockInBackground: Boolean? = null,
        allowDeviceUnlock: Boolean? = null,
        privacyScreenShield: Boolean? = null
    ) {
        viewModelScope.launch {
            preferences.updateAppLockOptions(
                autoLockInBackground = autoLockInBackground,
                allowDeviceUnlock = allowDeviceUnlock,
                privacyScreenShield = privacyScreenShield
            )
        }
    }

    fun updateDeviceName(newName: String) {
        val clean = newName.trim().take(60)
        if (clean.isBlank()) return
        viewModelScope.launch {
            preferences.updateDeviceName(clean)
            val cfg = preferences.getOrInitConfig(currentUserId).copy(deviceName = clean)
            syncThisDevice(cfg)
            _snackbarEvents.tryEmit("Device renamed to $clean")
        }
    }

    fun generateAndPublishPairingCode() {
        viewModelScope.launch {
            _isActionBusy.value = true
            try {
                val cfg = preferences.getOrInitConfig(currentUserId)
                var publishedCode: String? = null
                var lastErr: Throwable? = null
                repeat(3) {
                    val newCode = preferences.rotatePairingCode()
                    val updatedCfg = cfg.copy(pairingCode = newCode)
                    syncThisDevice(updatedCfg)
                    val res = repository.openPairingCode(
                        pairingCode = newCode,
                        hostDeviceId = updatedCfg.deviceId,
                        hostDeviceName = updatedCfg.deviceName,
                        alwaysAllow = updatedCfg.alwaysAllowControl
                    )
                    if (res.isSuccess) {
                        publishedCode = newCode
                        return@repeat
                    } else {
                        lastErr = res.exceptionOrNull()
                    }
                }
                if (publishedCode != null) {
                    _snackbarEvents.tryEmit("Pairing code $publishedCode is ready")
                } else {
                    _snackbarEvents.tryEmit(lastErr?.localizedMessage ?: "Failed to open pairing code")
                }
            } finally {
                _isActionBusy.value = false
            }
        }
    }

    fun publishCurrentPairingCode() {
        viewModelScope.launch {
            _isActionBusy.value = true
            try {
                val cfg = preferences.getOrInitConfig(currentUserId)
                val res = repository.openPairingCode(
                    pairingCode = cfg.pairingCode,
                    hostDeviceId = cfg.deviceId,
                    hostDeviceName = cfg.deviceName,
                    alwaysAllow = cfg.alwaysAllowControl
                )
                if (res.isSuccess) {
                    _snackbarEvents.tryEmit("Code ${cfg.pairingCode} published for pairing")
                } else {
                    _snackbarEvents.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Could not publish code")
                }
            } finally {
                _isActionBusy.value = false
            }
        }
    }

    fun claimPairingCode(code: String, onSuccess: () -> Unit = {}) {
        val clean = code.trim()
        if (!clean.matches(Regex("^[0-9]{6}$"))) {
            _snackbarEvents.tryEmit("Enter a valid 6-digit pairing code")
            return
        }
        viewModelScope.launch {
            _isActionBusy.value = true
            try {
                val cfg = preferences.getOrInitConfig(currentUserId)
                val res = repository.claimPairingCode(clean, cfg.deviceName)
                if (res.isSuccess) {
                    onSuccess()
                    val hostName = res.getOrNull()?.hostDeviceName ?: "Host"
                    _snackbarEvents.tryEmit("Paired with $hostName! Tap Grant Trust on $hostName to confirm.")
                } else {
                    _snackbarEvents.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Invalid or expired code")
                }
            } finally {
                _isActionBusy.value = false
            }
        }
    }

    fun approveClaimedPairing(pairing: PairingRecord) {
        viewModelScope.launch {
            _isActionBusy.value = true
            try {
                val res = repository.approveClaimedPairing(pairing)
                if (res.isSuccess) {
                    _snackbarEvents.tryEmit("${pairing.controllerDeviceName} added as a trusted controller")
                } else {
                    _snackbarEvents.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Approval failed")
                }
            } finally {
                _isActionBusy.value = false
            }
        }
    }

    fun dismissClaimedPairing(pairing: PairingRecord) {
        viewModelScope.launch {
            _isActionBusy.value = true
            try {
                val res = repository.dismissPairing(pairing.id)
                if (res.isSuccess) {
                    _snackbarEvents.tryEmit("Pairing request dismissed")
                } else {
                    _snackbarEvents.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Could not dismiss pairing")
                }
            } finally {
                _isActionBusy.value = false
            }
        }
    }

    fun revokeTrustedController(uidToRemove: String) {
        viewModelScope.launch {
            val cfg = preferences.getOrInitConfig(currentUserId)
            val res = repository.revokeTrustedUid(cfg.deviceId, uidToRemove)
            if (res.isSuccess) {
                _snackbarEvents.tryEmit("Trusted access revoked")
            } else {
                _snackbarEvents.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Could not revoke access")
            }
        }
    }

    fun connectToHostDevice(hostDevice: DeviceEntity) {
        viewModelScope.launch {
            _isActionBusy.value = true
            try {
                val cfg = preferences.getOrInitConfig(currentUserId)
                val res = repository.startOrConnectSession(
                    hostDevice = hostDevice,
                    controllerDeviceId = cfg.deviceId,
                    controllerDeviceName = cfg.deviceName
                )
                if (res.isSuccess) {
                    val sessionId = res.getOrNull()
                    _selectedSessionId.value = sessionId
                    _snackbarEvents.tryEmit(
                        if (hostDevice.alwaysAllowControl) "Connected to ${hostDevice.deviceName}"
                        else "Connection request sent to ${hostDevice.deviceName}"
                    )
                } else {
                    _snackbarEvents.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Failed to connect")
                }
            } finally {
                _isActionBusy.value = false
            }
        }
    }

    fun selectSession(sessionId: String) {
        _selectedSessionId.value = sessionId
    }

    fun approveOrResumeSession(sessionId: String) {
        viewModelScope.launch {
            val res = repository.updateSessionStatus(sessionId, SessionStatus.ACTIVE)
            if (res.isFailure) {
                _snackbarEvents.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Could not activate session")
            }
        }
    }

    fun endSession(sessionId: String) {
        viewModelScope.launch {
            val res = repository.updateSessionStatus(sessionId, SessionStatus.ENDED)
            if (res.isSuccess) {
                if (_selectedSessionId.value == sessionId) {
                    _selectedSessionId.value = null
                }
                _snackbarEvents.tryEmit("Remote session ended")
            } else {
                _snackbarEvents.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Could not end session")
            }
        }
    }

    fun sendRemoteCommand(
        type: CommandType,
        normX: Double = 0.5,
        normY: Double = 0.5,
        endNormX: Double = normX,
        endNormY: Double = normY
    ) {
        val session = activeSession.value ?: run {
            _snackbarEvents.tryEmit("Connect to a device first")
            return
        }
        viewModelScope.launch {
            val res = repository.sendCommand(
                session = session,
                type = type,
                normX = normX,
                normY = normY,
                endNormX = endNormX,
                endNormY = endNormY
            )
            if (res.isFailure) {
                _snackbarEvents.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Command failed")
            } else if (type == CommandType.CAPTURE_SNAPSHOT) {
                _snackbarEvents.tryEmit("Snapshot requested from ${session.hostDeviceName}")
            }
        }
    }

    fun sharePhotoBase64(title: String, base64: String) {
        val session = activeSession.value ?: run {
            _snackbarEvents.tryEmit("Connect to a device session before sharing a photo")
            return
        }
        viewModelScope.launch {
            _isActionBusy.value = true
            try {
                val cfg = preferences.getOrInitConfig(currentUserId)
                val res = repository.uploadSharedPhoto(
                    session = session,
                    senderDeviceName = cfg.deviceName,
                    title = title,
                    source = PhotoSource.GALLERY_PHOTO,
                    imageBase64 = base64
                )
                if (res.isSuccess) {
                    _snackbarEvents.tryEmit("Photo shared with ${session.hostDeviceName}")
                } else {
                    _snackbarEvents.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Photo upload failed")
                }
            } finally {
                _isActionBusy.value = false
            }
        }
    }

    fun notifyMessage(message: String) {
        _snackbarEvents.tryEmit(message)
    }

    private fun <T> unwrapFirestoreError(label: String, error: Throwable): UiState<T> {
        val fsErr = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (fsErr == null && error is CancellationException) throw error
        val root = fsErr ?: error
        Log.w(TAG, "Error observing $label", root)
        return UiState.Error(root.message ?: "Failed to load $label")
    }

    private companion object {
        const val TAG = "LinkMirrorVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
