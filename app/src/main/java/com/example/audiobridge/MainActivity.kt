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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import com.example.audiobridge.service.AudioCaptureService
import com.example.audiobridge.ui.AudioBridgeNavigation
import com.example.audiobridge.ui.AudioBridgeTheme
import com.example.audiobridge.viewmodel.StreamViewModel

/**
 * MainActivity — app entry point.
 *
 * Backend responsibilities (this file):
 *   - Register MediaProjection launcher (before onCreate)
 *   - Register runtime permission launcher (before onCreate)
 *   - Wire both launchers to ViewModel
 *   - Mount Gemini's Compose UI tree
 *
 * UI responsibilities (Gemini):
 *   - AudioBridgeTheme { ... }
 *   - AudioBridgeNavigation(...)
 *   - All composable screens
 */
class MainActivity : ComponentActivity() {

    private val viewModel: StreamViewModel by viewModels()

    // ── MediaProjection launcher ──────────────────────────────────────────────
    // Registered here (not in onCreate) — Android requires this before onCreate.
    // Flow:
    //   Start tapped → viewModel.onStartStream() → requestProjectionLauncher()
    //   → system dialog → result lands here → viewModel.onMediaProjectionResult()
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onMediaProjectionResult(result.resultCode, result.data)
    }

    // ── Permission launcher ───────────────────────────────────────────────────
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        permissionCallback?.invoke(allGranted)
        permissionCallback = null
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wire ViewModel's projection trigger to our launcher
        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        viewModel.requestProjectionLauncher = {
            mediaProjectionLauncher.launch(projManager.createScreenCaptureIntent())
        }

        setContent {
            AudioBridgeTheme {
                // Collect from Claude's ViewModel
                val streamState by viewModel.streamState.collectAsState()
                val audioLevel  by viewModel.audioLevel.collectAsState()

                // Track permission state — drives which screen Gemini shows first
                var hasPermissions by remember { mutableStateOf(checkPermissions()) }

                AudioBridgeNavigation(
                    hasPermissions       = hasPermissions,
                    streamState          = streamState,
                    audioLevel           = audioLevel,
                    savedIp              = viewModel.getSavedIp(),
                    onStartClick         = { ip, port -> viewModel.onStartStream(ip, port) },
                    onStopClick          = { viewModel.onStopStream() },
                    onRequestPermissions = {
                        askPermissions { granted -> hasPermissions = granted }
                    }
                )
            }
        }
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    /**
     * Returns true if all required permissions are already granted.
     * RECORD_AUDIO        — required for AudioPlaybackCaptureConfiguration
     * POST_NOTIFICATIONS  — required for foreground notification on API 33+
     */
    private fun checkPermissions(): Boolean {
        val audio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // implicitly granted below API 33
        }

        return audio && notify
    }

    private fun askPermissions(onResult: (Boolean) -> Unit) {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        permissionCallback = onResult
        permissionLauncher.launch(perms)
    }

    // ─── Notification Stop while foregrounded ────────────────────────────────

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == AudioCaptureService.ACTION_STOP) {
            viewModel.onStopStream()
        }
    }
}