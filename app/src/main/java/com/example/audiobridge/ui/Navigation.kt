package com.example.audiobridge.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

// Define our simple routes
object Routes {
    const val PERMISSIONS = "permissions"
    const val MAIN = "main"
}

@Composable
fun AudioBridgeNavigation(
    hasPermissions: Boolean,
    streamState: StreamState,
    audioLevel: Float,
    savedIp: String,
    onStartClick: (ip: String, port: Int) -> Unit,
    onStopClick: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val navController = rememberNavController()

    // Determine start destination cleanly
    val startDestination = if (hasPermissions) Routes.MAIN else Routes.PERMISSIONS

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {

        composable(Routes.PERMISSIONS) {
            PermissionScreen(
                onPermissionsGranted = {
                    // Call out to the host activity to handle the actual OS requests.
                    // Once granted, the activity should recompose this with hasPermissions = true
                    onRequestPermissions()
                }
            )
        }

        composable(Routes.MAIN) {
            MainScreen(
                streamState = streamState,
                audioLevel = audioLevel,
                savedIp = savedIp,
                onStartClick = onStartClick,
                onStopClick = onStopClick
            )
        }
    }
}