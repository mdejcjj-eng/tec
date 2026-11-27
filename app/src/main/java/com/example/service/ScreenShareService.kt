package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.CommandStatus
import com.example.data.CommandType
import com.example.data.DevicePreferences
import com.example.data.LinkMirrorRepository
import com.example.data.PhotoSource
import com.example.data.RemoteSession
import com.example.data.SessionStatus
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ScreenShareService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureWidth = 540
    private var captureHeight = 1170
    private var captureDensityDpi = DisplayMetrics.DENSITY_HIGH

    private val frameSequence = AtomicLong(0L)
    private val isUploadingFrame = AtomicBoolean(false)
    private var lastFrameSentAtMs = 0L
    private var lastIdleCacheAtMs = 0L

    private lateinit var repository: LinkMirrorRepository
    private lateinit var preferences: DevicePreferences
    private var wakeLock: PowerManager.WakeLock? = null
    private var hostDeviceId: String = ""
    private var hostDeviceName: String = "Host Device"
    @Volatile
    private var alwaysAllow: Boolean = true
    @Volatile
    private var streamMode: com.example.data.StreamMode = com.example.data.StreamMode.BALANCED
    @Volatile
    private var lastFrameFingerprint: Long = 0L
    @Volatile
    private var lastEncodedFrameBase64: String? = null

    private val captureLock = Any()

    @Volatile
    private var activeSession: RemoteSession? = null
    @Volatile
    private var observedCommandsSessionId: String? = null

    private var configJob: Job? = null
    private var sessionsJob: Job? = null
    private var commandsJob: Job? = null
    private var heartbeatJob: Job? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system or user")
            cleanupCaptureResources()
            ScreenCaptureBus.setScreenShareRunning(false)
            ScreenCaptureBus.setActiveHostSession(null)
            serviceScope.launch {
                syncDeviceShareState(active = false)
                stopSelf()
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val scale = 540f / maxOf(width, height).coerceAtLeast(1)
            val newW = (width * scale).toInt().coerceAtLeast(180)
            val newH = (height * scale).toInt().coerceAtLeast(180)
            if (newW != captureWidth || newH != captureHeight) {
                captureWidth = newW
                captureHeight = newH
                recreateVirtualDisplaySurface()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        com.example.auth.FirebaseBootstrap.ensureInitialized(applicationContext)
        repository = LinkMirrorRepository(applicationContext)
        preferences = DevicePreferences(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProjectionAndService()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || projectionData == null) {
            ScreenCaptureBus.setScreenShareRunning(false, "Missing screen capture permission")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            val projection = mgr?.getMediaProjection(resultCode, projectionData)
            if (projection == null) {
                ScreenCaptureBus.setScreenShareRunning(false, "Failed to obtain MediaProjection")
                stopSelf()
                return START_NOT_STICKY
            }

            cleanupCaptureResources()
            acquireWakeLock()
            mediaProjection = projection
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            computeInitialCaptureMetrics()
            createVirtualDisplayAndReader(projection)
            ScreenCaptureBus.setScreenShareRunning(true)

            startSessionObservers()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen share service: ${e.message}", e)
            cleanupCaptureResources()
            ScreenCaptureBus.setScreenShareRunning(
                false,
                e.localizedMessage ?: "Screen capture permission expired — tap Start again"
            )
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun computeInitialCaptureMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (fullW, fullH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
        val scale = 540f / maxOf(fullW, fullH).coerceAtLeast(1)
        captureWidth = (fullW * scale).toInt().coerceAtLeast(240)
        captureHeight = (fullH * scale).toInt().coerceAtLeast(240)
        captureDensityDpi = resources.displayMetrics.densityDpi
    }

    private fun createVirtualDisplayAndReader(projection: MediaProjection) {
        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ imgReader ->
            try {
                var pendingSession: RemoteSession? = null
                var pendingMode: com.example.data.StreamMode = streamMode
                var pendingBitmap: Bitmap? = null
                var pendingFingerprint = 0L
                var isIdleCacheRefresh = false

                synchronized(captureLock) {
                    if (imageReader !== imgReader) return@setOnImageAvailableListener
                    val session = activeSession
                    if (session == null) {
                        val nowIdle = System.currentTimeMillis()
                        val shouldRefreshCache = lastEncodedFrameBase64 == null || (nowIdle - lastIdleCacheAtMs >= 2000L)
                        if (shouldRefreshCache && isUploadingFrame.compareAndSet(false, true)) {
                            val img = imgReader.acquireLatestImage()
                            if (img == null) {
                                isUploadingFrame.set(false)
                                return@setOnImageAvailableListener
                            }
                            val bmp = try {
                                imageToBitmap(img)
                            } finally {
                                img.close()
                            }
                            if (bmp != null) {
                                lastIdleCacheAtMs = nowIdle
                                pendingBitmap = bmp
                                isIdleCacheRefresh = true
                            } else {
                                isUploadingFrame.set(false)
                            }
                        } else {
                            imgReader.acquireLatestImage()?.close()
                        }
                        return@synchronized
                    }

                    if (session.status != SessionStatus.ACTIVE.name &&
                        !(session.alwaysAllow && session.status == SessionStatus.REQUESTED.name)
                    ) {
                        imgReader.acquireLatestImage()?.close()
                        return@synchronized
                    }

                    val mode = streamMode
                    val now = System.currentTimeMillis()
                    if (now - lastFrameSentAtMs < mode.intervalMs || !isUploadingFrame.compareAndSet(false, true)) {
                        imgReader.acquireLatestImage()?.close()
                        return@synchronized
                    }

                    val image = imgReader.acquireLatestImage()
                    if (image == null) {
                        isUploadingFrame.set(false)
                        return@synchronized
                    }

                    val bitmap = try {
                        imageToBitmap(image)
                    } finally {
                        image.close()
                    }

                    if (bitmap == null) {
                        isUploadingFrame.set(false)
                        return@synchronized
                    }

                    val fingerprint = computeQuickFingerprint(bitmap)
                    val elapsed = System.currentTimeMillis() - lastFrameSentAtMs
                    if (fingerprint == lastFrameFingerprint &&
                        session.latestFrameBase64.isNotBlank() &&
                        elapsed < KEEPALIVE_FRAME_MS
                    ) {
                        bitmap.recycle()
                        isUploadingFrame.set(false)
                        return@synchronized
                    }

                    pendingSession = session
                    pendingMode = mode
                    pendingBitmap = bitmap
                    pendingFingerprint = fingerprint
                }

                val bitmapToSend = pendingBitmap ?: return@setOnImageAvailableListener
                if (isIdleCacheRefresh) {
                    serviceScope.launch {
                        try {
                            val encoded = ImageCodec.encodeBitmapToBase64(
                                bitmap = bitmapToSend,
                                maxDimension = pendingMode.maxDimension,
                                initialQuality = pendingMode.jpegQuality
                            )
                            if (encoded.isNotBlank()) {
                                lastEncodedFrameBase64 = encoded
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Idle cache encode skipped: ${t.message}")
                        } finally {
                            if (!bitmapToSend.isRecycled) bitmapToSend.recycle()
                            isUploadingFrame.set(false)
                        }
                    }
                    return@setOnImageAvailableListener
                }

                val targetSession = pendingSession ?: run {
                    if (!bitmapToSend.isRecycled) bitmapToSend.recycle()
                    isUploadingFrame.set(false)
                    return@setOnImageAvailableListener
                }

                serviceScope.launch {
                    try {
                        val b64 = ImageCodec.encodeBitmapToBase64(
                            bitmap = bitmapToSend,
                            maxDimension = pendingMode.maxDimension,
                            initialQuality = pendingMode.jpegQuality
                        )
                        if (b64.isBlank()) {
                            return@launch
                        }
                        lastEncodedFrameBase64 = b64
                        val seq = frameSequence.incrementAndGet()
                        lastFrameFingerprint = pendingFingerprint
                        lastFrameSentAtMs = System.currentTimeMillis()
                        val res = repository.updateSessionFrame(
                            sessionId = targetSession.id,
                            frameBase64 = b64,
                            frameSeq = seq,
                            width = captureWidth.toLong(),
                            height = captureHeight.toLong(),
                            remoteControlEnabled = ScreenCaptureBus.isAccessibilityServiceEnabled(applicationContext),
                            alwaysAllow = alwaysAllow
                        )
                        if (res.isSuccess) {
                            ScreenCaptureBus.setLastError(null)
                            ScreenCaptureBus.recordFrameSent(seq)
                        } else {
                            ScreenCaptureBus.setLastError(
                                res.exceptionOrNull()?.localizedMessage ?: "Frame sync error — retrying"
                            )
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Frame pipeline error: ${t.message}")
                        ScreenCaptureBus.setLastError(t.localizedMessage ?: "Frame capture error")
                    } finally {
                        if (!bitmapToSend.isRecycled) bitmapToSend.recycle()
                        isUploadingFrame.set(false)
                    }
                }
            } catch (t: Throwable) {
                isUploadingFrame.set(false)
                Log.w(TAG, "ImageReader callback guarded: ${t.message}")
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = projection.createVirtualDisplay(
            "LinkMirrorDisplay",
            captureWidth,
            captureHeight,
            captureDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            Handler(Looper.getMainLooper())
        )
    }

    private fun computeQuickFingerprint(bitmap: Bitmap): Long {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0L
        var hash = 1125899906842597L
        val stepX = (bitmap.width / 12).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                hash = 31L * hash + bitmap.getPixel(x, y).toLong()
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            if (image.planes.isEmpty() || captureWidth <= 0 || captureHeight <= 0) return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val pixelStride = plane.pixelStride
            if (pixelStride <= 0) return null
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureWidth
            val extraCols = (rowPadding / pixelStride).coerceAtLeast(0)
            val rawBmp = Bitmap.createBitmap(
                captureWidth + extraCols,
                captureHeight,
                Bitmap.Config.ARGB_8888
            )
            rawBmp.copyPixelsFromBuffer(buffer)
            if (rawBmp.width != captureWidth) {
                val cropped = Bitmap.createBitmap(rawBmp, 0, 0, captureWidth, captureHeight)
                rawBmp.recycle()
                cropped
            } else {
                rawBmp
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Frame decode error: ${t.message}")
            null
        }
    }

    private fun recreateVirtualDisplaySurface() {
        synchronized(captureLock) {
            val projection = mediaProjection ?: return
            virtualDisplay?.release()
            imageReader?.close()
            createVirtualDisplayAndReader(projection)
        }
    }

    private fun startSessionObservers() {
        configJob?.cancel()
        sessionsJob?.cancel()
        heartbeatJob?.cancel()

        configJob = serviceScope.launch {
            preferences.configFlow.collectLatest { cfg ->
                hostDeviceId = cfg.deviceId
                hostDeviceName = cfg.deviceName
                alwaysAllow = cfg.alwaysAllowControl
                streamMode = cfg.streamMode
            }
        }

        sessionsJob = serviceScope.launch {
            val uid = Firebase.auth.currentUser?.uid ?: return@launch
            val config = preferences.getOrInitConfig(uid)
            hostDeviceId = config.deviceId
            hostDeviceName = config.deviceName
            alwaysAllow = config.alwaysAllowControl
            streamMode = config.streamMode
            syncDeviceShareState(active = true)

            repository.observeSessions(uid)
                .catch { e -> Log.w(TAG, "Session observer error: ${e.message}") }
                .collectLatest { sessions ->
                    val cfg = preferences.getOrInitConfig(uid)
                    hostDeviceId = cfg.deviceId
                    hostDeviceName = cfg.deviceName
                    alwaysAllow = cfg.alwaysAllowControl
                    streamMode = cfg.streamMode

                    val candidate = sessions.firstOrNull { s ->
                        s.hostUid == uid &&
                            s.hostDeviceId == hostDeviceId &&
                            (s.status == SessionStatus.ACTIVE.name ||
                                (alwaysAllow && s.status == SessionStatus.REQUESTED.name))
                    }
                    val previousId = activeSession?.id
                    activeSession = candidate
                    ScreenCaptureBus.setActiveHostSession(candidate?.id)

                    if (candidate != null) {
                        if (candidate.status == SessionStatus.REQUESTED.name && alwaysAllow) {
                            repository.updateSessionStatus(candidate.id, SessionStatus.ACTIVE)
                        }
                        if (candidate.id != previousId || candidate.latestFrameBase64.isBlank()) {
                            val instantFrame = captureSingleSnapshotBase64() ?: lastEncodedFrameBase64
                            if (!instantFrame.isNullOrBlank()) {
                                val seq = frameSequence.incrementAndGet()
                                repository.updateSessionFrame(
                                    sessionId = candidate.id,
                                    frameBase64 = instantFrame,
                                    frameSeq = seq,
                                    width = captureWidth.toLong(),
                                    height = captureHeight.toLong(),
                                    remoteControlEnabled = ScreenCaptureBus.isAccessibilityServiceEnabled(applicationContext),
                                    alwaysAllow = alwaysAllow
                                )
                                ScreenCaptureBus.recordFrameSent(seq)
                            }
                        }
                        if (candidate.id != observedCommandsSessionId) {
                            observedCommandsSessionId = candidate.id
                            observeHostSpecialCommands(candidate, uid)
                        }
                    } else {
                        observedCommandsSessionId = null
                        commandsJob?.cancel()
                    }
                }
        }

        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(45_000L)
                if (mediaProjection != null && Firebase.auth.currentUser != null) {
                    acquireWakeLock()
                    syncDeviceShareState(active = true)
                }
            }
        }
    }

    private fun observeHostSpecialCommands(session: RemoteSession, uid: String) {
        commandsJob?.cancel()
        commandsJob = serviceScope.launch {
            repository.observeCommands(session.id, uid)
                .catch { e -> Log.w(TAG, "Host special command error: ${e.message}") }
                .collectLatest { cmds ->
                    cmds.filter {
                        it.status == CommandStatus.PENDING.name &&
                            (it.type == CommandType.CAPTURE_SNAPSHOT.name || it.type == CommandType.WAKE_STREAM.name)
                    }.forEach { cmd ->
                        val liveSession = activeSession ?: session
                        when (CommandType.fromRaw(cmd.type)) {
                            CommandType.CAPTURE_SNAPSHOT -> {
                                val latestFrame = captureSingleSnapshotBase64() ?: lastEncodedFrameBase64
                                if (!latestFrame.isNullOrBlank()) {
                                    repository.uploadSharedPhoto(
                                        session = liveSession,
                                        senderDeviceName = hostDeviceName,
                                        title = "Screen Snapshot #${frameSequence.get() + 1}",
                                        source = PhotoSource.SCREEN_SNAPSHOT,
                                        imageBase64 = latestFrame
                                    )
                                    repository.markCommandStatus(liveSession.id, cmd.id, CommandStatus.EXECUTED)
                                } else {
                                    repository.markCommandStatus(liveSession.id, cmd.id, CommandStatus.FAILED)
                                }
                            }
                            CommandType.WAKE_STREAM -> {
                                lastFrameSentAtMs = 0L
                                val instantFrame = captureSingleSnapshotBase64() ?: lastEncodedFrameBase64
                                if (!instantFrame.isNullOrBlank()) {
                                    val seq = frameSequence.incrementAndGet()
                                    repository.updateSessionFrame(
                                        sessionId = liveSession.id,
                                        frameBase64 = instantFrame,
                                        frameSeq = seq,
                                        width = captureWidth.toLong(),
                                        height = captureHeight.toLong(),
                                        remoteControlEnabled = ScreenCaptureBus.isAccessibilityServiceEnabled(applicationContext),
                                        alwaysAllow = alwaysAllow
                                    )
                                    ScreenCaptureBus.recordFrameSent(seq)
                                }
                                repository.markCommandStatus(liveSession.id, cmd.id, CommandStatus.EXECUTED)
                            }
                            else -> Unit
                        }
                    }
                }
        }
    }

    private fun captureSingleSnapshotBase64(): String? {
        synchronized(captureLock) {
            val reader = imageReader ?: return activeSession?.latestFrameBase64?.takeIf { it.isNotBlank() }
            val img = try {
                reader.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return activeSession?.latestFrameBase64?.takeIf { it.isNotBlank() }
            return try {
                val bitmap = imageToBitmap(img) ?: return null
                try {
                    ImageCodec.encodeBitmapToBase64(bitmap, maxDimension = 720, initialQuality = 78)
                } finally {
                    bitmap.recycle()
                }
            } catch (_: Exception) {
                null
            } finally {
                img.close()
            }
        }
    }

    private suspend fun syncDeviceShareState(active: Boolean) {
        val uid = Firebase.auth.currentUser?.uid ?: return
        val cfg = preferences.getOrInitConfig(uid)
        val dm = resources.displayMetrics
        repository.registerOrUpdateDevice(
            deviceId = cfg.deviceId,
            deviceName = cfg.deviceName,
            pairingCode = cfg.pairingCode,
            roleMode = cfg.roleMode,
            alwaysAllowControl = cfg.alwaysAllowControl,
            screenShareActive = active,
            accessibilityActive = ScreenCaptureBus.isAccessibilityServiceEnabled(applicationContext),
            screenWidth = dm.widthPixels.toLong(),
            screenHeight = dm.heightPixels.toLong()
        )
    }

    private fun stopProjectionAndService() {
        cleanupCaptureResources()
        ScreenCaptureBus.setScreenShareRunning(false)
        ScreenCaptureBus.setActiveHostSession(null)
        serviceScope.launch {
            syncDeviceShareState(active = false)
            stopSelf()
        }
    }

    private fun cleanupCaptureResources() {
        synchronized(captureLock) {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        }
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenControl::ShareWakeLock").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            wl.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire skipped: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_screen_share),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenShareService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title_screen_share))
            .setContentText(getString(R.string.notification_body_screen_share))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_stop_sharing), stopIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        configJob?.cancel()
        sessionsJob?.cancel()
        commandsJob?.cancel()
        heartbeatJob?.cancel()
        cleanupCaptureResources()
        ScreenCaptureBus.setScreenShareRunning(false)
        ScreenCaptureBus.setActiveHostSession(null)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenShareService"
        private const val CHANNEL_ID = "linkmirror_screen_share"
        private const val NOTIFICATION_ID = 4401
        private const val KEEPALIVE_FRAME_MS = 25_000L

        const val ACTION_START = "com.example.action.START_SCREEN_SHARE"
        const val ACTION_STOP = "com.example.action.STOP_SCREEN_SHARE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            try {
                val intent = Intent(context, ScreenShareService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to launch ScreenShareService: ${e.message}", e)
                ScreenCaptureBus.setScreenShareRunning(
                    false,
                    e.localizedMessage ?: "Could not start screen sharing service"
                )
            }
        }

        fun stop(context: Context) {
            try {
                if (ScreenCaptureBus.telemetry.value.screenShareRunning) {
                    val intent = Intent(context, ScreenShareService::class.java).apply {
                        action = ACTION_STOP
                    }
                    context.startService(intent)
                } else {
                    context.stopService(Intent(context, ScreenShareService::class.java))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stop service failed gracefully: ${e.message}")
            }
        }
    }
}
