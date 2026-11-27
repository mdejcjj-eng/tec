package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class LinkMirrorRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeTrustedDevices(userId: String): Flow<List<DeviceEntity>> = flow {
        val path = "devices"
        if (userId.isBlank()) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection(path)
                .whereArrayContains("trustedUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DeviceEntity::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fsErr ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeSessions(userId: String): Flow<List<RemoteSession>> = flow {
        val path = "sessions"
        if (userId.isBlank()) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection(path)
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RemoteSession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fsErr ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeSession(sessionId: String, userId: String): Flow<RemoteSession?> = flow {
        val path = "sessions/$sessionId"
        if (sessionId.isBlank() || userId.isBlank()) {
            emit(null)
            return@flow
        }
        emitAll(
            db.collection("sessions").document(sessionId)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) null
                    else snapshot.toObject(
                        RemoteSession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fsErr ?: error, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observeCommands(sessionId: String, userId: String): Flow<List<RemoteCommand>> = flow {
        val path = "sessions/$sessionId/commands"
        if (sessionId.isBlank() || userId.isBlank()) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection("sessions").document(sessionId).collection("commands")
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RemoteCommand::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fsErr ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeSharedPhotos(sessionId: String, userId: String): Flow<List<SharedPhoto>> = flow {
        val path = "sessions/$sessionId/sharedPhotos"
        if (sessionId.isBlank() || userId.isBlank()) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection("sessions").document(sessionId).collection("sharedPhotos")
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SharedPhoto::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fsErr ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeHostPairings(userId: String): Flow<List<PairingRecord>> = flow {
        val path = "pairings"
        if (userId.isBlank()) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection(path)
                .whereEqualTo("hostUid", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        PairingRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fsErr ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun registerOrUpdateDevice(
        deviceId: String,
        deviceName: String,
        pairingCode: String,
        roleMode: RoleMode,
        alwaysAllowControl: Boolean,
        screenShareActive: Boolean,
        accessibilityActive: Boolean,
        screenWidth: Long,
        screenHeight: Long,
        extraTrustedUids: List<String> = emptyList()
    ): Result<Unit> {
        val path = "devices/$deviceId"
        return try {
            val uid = requireUserId()
            require(deviceId.isNotBlank()) { "Device ID must not be blank" }
            val cleanName = deviceName.trim().ifBlank { "Android Device" }.take(60)
            val safeWidth = screenWidth.coerceIn(100L, 8000L)
            val safeHeight = screenHeight.coerceIn(100L, 8000L)

            val docRef = db.collection("devices").document(deviceId)
            val existingSnap = docRef.get().await()
            if (!existingSnap.exists()) {
                val trusted = (listOf(uid) + extraTrustedUids).distinct().take(10)
                val payload = mapOf(
                    "ownerUid" to uid,
                    "deviceName" to cleanName,
                    "pairingCode" to pairingCode,
                    "roleMode" to roleMode.name,
                    "alwaysAllowControl" to alwaysAllowControl,
                    "screenShareActive" to screenShareActive,
                    "accessibilityActive" to accessibilityActive,
                    "trustedUids" to trusted,
                    "screenWidth" to safeWidth,
                    "screenHeight" to safeHeight,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else {
                val existingTrusted = (existingSnap.get("trustedUids") as? List<*>)
                    ?.filterIsInstance<String>()
                    ?: emptyList()
                val mergedTrusted = (listOf(uid) + existingTrusted + extraTrustedUids).distinct().take(10)
                docRef.update(
                    mapOf(
                        "deviceName" to cleanName,
                        "pairingCode" to pairingCode,
                        "roleMode" to roleMode.name,
                        "alwaysAllowControl" to alwaysAllowControl,
                        "screenShareActive" to screenShareActive,
                        "accessibilityActive" to accessibilityActive,
                        "trustedUids" to mergedTrusted,
                        "screenWidth" to safeWidth,
                        "screenHeight" to safeHeight,
                        "lastSeenAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun revokeTrustedUid(deviceId: String, uidToRemove: String): Result<Unit> {
        val path = "devices/$deviceId"
        return try {
            val uid = requireUserId()
            require(uidToRemove != uid) { "Cannot revoke your own primary account from this device" }
            val docRef = db.collection("devices").document(deviceId)
            val snap = docRef.get().await()
            val existingTrusted = (snap.get("trustedUids") as? List<*>)
                ?.filterIsInstance<String>()
                ?: listOf(uid)
            val updated = (listOf(uid) + existingTrusted.filter { it != uidToRemove }).distinct().take(10)
            docRef.update(
                mapOf(
                    "trustedUids" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun openPairingCode(
        pairingCode: String,
        hostDeviceId: String,
        hostDeviceName: String,
        alwaysAllow: Boolean
    ): Result<Unit> {
        val path = "pairings/$pairingCode"
        return try {
            val uid = requireUserId()
            require(pairingCode.matches(Regex("^[0-9]{6}$"))) { "Pairing code must be 6 digits" }
            val docRef = db.collection("pairings").document(pairingCode)
            val existing = docRef.get().await()
            if (existing.exists() && existing.getString("hostUid") == uid) {
                docRef.update(
                    mapOf(
                        "status" to "OPEN",
                        "alwaysAllow" to alwaysAllow,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else if (existing.exists()) {
                return Result.failure(IllegalStateException("Code $pairingCode is already in use — tap New Code"))
            } else {
                docRef.set(
                    mapOf(
                        "hostUid" to uid,
                        "hostDeviceId" to hostDeviceId,
                        "hostDeviceName" to hostDeviceName.trim().ifBlank { "Host Device" }.take(60),
                        "controllerUid" to "",
                        "controllerDeviceName" to "",
                        "status" to "OPEN",
                        "alwaysAllow" to alwaysAllow,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun claimPairingCode(
        pairingCode: String,
        controllerDeviceName: String
    ): Result<PairingRecord> {
        val path = "pairings/$pairingCode"
        return try {
            val uid = requireUserId()
            val cleanCode = pairingCode.trim()
            require(cleanCode.matches(Regex("^[0-9]{6}$"))) { "Enter a valid 6-digit code" }
            val docRef = db.collection("pairings").document(cleanCode)
            val snap = docRef.get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalArgumentException("Pairing code $cleanCode was not found"))
            }
            val record = snap.toObject(
                PairingRecord::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: return Result.failure(IllegalStateException("Invalid pairing record"))

            if (record.status != "OPEN") {
                return Result.failure(IllegalStateException("Pairing code is already ${record.status.lowercase()}"))
            }
            docRef.update(
                mapOf(
                    "status" to "CLAIMED",
                    "controllerUid" to uid,
                    "controllerDeviceName" to controllerDeviceName.take(60).ifBlank { "Controller Phone" },
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(record.copy(status = "CLAIMED", controllerUid = uid, controllerDeviceName = controllerDeviceName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun approveClaimedPairing(pairing: PairingRecord): Result<Unit> {
        val path = "devices/${pairing.hostDeviceId}"
        return try {
            val uid = requireUserId()
            if (pairing.controllerUid.isBlank()) {
                return Result.failure(IllegalStateException("No controller has claimed this code yet"))
            }
            val docRef = db.collection("devices").document(pairing.hostDeviceId)
            val snap = docRef.get().await()
            val existingTrusted = (snap.get("trustedUids") as? List<*>)
                ?.filterIsInstance<String>()
                ?: listOf(uid)
            val updated = (listOf(uid) + existingTrusted + pairing.controllerUid).distinct().take(10)
            docRef.update(
                mapOf(
                    "trustedUids" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            db.collection("pairings").document(pairing.id).update(
                mapOf(
                    "status" to "APPROVED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun dismissPairing(pairingCode: String): Result<Unit> {
        val path = "pairings/$pairingCode"
        return try {
            requireUserId()
            db.collection("pairings").document(pairingCode).update(
                mapOf(
                    "status" to "REVOKED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun startOrConnectSession(
        hostDevice: DeviceEntity,
        controllerDeviceId: String,
        controllerDeviceName: String
    ): Result<String> {
        val sessionId = "sess_${hostDevice.id.take(24)}_${controllerDeviceId.take(24)}"
        val path = "sessions/$sessionId"
        return try {
            val uid = requireUserId()
            if (hostDevice.id == controllerDeviceId) {
                return Result.failure(IllegalArgumentException("Cannot connect this device to itself"))
            }
            val participants = listOf(hostDevice.ownerUid, uid).distinct().take(2)
            val docRef = db.collection("sessions").document(sessionId)
            val existing = docRef.get().await()
            val desiredStatus = if (hostDevice.alwaysAllowControl) {
                SessionStatus.ACTIVE.name
            } else {
                SessionStatus.REQUESTED.name
            }
            if (!existing.exists()) {
                val safeHostName = hostDevice.deviceName.trim().ifBlank { "Host Device" }.take(60)
                val safeCtrlName = controllerDeviceName.trim().ifBlank { "Controller Phone" }.take(60)
                val payload = mapOf(
                    "hostUid" to hostDevice.ownerUid,
                    "hostDeviceId" to hostDevice.id,
                    "hostDeviceName" to safeHostName,
                    "controllerUid" to uid,
                    "controllerDeviceId" to controllerDeviceId,
                    "controllerDeviceName" to safeCtrlName,
                    "participantUids" to participants,
                    "status" to desiredStatus,
                    "alwaysAllow" to hostDevice.alwaysAllowControl,
                    "remoteControlEnabled" to hostDevice.accessibilityActive,
                    "latestFrameBase64" to "",
                    "frameSeq" to 0L,
                    "frameWidth" to hostDevice.screenWidth.coerceIn(0L, 8000L),
                    "frameHeight" to hostDevice.screenHeight.coerceIn(0L, 8000L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else {
                val currentStatus = existing.getString("status")
                if (currentStatus == SessionStatus.ENDED.name) {
                    val safeHostName = hostDevice.deviceName.trim().ifBlank { "Host Device" }.take(60)
                    val safeCtrlName = controllerDeviceName.trim().ifBlank { "Controller Phone" }.take(60)
                    val freshId = "sess_${UUID.randomUUID().toString().replace("-", "").take(18)}"
                    val freshRef = db.collection("sessions").document(freshId)
                    freshRef.set(
                        mapOf(
                            "hostUid" to hostDevice.ownerUid,
                            "hostDeviceId" to hostDevice.id,
                            "hostDeviceName" to safeHostName,
                            "controllerUid" to uid,
                            "controllerDeviceId" to controllerDeviceId,
                            "controllerDeviceName" to safeCtrlName,
                            "participantUids" to participants,
                            "status" to desiredStatus,
                            "alwaysAllow" to hostDevice.alwaysAllowControl,
                            "remoteControlEnabled" to hostDevice.accessibilityActive,
                            "latestFrameBase64" to "",
                            "frameSeq" to 0L,
                            "frameWidth" to hostDevice.screenWidth.coerceIn(0L, 8000L),
                            "frameHeight" to hostDevice.screenHeight.coerceIn(0L, 8000L),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    return Result.success(freshId)
                } else {
                    docRef.update(
                        mapOf(
                            "status" to desiredStatus,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
            Result.success(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun updateSessionFrame(
        sessionId: String,
        frameBase64: String,
        frameSeq: Long,
        width: Long,
        height: Long,
        remoteControlEnabled: Boolean,
        alwaysAllow: Boolean
    ): Result<Unit> {
        val path = "sessions/$sessionId"
        return try {
            requireUserId()
            require(frameBase64.length <= 350_000) { "Frame exceeds size cap" }
            db.collection("sessions").document(sessionId).update(
                mapOf(
                    "status" to SessionStatus.ACTIVE.name,
                    "alwaysAllow" to alwaysAllow,
                    "remoteControlEnabled" to remoteControlEnabled,
                    "latestFrameBase64" to frameBase64,
                    "frameSeq" to frameSeq,
                    "frameWidth" to width.coerceIn(0L, 8000L),
                    "frameHeight" to height.coerceIn(0L, 8000L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateSessionStatus(sessionId: String, status: SessionStatus): Result<Unit> {
        val path = "sessions/$sessionId"
        return try {
            requireUserId()
            db.collection("sessions").document(sessionId).update(
                mapOf(
                    "status" to status.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun sendCommand(
        session: RemoteSession,
        type: CommandType,
        normX: Double = 0.5,
        normY: Double = 0.5,
        endNormX: Double = normX,
        endNormY: Double = normY
    ): Result<Unit> {
        val cmdId = "cmd_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val path = "sessions/${session.id}/commands/$cmdId"
        return try {
            val uid = requireUserId()
            if (session.status == SessionStatus.ENDED.name) {
                return Result.failure(IllegalStateException("Session has already ended"))
            }
            val payload = mapOf(
                "senderUid" to uid,
                "hostUid" to session.hostUid,
                "participantUids" to session.participantUids,
                "type" to type.name,
                "normX" to normX.coerceIn(0.0, 1.0),
                "normY" to normY.coerceIn(0.0, 1.0),
                "endNormX" to endNormX.coerceIn(0.0, 1.0),
                "endNormY" to endNormY.coerceIn(0.0, 1.0),
                "status" to CommandStatus.PENDING.name,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("sessions").document(session.id)
                .collection("commands").document(cmdId)
                .set(payload)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun markCommandStatus(
        sessionId: String,
        commandId: String,
        status: CommandStatus
    ): Result<Unit> {
        val path = "sessions/$sessionId/commands/$commandId"
        return try {
            requireUserId()
            db.collection("sessions").document(sessionId)
                .collection("commands").document(commandId)
                .update(
                    mapOf(
                        "status" to status.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun uploadSharedPhoto(
        session: RemoteSession,
        senderDeviceName: String,
        title: String,
        source: PhotoSource,
        imageBase64: String
    ): Result<Unit> {
        val photoId = "pic_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val path = "sessions/${session.id}/sharedPhotos/$photoId"
        return try {
            val uid = requireUserId()
            if (session.status == SessionStatus.ENDED.name) {
                return Result.failure(IllegalStateException("Session has already ended"))
            }
            require(imageBase64.length in 10..400_000) { "Compressed image exceeds 400KB limit" }
            val payload = mapOf(
                "senderUid" to uid,
                "senderDeviceName" to senderDeviceName.trim().ifBlank { "Device" }.take(60),
                "participantUids" to session.participantUids,
                "title" to title.trim().ifBlank { "Shared Photo" }.take(100),
                "source" to source.name,
                "imageBase64" to imageBase64,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("sessions").document(session.id)
                .collection("sharedPhotos").document(photoId)
                .set(payload)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }
}
