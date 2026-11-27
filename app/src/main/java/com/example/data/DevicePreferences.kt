package com.example.data

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
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

data class AppLockConfig(
    val isPinEnabled: Boolean = false,
    val autoLockInBackground: Boolean = true,
    val allowDeviceUnlock: Boolean = true,
    val privacyScreenShield: Boolean = false,
    val isLoaded: Boolean = false
)

class DevicePreferences(private val context: Context) {

    private val appContext = context.applicationContext

    private object Keys {
        val OWNER_UID = stringPreferencesKey("owner_uid")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val PAIRING_CODE = stringPreferencesKey("pairing_code")
        val ROLE_MODE = stringPreferencesKey("role_mode")
        val ALWAYS_ALLOW = booleanPreferencesKey("always_allow_control")
        val STREAM_MODE = stringPreferencesKey("stream_mode")

        val APP_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val APP_LOCK_PIN_SALT = stringPreferencesKey("app_lock_pin_salt")
        val APP_LOCK_AUTO_BG = booleanPreferencesKey("app_lock_auto_bg")
        val APP_LOCK_DEVICE_CREDENTIAL = booleanPreferencesKey("app_lock_device_credential")
        val PRIVACY_SCREEN_SHIELD = booleanPreferencesKey("privacy_screen_shield")
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

    val appLockConfigFlow: Flow<AppLockConfig> = appContext.linkMirrorDataStore.data.map { prefs ->
        val hash = prefs[Keys.APP_LOCK_PIN_HASH].orEmpty()
        val salt = prefs[Keys.APP_LOCK_PIN_SALT].orEmpty()
        AppLockConfig(
            isPinEnabled = hash.isNotBlank() && salt.isNotBlank(),
            autoLockInBackground = prefs[Keys.APP_LOCK_AUTO_BG] ?: true,
            allowDeviceUnlock = prefs[Keys.APP_LOCK_DEVICE_CREDENTIAL] ?: true,
            privacyScreenShield = prefs[Keys.PRIVACY_SCREEN_SHIELD] ?: false,
            isLoaded = true
        )
    }

    suspend fun getAppLockConfig(): AppLockConfig {
        return appLockConfigFlow.first()
    }

    suspend fun setAppPin(pin: String): Boolean {
        val clean = pin.trim()
        if (!clean.matches(Regex("^[0-9]{4,6}$"))) return false
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltB64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        val hashB64 = hashPin(clean, saltBytes)
        appContext.linkMirrorDataStore.edit { prefs ->
            prefs[Keys.APP_LOCK_PIN_SALT] = saltB64
            prefs[Keys.APP_LOCK_PIN_HASH] = hashB64
            if (prefs[Keys.APP_LOCK_AUTO_BG] == null) {
                prefs[Keys.APP_LOCK_AUTO_BG] = true
            }
            if (prefs[Keys.APP_LOCK_DEVICE_CREDENTIAL] == null) {
                prefs[Keys.APP_LOCK_DEVICE_CREDENTIAL] = true
            }
        }
        return true
    }

    suspend fun verifyAppPin(pin: String): Boolean {
        val clean = pin.trim()
        if (!clean.matches(Regex("^[0-9]{4,6}$"))) return false
        val prefs = appContext.linkMirrorDataStore.data.first()
        val saltB64 = prefs[Keys.APP_LOCK_PIN_SALT].orEmpty()
        val storedHashB64 = prefs[Keys.APP_LOCK_PIN_HASH].orEmpty()
        if (saltB64.isBlank() || storedHashB64.isBlank()) return false
        return try {
            val saltBytes = Base64.decode(saltB64, Base64.NO_WRAP)
            val expectedHash = Base64.decode(storedHashB64, Base64.NO_WRAP)
            val candidateHash = Base64.decode(hashPin(clean, saltBytes), Base64.NO_WRAP)
            MessageDigest.isEqual(expectedHash, candidateHash)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun clearAppPin() {
        appContext.linkMirrorDataStore.edit { prefs ->
            prefs.remove(Keys.APP_LOCK_PIN_HASH)
            prefs.remove(Keys.APP_LOCK_PIN_SALT)
        }
    }

    suspend fun updateAppLockOptions(
        autoLockInBackground: Boolean? = null,
        allowDeviceUnlock: Boolean? = null,
        privacyScreenShield: Boolean? = null
    ) {
        appContext.linkMirrorDataStore.edit { prefs ->
            autoLockInBackground?.let { prefs[Keys.APP_LOCK_AUTO_BG] = it }
            allowDeviceUnlock?.let { prefs[Keys.APP_LOCK_DEVICE_CREDENTIAL] = it }
            privacyScreenShield?.let { prefs[Keys.PRIVACY_SCREEN_SHIELD] = it }
        }
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var current = digest.run {
            reset()
            update(salt)
            update(pin.toByteArray(Charsets.UTF_8))
            digest()
        }
        repeat(4999) {
            digest.reset()
            digest.update(salt)
            digest.update(current)
            current = digest.digest()
        }
        return Base64.encodeToString(current, Base64.NO_WRAP)
    }

    suspend fun getOrInitConfig(ownerUid: String = ""): LocalDeviceConfig {
        val prefsSnapshot = appContext.linkMirrorDataStore.data.first()
        val storedOwner = prefsSnapshot[Keys.OWNER_UID].orEmpty()
        val existingId = prefsSnapshot[Keys.DEVICE_ID].orEmpty()
        val existingCode = prefsSnapshot[Keys.PAIRING_CODE].orEmpty()
        val existingName = prefsSnapshot[Keys.DEVICE_NAME].orEmpty()
        val roleMode = RoleMode.fromRaw(prefsSnapshot[Keys.ROLE_MODE])
        val alwaysAllow = prefsSnapshot[Keys.ALWAYS_ALLOW] ?: true
        val streamMode = StreamMode.fromRaw(prefsSnapshot[Keys.STREAM_MODE])

        val accountChanged = ownerUid.isNotBlank() && storedOwner.isNotBlank() && storedOwner != ownerUid
        val needsOwnerBackfill = ownerUid.isNotBlank() && storedOwner.isBlank()

        if (!accountChanged && !needsOwnerBackfill && existingId.isNotBlank() && existingCode.length == 6) {
            return LocalDeviceConfig(
                deviceId = existingId,
                deviceName = existingName.ifBlank { defaultDeviceModelName() },
                pairingCode = existingCode,
                roleMode = roleMode,
                alwaysAllowControl = alwaysAllow,
                streamMode = streamMode
            )
        }

        val newId = if (accountChanged || existingId.isBlank()) {
            "dev_" + UUID.randomUUID().toString().replace("-", "").take(16)
        } else {
            existingId
        }
        val newCode = if (accountChanged || existingCode.length != 6) {
            generateSixDigitCode()
        } else {
            existingCode
        }
        val name = existingName.ifBlank { defaultDeviceModelName() }

        appContext.linkMirrorDataStore.edit { prefs ->
            if (ownerUid.isNotBlank()) {
                prefs[Keys.OWNER_UID] = ownerUid
            }
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
            roleMode = roleMode,
            alwaysAllowControl = alwaysAllow,
            streamMode = streamMode
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
