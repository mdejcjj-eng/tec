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
import com.google.firebase.Firebase
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
    private val processedIds = HashSet<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = LinkMirrorRepository(applicationContext)
        preferences = DevicePreferences(applicationContext)
        ScreenCaptureBus.setAccessibilityConnected(true)
        observeActiveSessionCommands()
        Log.i(TAG, "LinkMirrorAccessibilityService connected")
    }

    private fun observeActiveSessionCommands() {
        sessionObserverJob?.cancel()
        sessionObserverJob = scope.launch {
            ScreenCaptureBus.telemetry.collectLatest { state ->
                val sessionId = state.activeHostSessionId
                val uid = Firebase.auth.currentUser?.uid
                if (sessionId.isNullOrBlank() || uid.isNullOrBlank()) {
                    commandObserverJob?.cancel()
                    return@collectLatest
                }
                startListeningToCommands(sessionId, uid)
            }
        }
    }

    private fun startListeningToCommands(sessionId: String, uid: String) {
        commandObserverJob?.cancel()
        commandObserverJob = scope.launch {
            repository.observeCommands(sessionId, uid)
                .catch { e -> Log.w(TAG, "Command stream error: ${e.message}") }
                .collectLatest { commands ->
                    commands.filter {
                        it.status == CommandStatus.PENDING.name &&
                            it.type != CommandType.CAPTURE_SNAPSHOT.name &&
                            it.type != CommandType.WAKE_STREAM.name &&
                            !processedIds.contains(it.id)
                    }.forEach { cmd ->
                        processedIds.add(cmd.id)
                        if (processedIds.size > 500) {
                            processedIds.clear()
                        }
                        executeRemoteCommand(sessionId, cmd)
                    }
                }
        }
    }

    private fun executeRemoteCommand(sessionId: String, cmd: RemoteCommand) {
        val type = CommandType.fromRaw(cmd.type)
        val (w, h) = getDisplayPixelSize()
        val x = (cmd.normX.coerceIn(0.02, 0.98) * w).toFloat()
        val y = (cmd.normY.coerceIn(0.02, 0.98) * h).toFloat()
        val endX = (cmd.endNormX.coerceIn(0.02, 0.98) * w).toFloat()
        val endY = (cmd.endNormY.coerceIn(0.02, 0.98) * h).toFloat()

        when (type) {
            CommandType.BACK -> markResult(sessionId, cmd.id, performGlobalAction(GLOBAL_ACTION_BACK), type.name)
            CommandType.HOME -> markResult(sessionId, cmd.id, performGlobalAction(GLOBAL_ACTION_HOME), type.name)
            CommandType.RECENTS -> markResult(sessionId, cmd.id, performGlobalAction(GLOBAL_ACTION_RECENTS), type.name)
            CommandType.NOTIFICATIONS -> markResult(sessionId, cmd.id, performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS), type.name)
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
        val path = Path().apply {
            moveTo(startX, startY)
            if (startX != endX || startY != endY) {
                lineTo(endX, endY)
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
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
    }

    private fun dispatchDoubleTap(sessionId: String, commandId: String, x: Float, y: Float) {
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
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
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
