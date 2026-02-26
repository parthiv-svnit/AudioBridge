package com.example.audiobridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.example.audiobridge.ui.AudioBridgeNavigation
import com.example.audiobridge.ui.AudioBridgeTheme
import com.example.audiobridge.viewmodel.StreamViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: StreamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AudioBridgeTheme {
                val streamState by viewModel.streamState.collectAsState()
                val audioLevel by viewModel.audioLevel.collectAsState()
                val needsProjection by viewModel.needsProjectionRequest.collectAsState()

                var hasPermissions by remember { mutableStateOf(checkInitialPermissions()) }

                // 1. Setup the Permission Requester
                val requestPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    hasPermissions = checkInitialPermissions()
                }

                // 2. Setup the Screen Capture Requester
                val requestProjectionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    viewModel.onMediaProjectionResult(result.resultCode, result.data)
                }

                // 3. Trigger Screen Capture when ViewModel says "Ready"
                LaunchedEffect(needsProjection) {
                    if (needsProjection) {
                        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        requestProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                        viewModel.onProjectionRequestConsumed()
                    }
                }

                // 4. Mount the UI Navigation
                AudioBridgeNavigation(
                    hasPermissions = hasPermissions,
                    streamState = streamState,
                    audioLevel = audioLevel,
                    savedIp = viewModel.savedIp,
                    onStartClick = { ip, port ->
                        viewModel.onStartStream(ip, port)
                    },
                    onStopClick = {
                        viewModel.onStopStream()
                    },
                    onRequestPermissions = {
                        val permissionsToRequest = mutableListOf(
                            Manifest.permission.RECORD_AUDIO
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                    }
                )
            }
        }
    }

    private fun checkInitialPermissions(): Boolean {
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return audioGranted && notifGranted
    }
}