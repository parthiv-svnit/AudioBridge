package com.example.audiobridge.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiobridge.service.AudioCaptureService
import com.example.audiobridge.ui.StreamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * StreamViewModel — single source of truth for AudioBridge stream state.
 */
class StreamViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG         = "StreamViewModel"
        private const val PREFS_NAME  = "audiobridge_prefs"
        private const val KEY_LAST_IP = "last_ip"
        private const val KEY_LAST_PORT = "last_port"
        private const val DEFAULT_PORT  = 7355
    }

    // ── Public StateFlows ───────────────

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    val audioLevel: StateFlow<Float> = AudioCaptureService.audioLevel
    val lastError: StateFlow<String?> = AudioCaptureService.lastError

    // ── Persisted state ────────────────────────────────────────────────────────

    private val prefs get() = getApplication<Application>()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val savedIp: String get() = prefs.getString(KEY_LAST_IP, "") ?: ""
    val savedPort: Int  get() = prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT)

    private var pendingIp   = ""
    private var pendingPort = DEFAULT_PORT

    // ── Public API ─────────────────────────────────────────────────────────────

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

        AudioCaptureService.lastError.value = null
        _needsProjectionRequest.value = true
    }

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

    private val _needsProjectionRequest = MutableStateFlow(false)
    val needsProjectionRequest: StateFlow<Boolean> = _needsProjectionRequest.asStateFlow()

    fun onProjectionRequestConsumed() {
        _needsProjectionRequest.value = false
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.w(TAG, "MediaProjection denied by user")
            _streamState.value = StreamState.Error("Screen capture permission denied")
            return
        }

        val ctx = getApplication<Application>()
        val startIntent = Intent(ctx, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(AudioCaptureService.EXTRA_TARGET_IP, pendingIp)
            putExtra(AudioCaptureService.EXTRA_TARGET_PORT, pendingPort)
        }

        ctx.startForegroundService(startIntent)
        _streamState.value = StreamState.Streaming(pendingIp)
        Log.i(TAG, "Service started — streaming to $pendingIp:$pendingPort")

        viewModelScope.launch {
            AudioCaptureService.lastError.collect { error ->
                if (error != null && _streamState.value is StreamState.Streaming) {
                    _streamState.value = StreamState.Error(error)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (_streamState.value is StreamState.Streaming) {
            onStopStream()
        }
    }
}