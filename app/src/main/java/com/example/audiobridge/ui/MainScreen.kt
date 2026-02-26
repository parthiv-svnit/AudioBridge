package com.example.audiobridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// Definition for the UI boundary signature
sealed class StreamState {
    object Idle : StreamState()
    data class Streaming(val ip: String) : StreamState()
    data class Error(val message: String) : StreamState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    streamState: StreamState,
    audioLevel: Float, // 0.0 - 1.0
    savedIp: String,
    onStartClick: (ip: String, port: Int) -> Unit,
    onStopClick: () -> Unit
) {
    var ipText by remember { mutableStateOf(savedIp) }
    var portText by remember { mutableStateOf("7355") }
    var showAdvanced by remember { mutableStateOf(false) }

    val isStreaming = streamState is StreamState.Streaming
    val buttonColor by animateColorAsState(
        targetValue = if (isStreaming) StopRed else StreamGreen,
        label = "buttonColor"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Assuming you have a standard drawable or using a placeholder shape if missing
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_lock_silent_mode_off), // Sub with actual waveform icon
                            contentDescription = "App Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("AudioBridge", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // --- Configuration Card ---
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    OutlinedTextField(
                        value = ipText,
                        onValueChange = { ipText = it },
                        label = { Text("PC IP Address") },
                        placeholder = { Text("e.g. 192.168.1.5") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        enabled = !isStreaming,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isStreaming) { showAdvanced = !showAdvanced }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Advanced Settings",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = if (showAdvanced) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Toggle Advanced",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = showAdvanced) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter { char -> char.isDigit() } },
                            label = { Text("Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !isStreaming,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- Status Indicator ---
            StatusIndicator(streamState = streamState)

            Spacer(modifier = Modifier.height(32.dp))

            // --- Audio Level Meter ---
            AudioLevelMeter(level = audioLevel)

            Spacer(modifier = Modifier.weight(1f))

            // --- Start/Stop Button ---
            val isButtonEnabled = ipText.isNotBlank() && portText.isNotBlank()

            Button(
                onClick = {
                    if (isStreaming) {
                        onStopClick()
                    } else {
                        val port = portText.toIntOrNull() ?: 7355
                        onStartClick(ipText.trim(), port)
                    }
                },
                enabled = isButtonEnabled || isStreaming,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(
                    text = if (isStreaming) "STOP STREAMING" else "START STREAMING",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
fun StatusIndicator(streamState: StreamState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )

        val dotColor = when (streamState) {
            is StreamState.Idle -> Color.Gray
            is StreamState.Streaming -> ElectricBlue.copy(alpha = alpha)
            is StreamState.Error -> StopRed
        }

        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color = dotColor)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = when (streamState) {
                is StreamState.Idle -> "Ready"
                is StreamState.Streaming -> "Streaming to ${streamState.ip}"
                is StreamState.Error -> streamState.message
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun AudioLevelMeter(level: Float) {
    // Smoothly animate the audio level changes
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 50, easing = LinearEasing),
        label = "audioLevelAnimation"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Captured Audio",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedLevel)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(StreamGreen)
            )
        }
    }
}