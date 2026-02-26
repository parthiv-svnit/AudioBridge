package com.example.audiobridge.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationBuilder {

    private const val CHANNEL_ID = "audiobridge_streaming_channel"
    private const val CHANNEL_NAME = "Audio Streaming"

    /**
     * Constructs the Notification UI for the Foreground Service.
     * Service logic (starting foreground, updating state) lives in Claude's territory.
     */
    fun buildForegroundNotification(
        context: Context,
        ip: String,
        port: Int,
        stopPendingIntent: PendingIntent
    ): Notification {

        // Ensure channel exists (Required for Android O and above)
        createNotificationChannel(context)

        // Using standard system icon fallback, Claude's layer can provide specific mipmaps if needed
        val iconId = android.R.drawable.ic_media_play

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconId)
            .setContentTitle("AudioBridge Active")
            .setContentText("Streaming to $ip:$port")
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority avoids sound/vibration but stays in status bar
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Streaming",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active AudioBridge streaming status"
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}