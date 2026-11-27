package com.example.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.UUID

private val Context.linkMirrorDataStore by preferencesDataStore(name = "linkmirror_device_prefs")

enum class StreamMode(
    val title: String,
    val subtitle: String,
    val intervalMs: Long,
    val maxDimension: Int,
    val jpegQuality: Int
) {
    ECO("Eco", "6–8 hrs/day • Low data", 950L, 420, 44),
    BALANCED("Balanced", "Recommended • Smart diff", 480L, 520, 54),
    SMOOTH("Smooth", "Fastest live motion", 260L, 600, 62);

    companion object {
        fun fromRaw(raw: String?): StreamMode =
            entries.firstOrNull { it.name == raw } ?: BALANCED
    }
}

data class LocalDeviceConfig(
    val deviceId: String,
    val deviceName: String,
    val pairingCode: String,
    val roleMode: RoleMode,
    val alwaysAllowControl: Boolean,
    val streamMode: StreamMode = StreamMode.BALANCED
)

class DevicePreferences(private val context: Context) {

    private val appContext = context.applicationContext

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val PAIRING_CODE = stringPreferencesKey("pairing_code")
        val ROLE_MODE = stringPreferencesKey("role_mode")
        val ALWAYS_ALLOW = booleanPreferencesKey("always_allow_control")
        val STREAM_MODE = stringPreferencesKey("stream_mode")
    }

    val configFlow: Flow<LocalDeviceConfig> = appContext.linkMirrorDataStore.data.map { prefs ->
        val defaultModel = defaultDeviceModelName()
        LocalDeviceConfig(
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            deviceName = prefs[Keys.DEVICE_NAME] ?: defaultModel,
            pairingCode = prefs[Keys.PAIRING_CODE] ?: "",
            roleMode = RoleMode.fromRaw(prefs[Keys.ROLE_MODE]),
            alwaysAllowControl = prefs[Keys.ALWAYS_ALLOW] ?: true,
            streamMode = StreamMode.fromRaw(prefs[Keys.STREAM_MODE])
        )
    }

    suspend fun getOrInitConfig(): LocalDeviceConfig {
        val current = configFlow.first()
        if (current.deviceId.isNotBlank() && current.pairingCode.length == 6) {
            return current
        }
        val newId = current.deviceId.ifBlank {
            "dev_" + UUID.randomUUID().toString().replace("-", "").take(16)
        }
        val newCode = if (current.pairingCode.length == 6) current.pairingCode else generateSixDigitCode()
        val name = current.deviceName.ifBlank { defaultDeviceModelName() }
        appContext.linkMirrorDataStore.edit { prefs ->
            prefs[Keys.DEVICE_ID] = newId
            prefs[Keys.DEVICE_NAME] = name
            prefs[Keys.PAIRING_CODE] = newCode
            if (prefs[Keys.ROLE_MODE] == null) {
                prefs[Keys.ROLE_MODE] = RoleMode.DUAL.name
            }
            if (prefs[Keys.ALWAYS_ALLOW] == null) {
                prefs[Keys.ALWAYS_ALLOW] = true
            }
            if (prefs[Keys.STREAM_MODE] == null) {
                prefs[Keys.STREAM_MODE] = StreamMode.BALANCED.name
            }
        }
        return LocalDeviceConfig(
            deviceId = newId,
            deviceName = name,
            pairingCode = newCode,
            roleMode = current.roleMode,
            alwaysAllowControl = current.alwaysAllowControl,
            streamMode = current.streamMode
        )
    }

    suspend fun updateRoleMode(mode: RoleMode) {
        appContext.linkMirrorDataStore.edit { it[Keys.ROLE_MODE] = mode.name }
    }

    suspend fun updateAlwaysAllow(enabled: Boolean) {
        appContext.linkMirrorDataStore.edit { it[Keys.ALWAYS_ALLOW] = enabled }
    }

    suspend fun updateStreamMode(mode: StreamMode) {
        appContext.linkMirrorDataStore.edit { it[Keys.STREAM_MODE] = mode.name }
    }

    suspend fun updateDeviceName(name: String) {
        val clean = name.trim().take(60)
        if (clean.isNotEmpty()) {
            appContext.linkMirrorDataStore.edit { it[Keys.DEVICE_NAME] = clean }
        }
    }

    suspend fun rotatePairingCode(): String {
        val code = generateSixDigitCode()
        appContext.linkMirrorDataStore.edit { it[Keys.PAIRING_CODE] = code }
        return code
    }

    private fun generateSixDigitCode(): String {
        val rng = SecureRandom()
        return (100000 + rng.nextInt(900000)).toString()
    }

    private fun defaultDeviceModelName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val combined = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
        return combined.ifBlank { "Android Device" }.take(60)
    }
}
