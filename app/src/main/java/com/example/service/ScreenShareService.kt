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

    private lateinit var repository: LinkMirrorRepository
    private lateinit var preferences: DevicePreferences
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

    @Volatile
    private var activeSession: RemoteSession? = null

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

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, projectionData)
        if (projection == null) {
            ScreenCaptureBus.setScreenShareRunning(false, "Failed to obtain MediaProjection")
            stopSelf()
            return START_NOT_STICKY
        }

        cleanupCaptureResources()
        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        computeInitialCaptureMetrics()
        createVirtualDisplayAndReader(projection)
        ScreenCaptureBus.setScreenShareRunning(true)

        startSessionObservers()
        return START_NOT_STICKY
    }

    private fun computeInitialCaptureMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (fullW, fullH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = resources.displayMetrics
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
            val session = activeSession
            if (session == null) {
                if (lastEncodedFrameBase64 == null && isUploadingFrame.compareAndSet(false, true)) {
                    val img = imgReader.acquireLatestImage()
                    if (img == null) {
                        isUploadingFrame.set(false)
                        return@setOnImageAvailableListener
                    }
                    val bmp = imageToBitmap(img)
                    img.close()
                    if (bmp != null) {
                        serviceScope.launch {
                            try {
                                lastEncodedFrameBase64 = ImageCodec.encodeBitmapToBase64(
                                    bitmap = bmp,
                                    maxDimension = streamMode.maxDimension,
                                    initialQuality = streamMode.jpegQuality
                                )
                            } finally {
                                bmp.recycle()
                                isUploadingFrame.set(false)
                            }
                        }
                    } else {
                        isUploadingFrame.set(false)
                    }
                } else {
                    imgReader.acquireLatestImage()?.close()
                }
                return@setOnImageAvailableListener
            }
            if (session.status != SessionStatus.ACTIVE.name &&
                !(session.alwaysAllow && session.status == SessionStatus.REQUESTED.name)
            ) {
                val skip = imgReader.acquireLatestImage()
                skip?.close()
                return@setOnImageAvailableListener
            }

            val mode = streamMode
            val now = System.currentTimeMillis()
            if (now - lastFrameSentAtMs < mode.intervalMs || !isUploadingFrame.compareAndSet(false, true)) {
                val drop = imgReader.acquireLatestImage()
                drop?.close()
                return@setOnImageAvailableListener
            }

            val image = imgReader.acquireLatestImage()
            if (image == null) {
                isUploadingFrame.set(false)
                return@setOnImageAvailableListener
            }

            val bitmap = try {
                imageToBitmap(image)
            } finally {
                image.close()
            }

            if (bitmap == null) {
                isUploadingFrame.set(false)
                return@setOnImageAvailableListener
            }

            val fingerprint = computeQuickFingerprint(bitmap)
            val elapsed = System.currentTimeMillis() - lastFrameSentAtMs
            if (fingerprint == lastFrameFingerprint && elapsed < KEEPALIVE_FRAME_MS) {
                bitmap.recycle()
                isUploadingFrame.set(false)
                return@setOnImageAvailableListener
            }

            serviceScope.launch {
                try {
                    val b64 = ImageCodec.encodeBitmapToBase64(
                        bitmap = bitmap,
                        maxDimension = mode.maxDimension,
                        initialQuality = mode.jpegQuality
                    )
                    lastEncodedFrameBase64 = b64
                    val seq = frameSequence.incrementAndGet()
                    lastFrameFingerprint = fingerprint
                    lastFrameSentAtMs = System.currentTimeMillis()
                    repository.updateSessionFrame(
                        sessionId = session.id,
                        frameBase64 = b64,
                        frameSeq = seq,
                        width = captureWidth.toLong(),
                        height = captureHeight.toLong(),
                        remoteControlEnabled = ScreenCaptureBus.isAccessibilityServiceEnabled(applicationContext),
                        alwaysAllow = alwaysAllow
                    )
                    ScreenCaptureBus.recordFrameSent(seq)
                } finally {
                    bitmap.recycle()
                    isUploadingFrame.set(false)
                }
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
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureWidth
            val rawBmp = Bitmap.createBitmap(
                captureWidth + rowPadding / pixelStride,
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
        } catch (e: Exception) {
            Log.w(TAG, "Frame decode error: ${e.message}")
            null
        }
    }

    private fun recreateVirtualDisplaySurface() {
        val projection = mediaProjection ?: return
        virtualDisplay?.release()
        imageReader?.close()
        createVirtualDisplayAndReader(projection)
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
            val config = preferences.getOrInitConfig()
            hostDeviceId = config.deviceId
            hostDeviceName = config.deviceName
            alwaysAllow = config.alwaysAllowControl
            streamMode = config.streamMode
            syncDeviceShareState(active = true)

            val uid = Firebase.auth.currentUser?.uid ?: return@launch
            repository.observeSessions(uid)
                .catch { e -> Log.w(TAG, "Session observer error: ${e.message}") }
                .collectLatest { sessions ->
                    val cfg = preferences.getOrInitConfig()
                    alwaysAllow = cfg.alwaysAllowControl
                    streamMode = cfg.streamMode

                    val candidate = sessions.firstOrNull { s ->
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
                        observeHostSpecialCommands(candidate, uid)
                    } else {
                        commandsJob?.cancel()
                    }
                }
        }

        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(45_000L)
                if (mediaProjection != null && Firebase.auth.currentUser != null) {
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
                        when (CommandType.fromRaw(cmd.type)) {
                            CommandType.CAPTURE_SNAPSHOT -> {
                                val latestFrame = captureSingleSnapshotBase64()
                                if (latestFrame != null) {
                                    repository.uploadSharedPhoto(
                                        session = session,
                                        senderDeviceName = hostDeviceName,
                                        title = "Screen Snapshot #${frameSequence.get() + 1}",
                                        source = PhotoSource.SCREEN_SNAPSHOT,
                                        imageBase64 = latestFrame
                                    )
                                    repository.markCommandStatus(session.id, cmd.id, CommandStatus.EXECUTED)
                                } else {
                                    repository.markCommandStatus(session.id, cmd.id, CommandStatus.FAILED)
                                }
                            }
                            CommandType.WAKE_STREAM -> {
                                lastFrameSentAtMs = 0L
                                repository.markCommandStatus(session.id, cmd.id, CommandStatus.EXECUTED)
                            }
                            else -> Unit
                        }
                    }
                }
        }
    }

    private fun captureSingleSnapshotBase64(): String? {
        val reader = imageReader ?: return activeSession?.latestFrameBase64?.takeIf { it.isNotBlank() }
        val img = reader.acquireLatestImage() ?: return activeSession?.latestFrameBase64?.takeIf { it.isNotBlank() }
        return try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureWidth
            val rawBmp = Bitmap.createBitmap(
                captureWidth + rowPadding / pixelStride,
                captureHeight,
                Bitmap.Config.ARGB_8888
            )
            rawBmp.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (rawBmp.width != captureWidth) {
                val c = Bitmap.createBitmap(rawBmp, 0, 0, captureWidth, captureHeight)
                rawBmp.recycle()
                c
            } else {
                rawBmp
            }
            val encoded = ImageCodec.encodeBitmapToBase64(cropped, maxDimension = 720, initialQuality = 78)
            cropped.recycle()
            encoded
        } catch (_: Exception) {
            null
        } finally {
            img.close()
        }
    }

    private suspend fun syncDeviceShareState(active: Boolean) {
        if (Firebase.auth.currentUser == null) return
        val cfg = preferences.getOrInitConfig()
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
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
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
        private const val KEEPALIVE_FRAME_MS = 4000L

        const val ACTION_START = "com.example.action.START_SCREEN_SHARE"
        const val ACTION_STOP = "com.example.action.STOP_SCREEN_SHARE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
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
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
