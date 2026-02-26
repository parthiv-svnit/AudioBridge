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

/**
 * MainActivity — entry point.
 *
 * Backend responsibilities (this file):
 *   - Register MediaProjection ActivityResultLauncher
 *   - Register runtime permission launcher (RECORD_AUDIO, POST_NOTIFICATIONS)
 *   - Wire ViewModel to launchers
 *   - Mount Gemini's Compose UI and pass ViewModel state down
 *
 * UI responsibilities (Gemini):
 *   - AudioBridgeTheme, AudioBridgeNavigation and all composables
 */
class MainActivity : ComponentActivity() {

    // ── ViewModel ─────────────────────────────────────────────────────────────
    private val viewModel: StreamViewModel by viewModels()

    // ── MediaProjection launcher ──────────────────────────────────────────────
    // Must be registered before onCreate completes (Android framework requirement).
    //
    // Flow:
    //   UI taps Start
    //   → viewModel.onStartStream(ip, port)
    //   → viewModel.requestProjectionLauncher() ← wired to this launcher below
    //   → system dialog appears
    //   → result arrives here
    //   → viewModel.onMediaProjectionResult(resultCode, data)
    //   → ViewModel creates MediaProjection and starts AudioCaptureService
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onMediaProjectionResult(result.resultCode, result.data)
    }

    // ── Permission launcher ───────────────────────────────────────────────────
    // Requests RECORD_AUDIO + POST_NOTIFICATIONS together.
    // Gemini's PermissionScreen fires onRequestPermissions → triggers this.
    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        onPermissionResult?.invoke(allGranted)
        onPermissionResult = null
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wire ViewModel's projection trigger to our registered launcher
        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        viewModel.requestProjectionLauncher = {
            mediaProjectionLauncher.launch(projManager.createScreenCaptureIntent())
        }

        setContent {
            AudioBridgeTheme {

                // Collect state from Claude's ViewModel
                val streamState by viewModel.streamState.collectAsState()
                val audioLevel  by viewModel.audioLevel.collectAsState()

                // Permission state — drives which screen Gemini's nav shows
                var hasPermissions by remember { mutableStateOf(checkInitialPermissions()) }

                // Mount Gemini's navigation tree
                AudioBridgeNavigation(
                    hasPermissions = hasPermissions,
                    streamState    = streamState,
                    audioLevel     = audioLevel,

                    // Claude's ViewModel exposes savedIp both as property and getSavedIp()
                    savedIp        = viewModel.getSavedIp(),

                    // ── Name bridge ───────────────────────────────────────────
                    // Gemini calls: onStartClick(ip, port)
                    // Claude has:   onStartStream(ip, port)
                    onStartClick   = { ip, port -> viewModel.onStartStream(ip, port) },

                    // Gemini calls: onStopClick()
                    // Claude has:   onStopStream()
                    onStopClick    = { viewModel.onStopStream() },

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
     * Returns true if all required runtime permissions are already granted.
     * Called once at startup to decide whether to show PermissionScreen.
     *
     * RECORD_AUDIO         — required by AudioPlaybackCaptureConfiguration
     * POST_NOTIFICATIONS   — required by foreground notification on Android 13+
     */
    private fun checkInitialPermissions(): Boolean {
        val recordAudio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // POST_NOTIFICATIONS only exists on Android 13+ (API 33).
        // On older versions it is implicitly granted — return true.
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
     * [onResult] is called with true only if every permission was granted.
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

    // ─── Notification Stop action ─────────────────────────────────────────────

    /**
     * Handle Stop action from the foreground notification if Activity is open.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == AudioCaptureService.ACTION_STOP) {
            viewModel.onStopStream()
        }
    }
}