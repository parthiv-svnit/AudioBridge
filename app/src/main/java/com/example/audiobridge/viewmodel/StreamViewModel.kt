package com.example.audiobridge.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiobridge.service.AudioCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── StreamState ────────────────────────────────────────────────────────────────

/**
 * Sealed class for streaming state.
 * UI team observes [StreamViewModel.streamState] and switches on this.
 * Do not rename — Gemini's composables reference these by name.
 */
sealed class StreamState {
    object Idle                           : StreamState()
    data class Streaming(val ip: String) : StreamState()
    data class Error(val message: String): StreamState()
}

// ── ViewModel ──────────────────────────────────────────────────────────────────

/**
 * StreamViewModel — single source of truth for AudioBridge stream state.
 *
 * StateFlows exposed to UI (exact names — do not rename):
 *   val streamState: StateFlow<StreamState>
 *   val audioLevel:  StateFlow<Float>        — 0.0–1.0
 *   val lastError:   StateFlow<String?>
 *
 * Functions exposed to UI:
 *   fun onStartStream(ip: String, port: Int)
 *   fun onStopStream()
 *   fun getSavedIp(): String                 — last used IP from SharedPreferences
 *   val savedIp: String                      — same as above, property form
 *
 * MediaProjection coordination (called by MainActivity):
 *   var requestProjectionLauncher: (() -> Unit)?
 *   fun onMediaProjectionResult(resultCode: Int, data: Intent?)
 */
class StreamViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG           = "StreamViewModel"
        private const val PREFS_NAME    = "audiobridge_prefs"
        private const val PREF_LAST_IP  = "last_ip"
        private const val PREF_LAST_PORT = "last_port"
        const val DEFAULT_PORT          = 7355
    }

    // ─── Public StateFlows ────────────────────────────────────────────────────

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    /** Peak audio level 0.0–1.0, updated every ~10ms chunk by the service. */
    val audioLevel: StateFlow<Float> = AudioCaptureService.audioLevel.asStateFlow()

    /** Last error message from service, or null when healthy. */
    val lastError: StateFlow<String?> = AudioCaptureService.lastError.asStateFlow()

    // ─── Persisted preferences ────────────────────────────────────────────────

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Last used IP — restored across app restarts. Available as both property and function. */
    val savedIp: String get() = prefs.getString(PREF_LAST_IP, "") ?: ""
    fun getSavedIp(): String = savedIp  // Function form for Gemini compatibility

    val savedPort: Int get() = prefs.getInt(PREF_LAST_PORT, DEFAULT_PORT)

    // ─── Internal state ───────────────────────────────────────────────────────

    private var pendingIp: String = ""
    private var pendingPort: Int = DEFAULT_PORT

    /**
     * Set by MainActivity to trigger the system MediaProjection dialog.
     * Called from onStartStream after validation passes.
     *
     * Setup in MainActivity.onCreate():
     *   viewModel.requestProjectionLauncher = {
     *       mediaProjectionLauncher.launch(projManager.createScreenCaptureIntent())
     *   }
     */
    var requestProjectionLauncher: (() -> Unit)? = null

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Called by UI Start button.
     * Validates IP, saves to prefs, then triggers the MediaProjection dialog.
     * The service only starts after the user approves — see onMediaProjectionResult.
     */
    fun onStartStream(ip: String, port: Int) {
        val cleanIp = ip.trim()
        if (!isValidIp(cleanIp)) {
            _streamState.value = StreamState.Error("Invalid IP: $cleanIp")
            return
        }

        pendingIp = cleanIp
        pendingPort = port

        prefs.edit()
            .putString(PREF_LAST_IP, cleanIp)
            .putInt(PREF_LAST_PORT, port)
            .apply()

        val launcher = requestProjectionLauncher
        if (launcher == null) {
            _streamState.value = StreamState.Error("Projection launcher not registered")
            Log.e(TAG, "requestProjectionLauncher was not set by MainActivity")
            return
        }
        launcher()
    }

    /**
     * Called by UI Stop button and notification Stop action.
     */
    fun onStopStream() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        })
        _streamState.value = StreamState.Idle
        AudioCaptureService.lastError.value = null
        AudioCaptureService.audioLevel.value = 0f
        Log.i(TAG, "Stream stopped by user")
    }

    /**
     * Called by MainActivity after the MediaProjection system dialog returns.
     *
     * @param resultCode  Activity.RESULT_OK if user approved, RESULT_CANCELED otherwise
     * @param data        The Intent from the result — must be passed to MediaProjectionManager
     */
    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "MediaProjection denied by user (resultCode=$resultCode)")
            _streamState.value = StreamState.Error("Screen capture permission denied")
            return
        }

        val ctx = getApplication<Application>()
        val projManager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        val projection: MediaProjection = projManager.getMediaProjection(resultCode, data)

        val startIntent = Intent(ctx, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_MEDIA_PROJECTION, projection)
            putExtra(AudioCaptureService.EXTRA_TARGET_IP, pendingIp)
            putExtra(AudioCaptureService.EXTRA_TARGET_PORT, pendingPort)
        }
        ctx.startForegroundService(startIntent)

        _streamState.value = StreamState.Streaming(pendingIp)
        Log.i(TAG, "AudioCaptureService started → $pendingIp:$pendingPort")

        // Mirror service errors into streamState
        viewModelScope.launch {
            AudioCaptureService.lastError.collect { error ->
                if (error != null && _streamState.value is StreamState.Streaming) {
                    _streamState.value = StreamState.Error(error)
                }
            }
        }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        if (_streamState.value is StreamState.Streaming) {
            onStopStream()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } ?: false }
    }
}