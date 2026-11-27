package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class RoleMode {
    DUAL, CONTROLLER, HOST;

    companion object {
        fun fromRaw(raw: String?): RoleMode =
            entries.firstOrNull { it.name == raw } ?: DUAL
    }
}

enum class SessionStatus {
    REQUESTED, ACTIVE, PAUSED, ENDED;

    companion object {
        fun fromRaw(raw: String?): SessionStatus =
            entries.firstOrNull { it.name == raw } ?: REQUESTED
    }
}

enum class CommandType {
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SWIPE,
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    SCROLL_UP,
    SCROLL_DOWN,
    WAKE_STREAM,
    CAPTURE_SNAPSHOT;

    companion object {
        fun fromRaw(raw: String?): CommandType =
            entries.firstOrNull { it.name == raw } ?: TAP
    }
}

enum class CommandStatus {
    PENDING, EXECUTED, FAILED;

    companion object {
        fun fromRaw(raw: String?): CommandStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

enum class PhotoSource {
    SCREEN_SNAPSHOT, GALLERY_PHOTO;

    companion object {
        fun fromRaw(raw: String?): PhotoSource =
            entries.firstOrNull { it.name == raw } ?: GALLERY_PHOTO
    }
}

@Keep
@IgnoreExtraProperties
data class DeviceEntity(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val deviceName: String = "",
    val pairingCode: String = "000000",
    val roleMode: String = RoleMode.DUAL.name,
    val alwaysAllowControl: Boolean = false,
    val screenShareActive: Boolean = false,
    val accessibilityActive: Boolean = false,
    val trustedUids: List<String> = emptyList(),
    val screenWidth: Long = 1080L,
    val screenHeight: Long = 2400L,
    @ServerTimestamp val lastSeenAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PairingRecord(
    @DocumentId val id: String = "",
    val hostUid: String = "",
    val hostDeviceId: String = "",
    val hostDeviceName: String = "",
    val controllerUid: String = "",
    val controllerDeviceName: String = "",
    val status: String = "OPEN",
    val alwaysAllow: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class RemoteSession(
    @DocumentId val id: String = "",
    val hostUid: String = "",
    val hostDeviceId: String = "",
    val hostDeviceName: String = "",
    val controllerUid: String = "",
    val controllerDeviceId: String = "",
    val controllerDeviceName: String = "",
    val participantUids: List<String> = emptyList(),
    val status: String = SessionStatus.ACTIVE.name,
    val alwaysAllow: Boolean = false,
    val remoteControlEnabled: Boolean = true,
    val latestFrameBase64: String = "",
    val frameSeq: Long = 0L,
    val frameWidth: Long = 540L,
    val frameHeight: Long = 1200L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class RemoteCommand(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val hostUid: String = "",
    val participantUids: List<String> = emptyList(),
    val type: String = CommandType.TAP.name,
    val normX: Double = 0.5,
    val normY: Double = 0.5,
    val endNormX: Double = 0.5,
    val endNormY: Double = 0.5,
    val status: String = CommandStatus.PENDING.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SharedPhoto(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val senderDeviceName: String = "",
    val participantUids: List<String> = emptyList(),
    val title: String = "",
    val source: String = PhotoSource.GALLERY_PHOTO.name,
    val imageBase64: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
