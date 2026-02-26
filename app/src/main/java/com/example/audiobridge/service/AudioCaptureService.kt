package com.example.audiobridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * AudioCaptureService — foreground service that captures all system audio
 * and streams it as raw PCM over UDP with minimum latency.
 *
 * Audio pipeline:
 *   MediaProjection → AudioPlaybackCaptureConfiguration → AudioRecord
 *   → raw Thread read loop (no coroutines — avoids scheduling jitter)
 *   → UDPSender (fire-and-forget)
 *
 * Audio format:
 *   Encoding:   PCM_16BIT  (2 bytes per sample)
 *   SampleRate: 48000 Hz   (falls back to device native if unsupported)
 *   Channels:   STEREO     (2 channels)
 *   Chunk size: 1920 bytes = 10ms of audio at 48kHz stereo 16-bit
 *               = 48000 * 2ch * 2bytes * 0.01s
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"

        // ── Audio constants ─────────────────────────────────────────────────
        const val SAMPLE_RATE_PRIMARY = 48000
        const val CHANNEL_CONFIG      = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT        = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_FRAME     = 4    // 2 channels × 2 bytes
        const val CHUNK_MS            = 10
        const val CHUNK_SIZE          = SAMPLE_RATE_PRIMARY / 1000 * CHUNK_MS * BYTES_PER_FRAME
        // = 48 * 10 * 4 = 1920 bytes

        // ── Intent keys ─────────────────────────────────────────────────────
        const val EXTRA_MEDIA_PROJECTION = "extra_media_projection"
        const val EXTRA_TARGET_IP        = "extra_target_ip"
        const val EXTRA_TARGET_PORT      = "extra_target_port"
        const val ACTION_STOP            = "com.example.audiobridge.ACTION_STOP"

        // ── Notification ─────────────────────────────────────────────────────
        const val NOTIFICATION_ID      = 1001
        const val NOTIFICATION_CHANNEL = "audiobridge_stream"

        // ── Shared state — ViewModel observes these ──────────────────────────
        val audioLevel  = MutableStateFlow(0f)
        val lastError   = MutableStateFlow<String?>(null)
        val isStreaming = MutableStateFlow(false)
    }

    private var audioRecord: AudioRecord? = null
    private var udpSender: UDPSender? = null
    private var captureThread: Thread? = null
    @Volatile private var keepRunning = false

    // ─── Service lifecycle ────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop action received")
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        // Extract MediaProjection — handle deprecated API on older Android
        val mediaProjection: MediaProjection? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_MEDIA_PROJECTION, MediaProjection::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_MEDIA_PROJECTION)
        }

        val targetIp   = intent?.getStringExtra(EXTRA_TARGET_IP)
        val targetPort = intent?.getIntExtra(EXTRA_TARGET_PORT, 7355) ?: 7355

        if (mediaProjection == null || targetIp.isNullOrBlank()) {
            Log.e(TAG, "Missing MediaProjection or target IP — cannot start")
            lastError.value = "Missing projection or IP"
            stopSelf()
            return START_NOT_STICKY
        }

        // Must call startForeground before doing any heavy work
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(targetIp, targetPort))

        startCapture(mediaProjection, targetIp, targetPort)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ─── Capture ──────────────────────────────────────────────────────────────

    private fun startCapture(projection: MediaProjection, ip: String, port: Int) {
        val sampleRate = resolveSampleRate()

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer, CHUNK_SIZE * 2)

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNEL_CONFIG)
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

        // Raw Thread — NOT a coroutine. Coroutine dispatchers add scheduling jitter.
        // MAX_PRIORITY ensures the OS doesn't preempt us during audio reads.
        captureThread = Thread({
            Log.i(TAG, "Capture thread started — $sampleRate Hz, chunk=${CHUNK_SIZE}B")
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
        audioRecord = null // released inside captureLoop
    }

    // ─── Capture loop ─────────────────────────────────────────────────────────

    /**
     * Hot read loop on MAX_PRIORITY thread.
     * Reads exactly CHUNK_SIZE bytes (~10ms) per iteration.
     * Passes each chunk immediately to UDPSender — zero internal buffering.
     * Updates audioLevel StateFlow every chunk for the UI level meter.
     */
    private fun captureLoop(record: AudioRecord) {
        val buffer = ByteArray(CHUNK_SIZE)

        while (keepRunning) {
            val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

            when {
                read > 0 -> {
                    udpSender?.send(buffer.copyOf(read))
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
                // read == 0 → silence frame, still send to keep stream clock alive
            }
        }
    }

    /**
     * Compute normalized peak level 0.0–1.0 from raw PCM buffer.
     * Used by the UI audio level meter.
     * Formula: max(abs(sample)) / 32768
     */
    private fun computeLevel(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i < length - 1) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val magnitude = abs(sample.toShort().toInt())
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak / 32768f
    }

    // ─── Sample rate ──────────────────────────────────────────────────────────

    /**
     * Try 48kHz first. Fall back to 44100 or lower if device doesn't support it.
     * Any fallback is logged — receiver.py SAMPLE_RATE must be updated to match.
     */
    private fun resolveSampleRate(): Int {
        if (AudioRecord.getMinBufferSize(SAMPLE_RATE_PRIMARY, CHANNEL_CONFIG, AUDIO_FORMAT) > 0) {
            Log.i(TAG, "Using sample rate: ${SAMPLE_RATE_PRIMARY}Hz")
            return SAMPLE_RATE_PRIMARY
        }
        for (rate in listOf(44100, 22050, 16000)) {
            if (AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT) > 0) {
                Log.w(TAG, "⚠️ Fallback sample rate: ${rate}Hz — update SAMPLE_RATE in receiver.py!")
                return rate
            }
        }
        Log.e(TAG, "No supported sample rate found — trying ${SAMPLE_RATE_PRIMARY}Hz anyway")
        return SAMPLE_RATE_PRIMARY
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Minimal fallback notification.
     * UI team's NotificationBuilder can replace this for full styling.
     */
    private fun buildNotification(ip: String, port: Int): Notification {
        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("AudioBridge Active")
            .setContentText("Streaming to $ip:$port")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }
}