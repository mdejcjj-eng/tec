package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.data.CommandStatus
import com.example.data.CommandType
import com.example.data.DevicePreferences
import com.example.data.LinkMirrorRepository
import com.example.data.RemoteCommand
import com.example.data.SessionStatus
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LinkMirrorAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: LinkMirrorRepository
    private lateinit var preferences: DevicePreferences
    private var sessionObserverJob: Job? = null
    private var commandObserverJob: Job? = null
    private val processedIds = LinkedHashSet<String>()
    private val processedQueue = ArrayDeque<String>()
    private var currentCommandSessionId: String? = null

    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            currentCommandSessionId = null
            sessionObserverJob?.cancel()
            commandObserverJob?.cancel()
        } else {
            observeActiveSessionCommands(uid)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = LinkMirrorRepository(applicationContext)
        preferences = DevicePreferences(applicationContext)
        ScreenCaptureBus.setAccessibilityConnected(true)
        try {
            Firebase.auth.addAuthStateListener(authListener)
            Firebase.auth.currentUser?.uid?.let { observeActiveSessionCommands(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Auth setup skipped: ${e.message}")
        }
        Log.i(TAG, "LinkMirrorAccessibilityService connected")
    }

    private fun observeActiveSessionCommands(uid: String) {
        sessionObserverJob?.cancel()
        sessionObserverJob = scope.launch {
            val cfg = preferences.getOrInitConfig(uid)
            repository.observeSessions(uid)
                .catch { e -> Log.w(TAG, "Session observer error: ${e.message}") }
                .collectLatest { sessions ->
                    val hostSession = sessions.firstOrNull { s ->
                        s.hostUid == uid &&
                            s.hostDeviceId == cfg.deviceId &&
                            (s.status == SessionStatus.ACTIVE.name ||
                                (cfg.alwaysAllowControl && s.status == SessionStatus.REQUESTED.name))
                    }
                    val targetId = hostSession?.id
                    if (targetId != currentCommandSessionId) {
                        currentCommandSessionId = targetId
                        if (targetId.isNullOrBlank()) {
                            commandObserverJob?.cancel()
                        } else {
                            startListeningToCommands(targetId, uid)
                        }
                    }
                }
        }
    }

    private fun startListeningToCommands(sessionId: String, uid: String) {
        commandObserverJob?.cancel()
        commandObserverJob = scope.launch {
            repository.observeCommands(sessionId, uid)
                .catch { e -> Log.w(TAG, "Command stream error: ${e.message}") }
                .collectLatest { commands ->
                    val nowMs = System.currentTimeMillis()
                    commands.filter {
                        val createdMs = it.createdAt?.toDate()?.time
                        val isFresh = createdMs == null || (nowMs - createdMs) <= 120_000L
                        isFresh &&
                            it.status == CommandStatus.PENDING.name &&
                            it.type != CommandType.CAPTURE_SNAPSHOT.name &&
                            it.type != CommandType.WAKE_STREAM.name &&
                            !processedIds.contains(it.id)
                    }.forEach { cmd ->
                        processedIds.add(cmd.id)
                        processedQueue.addLast(cmd.id)
                        if (processedQueue.size > 500) {
                            val oldest = processedQueue.removeFirst()
                            processedIds.remove(oldest)
                        }
                        executeRemoteCommand(sessionId, cmd)
                    }
                }
        }
    }

    private fun executeRemoteCommand(sessionId: String, cmd: RemoteCommand) {
        val type = CommandType.fromRaw(cmd.type)
        val (rawW, rawH) = getDisplayPixelSize()
        val w = rawW.coerceAtLeast(100)
        val h = rawH.coerceAtLeast(100)
        val x = (cmd.normX.coerceIn(0.02, 0.98) * w).toFloat()
        val y = (cmd.normY.coerceIn(0.02, 0.98) * h).toFloat()
        val endX = (cmd.endNormX.coerceIn(0.02, 0.98) * w).toFloat()
        val endY = (cmd.endNormY.coerceIn(0.02, 0.98) * h).toFloat()

        when (type) {
            CommandType.BACK -> safePerformGlobalAction(sessionId, cmd.id, GLOBAL_ACTION_BACK, type.name)
            CommandType.HOME -> safePerformGlobalAction(sessionId, cmd.id, GLOBAL_ACTION_HOME, type.name)
            CommandType.RECENTS -> safePerformGlobalAction(sessionId, cmd.id, GLOBAL_ACTION_RECENTS, type.name)
            CommandType.NOTIFICATIONS -> safePerformGlobalAction(sessionId, cmd.id, GLOBAL_ACTION_NOTIFICATIONS, type.name)
            CommandType.TAP -> dispatchSingleStroke(sessionId, cmd.id, x, y, x, y, 60L, type.name)
            CommandType.LONG_PRESS -> dispatchSingleStroke(sessionId, cmd.id, x, y, x, y, 650L, type.name)
            CommandType.DOUBLE_TAP -> dispatchDoubleTap(sessionId, cmd.id, x, y)
            CommandType.SWIPE -> dispatchSingleStroke(sessionId, cmd.id, x, y, endX, endY, 320L, type.name)
            CommandType.SCROLL_UP -> dispatchSingleStroke(
                sessionId, cmd.id, w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f, 340L, type.name
            )
            CommandType.SCROLL_DOWN -> dispatchSingleStroke(
                sessionId, cmd.id, w * 0.5f, h * 0.28f, w * 0.5f, h * 0.72f, 340L, type.name
            )
            CommandType.WAKE_STREAM, CommandType.CAPTURE_SNAPSHOT -> Unit
        }
    }

    private fun safePerformGlobalAction(sessionId: String, commandId: String, action: Int, label: String) {
        val ok = try {
            performGlobalAction(action)
        } catch (e: Exception) {
            Log.w(TAG, "performGlobalAction failed: ${e.message}")
            false
        }
        markResult(sessionId, commandId, ok, label)
    }

    private fun dispatchSingleStroke(
        sessionId: String,
        commandId: String,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        label: String
    ) {
        try {
            val path = Path().apply {
                moveTo(startX, startY)
                if (startX != endX || startY != endY) {
                    lineTo(endX, endY)
                }
            }
            val safeDuration = durationMs.coerceIn(10L, 60_000L)
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    markResult(sessionId, commandId, true, label)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    markResult(sessionId, commandId, false, label)
                }
            }, null)
            if (!accepted) {
                markResult(sessionId, commandId, false, label)
            }
        } catch (e: Exception) {
            Log.w(TAG, "dispatchSingleStroke failed: ${e.message}")
            markResult(sessionId, commandId, false, label)
        }
    }

    private fun dispatchDoubleTap(sessionId: String, commandId: String, x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val first = GestureDescription.StrokeDescription(path, 0L, 50L)
            val second = GestureDescription.StrokeDescription(path, 110L, 50L)
            val gesture = GestureDescription.Builder().addStroke(first).addStroke(second).build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    markResult(sessionId, commandId, true, "DOUBLE_TAP")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    markResult(sessionId, commandId, false, "DOUBLE_TAP")
                }
            }, null)
            if (!accepted) {
                markResult(sessionId, commandId, false, "DOUBLE_TAP")
            }
        } catch (e: Exception) {
            Log.w(TAG, "dispatchDoubleTap failed: ${e.message}")
            markResult(sessionId, commandId, false, "DOUBLE_TAP")
        }
    }

    private fun markResult(sessionId: String, commandId: String, ok: Boolean, label: String) {
        if (ok) {
            ScreenCaptureBus.recordCommandExecuted(label)
        }
        scope.launch(Dispatchers.IO) {
            repository.markCommandStatus(
                sessionId = sessionId,
                commandId = commandId,
                status = if (ok) CommandStatus.EXECUTED else CommandStatus.FAILED
            )
        }
    }

    private fun getDisplayPixelSize(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.maximumWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                dm.widthPixels to dm.heightPixels
            }
        } catch (_: Exception) {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Minimal footprint: we do not read window hierarchy content.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        try {
            Firebase.auth.removeAuthStateListener(authListener)
        } catch (_: Exception) {
        }
        ScreenCaptureBus.setAccessibilityConnected(false)
        sessionObserverJob?.cancel()
        commandObserverJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LinkMirrorAccessibility"
    }
}
