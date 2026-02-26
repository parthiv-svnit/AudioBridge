package com.audiobridge.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiobridge.service.AudioCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── State model ────────────────────────────────────────────────────────────────

/**
 * Sealed class representing the streaming state.
 * UI team binds to this — do not rename variants.
 */
sealed class StreamState {
    object Idle                              : StreamState()
    data class Streaming(val ip: String)    : StreamState()
    data class Error(val message: String)   : StreamState()
}

// ── ViewModel ──────────────────────────────────────────────────────────────────

/**
 * StreamViewModel — single source of truth for AudioBridge stream state.
 *
 * Exposes exact StateFlow names the UI team (Gemini) depends on:
 *   - streamState
 *   - audioLevel
 *   - lastError
 *
 * Functions the UI team calls:
 *   - onStartStream(ip, port)
 *   - onStopStream()
 *
 * MediaProjection lifecycle:
 *   - MainActivity calls onMediaProjectionResult() after the system prompt
 *   - ViewModel holds the pending ip/port until projection arrives
 */
class StreamViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG         = "StreamViewModel"
        private const val PREFS_NAME  = "audiobridge_prefs"
        private const val KEY_LAST_IP = "last_ip"
        private const val KEY_LAST_PORT = "last_port"
        private const val DEFAULT_PORT  = 7355
    }

    // ── Public StateFlows (UI team depends on these exact names) ───────────────

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    /**
     * Peak audio amplitude, 0.0–1.0. Updated every ~10ms chunk.
     * Sourced from AudioCaptureService.audioLevel companion StateFlow.
     */
    val audioLevel: StateFlow<Float> = AudioCaptureService.audioLevel

    /**
     * Last error message, or null if no error.
     * Sourced from AudioCaptureService.lastError companion StateFlow.
     */
    val lastError: StateFlow<String?> = AudioCaptureService.lastError

    // ── Persisted state ────────────────────────────────────────────────────────

    private val prefs get() = getApplication<Application>()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Last successfully used IP — restored on next launch. */
    val savedIp: String get() = prefs.getString(KEY_LAST_IP, "") ?: ""
    val savedPort: Int  get() = prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT)

    // ── Pending stream params (set before MediaProjection prompt) ──────────────

    private var pendingIp   = ""
    private var pendingPort = DEFAULT_PORT

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Called by UI when user taps START.
     * Persists ip/port, then requests MediaProjection via MainActivity.
     * Actual service start happens in [onMediaProjectionResult].
     *
     * @param ip   PC's local IP address
     * @param port UDP port (default 7355)
     */
    fun onStartStream(ip: String, port: Int) {
        if (_streamState.value is StreamState.Streaming) {
            Log.w(TAG, "Already streaming — ignoring start request")
            return
        }

        if (ip.isBlank()) {
            _streamState.value = StreamState.Error("Please enter a PC IP address")
            return
        }

        pendingIp   = ip.trim()
        pendingPort = port

        prefs.edit()
            .putString(KEY_LAST_IP, pendingIp)
            .putInt(KEY_LAST_PORT, pendingPort)
            .apply()

        // Clear previous error
        AudioCaptureService.lastError.value = null

        // Signal MainActivity to launch MediaProjection system prompt
        // MainActivity observes needsProjectionRequest and calls the launcher
        _needsProjectionRequest.value = true
    }

    /**
     * Called by UI when user taps STOP (or notification stop action).
     */
    fun onStopStream() {
        val ctx = getApplication<Application>()
        val stopIntent = Intent(ctx, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        ctx.startService(stopIntent)
        _streamState.value = StreamState.Idle
        AudioCaptureService.audioLevel.value = 0f
        Log.i(TAG, "Stream stopped by user")
    }

    // ── MediaProjection coordination ───────────────────────────────────────────

    // MainActivity observes this to know when to fire the projection launcher
    private val _needsProjectionRequest = MutableStateFlow(false)
    val needsProjectionRequest: StateFlow<Boolean> = _needsProjectionRequest.asStateFlow()

    /** Called by MainActivity after it consumes the projection request. */
    fun onProjectionRequestConsumed() {
        _needsProjectionRequest.value = false
    }

    /**
     * Called by MainActivity with the MediaProjection obtained from system.
     * If null, user denied the prompt.
     */
    fun onMediaProjectionResult(projection: MediaProjection?) {
        if (projection == null) {
            Log.w(TAG, "MediaProjection denied by user")
            _streamState.value = StreamState.Error("Screen capture permission denied")
            return
        }

        val ctx = getApplication<Application>()
        val startIntent = Intent(ctx, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_MEDIA_PROJECTION, projection)
            putExtra(AudioCaptureService.EXTRA_TARGET_IP, pendingIp)
            putExtra(AudioCaptureService.EXTRA_TARGET_PORT, pendingPort)
        }

        ctx.startForegroundService(startIntent)
        _streamState.value = StreamState.Streaming(pendingIp)
        Log.i(TAG, "Service started — streaming to $pendingIp:$pendingPort")

        // Watch for errors from the service
        viewModelScope.launch {
            AudioCaptureService.lastError.collect { error ->
                if (error != null && _streamState.value is StreamState.Streaming) {
                    _streamState.value = StreamState.Error(error)
                }
            }
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        if (_streamState.value is StreamState.Streaming) {
            onStopStream()
        }
    }
}