package com.example.audiobridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * AudioCaptureService — foreground service for system audio capture + UDP streaming.
 *
 * ── CRITICAL DESIGN NOTE: MediaProjection handoff ──────────────────────────────
 * MediaProjection is NOT Parcelable and cannot be passed via Intent extras.
 * Instead, ViewModel stores it in [pendingProjection] before calling startForegroundService().
 * The service reads and clears it in onStartCommand(). This is the standard pattern.
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * Audio format: PCM_16BIT, 48000 Hz, Stereo, ~10ms chunks (1920 bytes each)
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"

        // ── Intent extras ────────────────────────────────────────────────────
        const val EXTRA_TARGET_IP   = "extra_target_ip"
        const val EXTRA_TARGET_PORT = "extra_target_port"
        const val ACTION_STOP       = "com.example.audiobridge.ACTION_STOP"

        // ── Notification ─────────────────────────────────────────────────────
        const val NOTIFICATION_ID      = 1001
        const val NOTIFICATION_CHANNEL = "audiobridge_stream"

        // ── Audio config — plain Int values, NOT AudioFormat.* constants ─────
        // (AudioFormat.* are not const, so they can't be used in const val)
        const val SAMPLE_RATE   = 48000
        const val CHANNEL_COUNT = 2
        const val BYTES_PER_SAMPLE = 2      // PCM_16BIT
        const val BYTES_PER_FRAME  = CHANNEL_COUNT * BYTES_PER_SAMPLE   // = 4
        const val CHUNK_MS         = 10
        const val CHUNK_FRAMES     = SAMPLE_RATE / 1000 * CHUNK_MS       // = 480
        const val CHUNK_BYTES      = CHUNK_FRAMES * BYTES_PER_FRAME      // = 1920

        // ── Shared state (ViewModel observes these) ──────────────────────────
        val audioLevel  = MutableStateFlow(0f)
        val lastError   = MutableStateFlow<String?>(null)
        val isStreaming = MutableStateFlow(false)

        /**
         * MediaProjection handoff point.
         * ViewModel sets this BEFORE calling startForegroundService().
         * Service reads and clears it in onStartCommand().
         * Volatile so both threads see the write immediately.
         */
        @Volatile var pendingProjection: MediaProjection? = null
    }

    private var audioRecord: AudioRecord? = null
    private var udpSender: UDPSender? = null
    private var captureThread: Thread? = null
    @Volatile private var keepRunning = false

    // ─── Service lifecycle ────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        // Collect MediaProjection from static handoff — NOT from Intent
        val projection = pendingProjection
        pendingProjection = null // clear immediately

        val ip   = intent?.getStringExtra(EXTRA_TARGET_IP)
        val port = intent?.getIntExtra(EXTRA_TARGET_PORT, 7355) ?: 7355

        if (projection == null || ip.isNullOrBlank()) {
            Log.e(TAG, "Missing MediaProjection or IP")
            lastError.value = "Missing projection or IP"
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground must be called before any heavy work
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(ip, port))

        startCapture(projection, ip, port)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ─── Capture ──────────────────────────────────────────────────────────────

    private fun startCapture(projection: MediaProjection, ip: String, port: Int) {
        val sampleRate = resolveSampleRate()

        // CHANNEL_IN_STEREO = AudioFormat.CHANNEL_IN_STEREO (value = 12)
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        // ENCODING_PCM_16BIT = AudioFormat.ENCODING_PCM_16BIT (value = 2)
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = maxOf(minBuffer, CHUNK_BYTES * 2)

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            lastError.value = "AudioRecord init failed"
            stopSelf()
            return
        }

        audioRecord = record
        udpSender = UDPSender().also { it.start(ip, port) }
        keepRunning = true
        isStreaming.value = true

        // Raw Thread — NOT coroutine. Coroutine dispatchers add unpredictable jitter.
        captureThread = Thread({
            Log.i(TAG, "Capture thread started — ${sampleRate}Hz, chunk=${CHUNK_BYTES}B")
            record.startRecording()
            captureLoop(record)
            record.stop()
            record.release()
            Log.i(TAG, "Capture thread exited")
        }, "audiobridge-capture").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun stopCapture() {
        keepRunning = false
        isStreaming.value = false
        audioLevel.value = 0f

        captureThread?.join(500)
        captureThread = null

        udpSender?.stop()
        udpSender = null
        audioRecord = null // AudioRecord released inside captureLoop after it exits
    }

    // ─── Capture loop ─────────────────────────────────────────────────────────

    private fun captureLoop(record: AudioRecord) {
        val buffer = ByteArray(CHUNK_BYTES)

        while (keepRunning) {
            val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

            when {
                read > 0 -> {
                    // Fire-and-forget — no buffering
                    udpSender?.send(buffer.copyOf(read))
                    // Update UI level meter: peak / 32768 → 0.0–1.0
                    audioLevel.value = computeLevel(buffer, read)
                }
                read == AudioRecord.ERROR_INVALID_OPERATION -> {
                    Log.e(TAG, "AudioRecord: ERROR_INVALID_OPERATION")
                    lastError.value = "Audio capture error"
                    break
                }
                read == AudioRecord.ERROR_BAD_VALUE -> {
                    Log.e(TAG, "AudioRecord: ERROR_BAD_VALUE")
                    break
                }
                // read == 0 → silence, keep going (stream clock stays alive)
            }
        }
    }

    private fun computeLevel(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i < length - 1) {
            // Reassemble little-endian 16-bit sample
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val mag = abs(sample.toShort().toInt())
            if (mag > peak) peak = mag
            i += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    // ─── Sample rate ──────────────────────────────────────────────────────────

    private fun resolveSampleRate(): Int {
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        if (AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding) > 0) {
            return SAMPLE_RATE
        }
        for (rate in listOf(44100, 22050, 16000)) {
            if (AudioRecord.getMinBufferSize(rate, channelConfig, encoding) > 0) {
                Log.w(TAG, "FALLBACK: ${rate}Hz — update SAMPLE_RATE in receiver.py!")
                return rate
            }
        }
        return SAMPLE_RATE
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "AudioBridge Stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while AudioBridge is streaming"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(ip: String, port: Int): android.app.Notification {
        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Using NotificationCompat (from androidx.core, always available)
        // to avoid raw Notification.Action.Builder icon issues across API levels
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("AudioBridge Active")
            .setContentText("Streaming to $ip:$port")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}