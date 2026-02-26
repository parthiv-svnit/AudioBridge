package com.example.audiobridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.example.audiobridge.service.AudioCaptureService
import com.example.audiobridge.ui.AudioBridgeNavigation
import com.example.audiobridge.ui.AudioBridgeTheme
import com.example.audiobridge.viewmodel.StreamViewModel

class MainActivity : ComponentActivity() {

    // ── Claude's ViewModel ────────────────────────────────────────────────────
    private val viewModel: StreamViewModel by viewModels()

    // ── MediaProjection launcher (Claude's backend requirement) ───────────────
    // Must be registered before onCreate completes — Android framework rule.
    // Flow: user taps Start → onStartStream() → requestProjectionLauncher()
    //       → system dialog appears → this callback fires with result
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Forward result directly to Claude's ViewModel
        viewModel.onMediaProjectionResult(result.resultCode, result.data)
    }

    // ── Runtime permission launcher ───────────────────────────────────────────
    // Requests RECORD_AUDIO + POST_NOTIFICATIONS in one shot.
    // Gemini's PermissionScreen calls onRequestPermissions → triggers this.
    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        onPermissionResult?.invoke(allGranted)
        onPermissionResult = null
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wire Claude's ViewModel projection launcher callback to our registered launcher
        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        viewModel.requestProjectionLauncher = {
            mediaProjectionLauncher.launch(projManager.createScreenCaptureIntent())
        }

        setContent {
            AudioBridgeTheme {

                // ── Collect states from Claude's ViewModel ────────────────────
                val streamState by viewModel.streamState.collectAsState()
                val audioLevel  by viewModel.audioLevel.collectAsState()

                // ── Permission state (drives which screen Gemini's nav shows) ─
                var hasPermissions by remember { mutableStateOf(checkInitialPermissions()) }

                // ── Mount Gemini's navigation ─────────────────────────────────
                AudioBridgeNavigation(
                    hasPermissions = hasPermissions,
                    streamState    = streamState,
                    audioLevel     = audioLevel,

                    // Claude's ViewModel exposes savedIp as a property
                    savedIp        = viewModel.savedIp,

                    // ── Name bridge: Gemini → Claude ──────────────────────────
                    // Gemini calls:  onStartClick(ip, port)
                    // Claude has:    onStartStream(ip, port)
                    onStartClick   = { ip, port ->
                        viewModel.onStartStream(ip, port)
                        // MediaProjection dialog is triggered inside onStartStream
                        // via requestProjectionLauncher (wired above).
                        // AudioCaptureService starts only after user approves.
                    },

                    // Gemini calls:  onStopClick()
                    // Claude has:    onStopStream()
                    onStopClick    = {
                        viewModel.onStopStream()
                    },

                    // Gemini's PermissionScreen calls this when user taps "Grant"
                    onRequestPermissions = {
                        requestRequiredPermissions { granted ->
                            hasPermissions = granted
                        }
                    }
                )
            }
        }
    }

    // ─── Permission helpers ───────────────────────────────────────────────────

    /**
     * Check whether all required permissions are already granted.
     * Called once on startup — used by Gemini to decide which screen shows first.
     *
     * Required permissions:
     *   RECORD_AUDIO       — needed by AudioRecord / AudioPlaybackCaptureConfiguration
     *   POST_NOTIFICATIONS — foreground service notification (Android 13+ only)
     */
    private fun checkInitialPermissions(): Boolean {
        val recordAudio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // POST_NOTIFICATIONS only exists on Android 13+ (API 33+)
        // On older versions it is implicitly granted — never deny it here
        val postNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return recordAudio && postNotifications
    }

    /**
     * Launch the runtime permission dialog for all required permissions.
     * [onResult] receives true only if every permission was granted.
     *
     * Called when user taps "Grant Permissions" on Gemini's PermissionScreen.
     */
    private fun requestRequiredPermissions(onResult: (Boolean) -> Unit) {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        onPermissionResult = onResult
        permissionLauncher.launch(permissions)
    }

    // ─── Intent handling ──────────────────────────────────────────────────────

    /**
     * Handles Stop action from foreground notification while Activity is open.
     * Claude's service broadcasts ACTION_STOP; we forward to ViewModel.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == AudioCaptureService.ACTION_STOP) {
            viewModel.onStopStream()
        }
    }
}