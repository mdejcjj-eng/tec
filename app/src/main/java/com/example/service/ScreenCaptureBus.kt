package com.example.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HostServiceTelemetry(
    val screenShareRunning: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val activeHostSessionId: String? = null,
    val framesSent: Long = 0L,
    val commandsExecuted: Long = 0L,
    val lastActionLabel: String = "Idle",
    val lastError: String? = null
)

object ScreenCaptureBus {
    private val _telemetry = MutableStateFlow(HostServiceTelemetry())
    val telemetry: StateFlow<HostServiceTelemetry> = _telemetry.asStateFlow()

    fun setScreenShareRunning(running: Boolean, error: String? = null) {
        _telemetry.value = _telemetry.value.copy(
            screenShareRunning = running,
            lastError = error,
            lastActionLabel = if (running) "Screen Share Armed" else "Screen Share Stopped"
        )
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _telemetry.value = _telemetry.value.copy(
            accessibilityConnected = connected
        )
    }

    fun setActiveHostSession(sessionId: String?) {
        _telemetry.value = _telemetry.value.copy(
            activeHostSessionId = sessionId
        )
    }

    fun recordFrameSent(seq: Long) {
        _telemetry.value = _telemetry.value.copy(
            framesSent = seq,
            lastActionLabel = "Streaming frame #$seq"
        )
    }

    fun recordCommandExecuted(actionName: String) {
        _telemetry.value = _telemetry.value.copy(
            commandsExecuted = _telemetry.value.commandsExecuted + 1L,
            lastActionLabel = "Executed: $actionName"
        )
    }

    fun setLastError(message: String?) {
        _telemetry.value = _telemetry.value.copy(lastError = message)
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, LinkMirrorAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component == expected) {
                return true
            }
        }
        return false
    }
}
