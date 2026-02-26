package com.example.audiobridge.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
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
 * Sealed class for all possible streaming states.
 * Gemini's composables switch on this — do not rename variants or fields.
 */
sealed class StreamState {
    object Idle                             : StreamState()
    data class Streaming(val ip: String)   : StreamState()
    data class Error(val message: String)  : StreamState()
}

// ── ViewModel ──────────────────────────────────────────────────────────────────

/**
 * StreamViewModel — single source of truth for AudioBridge state.
 *
 * Exposed to UI (exact names — Gemini depends on these):
 *   val streamState: StateFlow<StreamState>
 *   val audioLevel:  StateFlow<Float>
 *   val lastError:   StateFlow<String?>
 *   fun onStartStream(ip: String, port: Int)
 *   fun onStopStream()
 *   fun getSavedIp(): String
 *   val savedIp: String
 *
 * Wired by MainActivity (before setContent):
 *   var requestProjectionLauncher: (() -> Unit)?
 *   fun onMediaProjectionResult(resultCode: Int, data: Intent?)
 */
class StreamViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG            = "StreamViewModel"
        private const val PREFS_NAME     = "audiobridge_prefs"
        private const val PREF_LAST_IP   = "last_ip"
        private const val PREF_LAST_PORT = "last_port"
        const val DEFAULT_PORT           = 7355
    }

    // ─── Public StateFlows ────────────────────────────────────────────────────

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    /** 0.0–1.0 peak level from AudioCaptureService, updated every ~10ms. */
    val audioLevel: StateFlow<Float> = AudioCaptureService.audioLevel.asStateFlow()

    /** Last error from service, null when healthy. */
    val lastError: StateFlow<String?> = AudioCaptureService.lastError.asStateFlow()

    // ─── Persistence ──────────────────────────────────────────────────────────

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val savedIp: String get() = prefs.getString(PREF_LAST_IP, "") ?: ""
    fun getSavedIp(): String = savedIp   // function form — Gemini uses this

    // ─── Internal ────────────────────────────────────────────────────────────

    private var pendingIp: String = ""
    private var pendingPort: Int = DEFAULT_PORT

    /**
     * Set by MainActivity.onCreate() BEFORE setContent{}.
     * Calling this lambda triggers the MediaProjection system dialog.
     */
    var requestProjectionLauncher: (() -> Unit)? = null

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Called by Gemini's Start button.
     * Validates IP, saves to prefs, triggers the MediaProjection dialog.
     * Stream does NOT start until user approves — see onMediaProjectionResult.
     */
    fun onStartStream(ip: String, port: Int) {
        val clean = ip.trim()
        if (!isValidIp(clean)) {
            _streamState.value = StreamState.Error("Invalid IP address: $clean")
            return
        }

        pendingIp   = clean
        pendingPort = port

        prefs.edit()
            .putString(PREF_LAST_IP, clean)
            .putInt(PREF_LAST_PORT, port)
            .apply()

        val launcher = requestProjectionLauncher
        if (launcher == null) {
            _streamState.value = StreamState.Error("Launcher not set — call from MainActivity")
            Log.e(TAG, "requestProjectionLauncher was null")
            return
        }
        launcher()
    }

    /**
     * Called by Gemini's Stop button and notification Stop action.
     */
    fun onStopStream() {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP
            }
        )
        _streamState.value = StreamState.Idle
        AudioCaptureService.lastError.value = null
        AudioCaptureService.audioLevel.value = 0f
        Log.i(TAG, "Stream stopped")
    }

    /**
     * Called by MainActivity after the MediaProjection system dialog returns.
     *
     * On approval:
     *   1. Obtains MediaProjection from system
     *   2. Stores it in AudioCaptureService.pendingProjection (static handoff)
     *   3. Starts AudioCaptureService via Intent (IP + port only, no projection in intent)
     *
     * On denial: sets Error state.
     */
    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        // android.app.Activity.RESULT_OK = -1
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.w(TAG, "MediaProjection denied (resultCode=$resultCode)")
            _streamState.value = StreamState.Error("Screen capture permission denied")
            return
        }

        val ctx = getApplication<Application>()
        val projManager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        // Obtain the projection token
        val projection = projManager.getMediaProjection(resultCode, data)

        // Static handoff — MediaProjection is NOT Parcelable so we can't put it
        // in an Intent. Service reads and clears this in onStartCommand().
        AudioCaptureService.pendingProjection = projection

        // Start service with only the primitive params in the Intent
        val startIntent = Intent(ctx, AudioCaptureService::class.java).apply {
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
        return parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } ?: false }
    }
}