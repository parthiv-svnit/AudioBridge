package com.audiobridge.service

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AudioCaptureService — foreground service that captures ALL system audio
 * via MediaProjection and streams raw PCM over UDP.
 *
 * Audio config (constants at top — change here only):
 *   Sample rate : 48000 Hz  (falls back to device native if unsupported)
 *   Encoding    : PCM_16BIT (signed 16-bit, little-endian)
 *   Channels    : STEREO    (interleaved L/R)
 *   Chunk size  : 10ms      = 1920 bytes at 48kHz stereo 16-bit
 *
 * Threading:
 *   Audio read loop runs on a raw Java Thread — NOT a coroutine.
 *   This avoids coroutine scheduler jitter that could cause buffer underruns.
 */
class AudioCaptureService : Service() {

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AudioCaptureService"

        // Audio format
        private const val SAMPLE_RATE_PRIMARY   = 48000
        private const val CHANNEL_CONFIG        = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_ENCODING        = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE      = 2  // 16-bit
        private const val CHANNELS              = 2  // stereo
        private const val CHUNK_MS              = 10 // milliseconds per chunk

        // Computed chunk size in bytes: rate * channels * bytesPerSample * (chunkMs / 1000)
        // 48000 * 2 * 2 * 0.01 = 1920 bytes
        private fun chunkSize(sampleRate: Int): Int =
            sampleRate * CHANNELS * BYTES_PER_SAMPLE * CHUNK_MS / 1000

        // Intent extras
        const val EXTRA_MEDIA_PROJECTION = "extra_media_projection"
        const val EXTRA_TARGET_IP        = "extra_target_ip"
        const val EXTRA_TARGET_PORT      = "extra_target_port"

        // Notification
        const val NOTIFICATION_ID        = 1001
        const val CHANNEL_ID             = "audiobridge_channel"

        // Action to stop from notification
        const val ACTION_STOP            = "com.audiobridge.ACTION_STOP"

        // Shared StateFlows — ViewModel reads these
        val audioLevel  = MutableStateFlow(0f)
        val lastError   = MutableStateFlow<String?>(null)
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private var audioRecord: AudioRecord? = null
    private val udpSender   = UDPSender()
    private var captureThread: Thread? = null

    @Volatile private var isCapturing = false
    private var actualSampleRate = SAMPLE_RATE_PRIMARY

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground immediately to avoid ANR
        // Notification is built by the UI team's NotificationBuilder
        val notification = buildForegroundNotification(intent)
        startForeground(NOTIFICATION_ID, notification)

        val mediaProjection = intent?.getParcelableExtra<MediaProjection>(EXTRA_MEDIA_PROJECTION)
        val targetIp        = intent?.getStringExtra(EXTRA_TARGET_IP)   ?: return START_NOT_STICKY
        val targetPort      = intent?.getIntExtra(EXTRA_TARGET_PORT, 7355) ?: 7355

        if (mediaProjection == null) {
            Log.e(TAG, "No MediaProjection provided — cannot start capture")
            lastError.value = "MediaProjection missing"
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(mediaProjection, targetIp, targetPort)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ── Capture ────────────────────────────────────────────────────────────────

    private fun startCapture(projection: MediaProjection, ip: String, port: Int) {
        if (isCapturing) return

        val sampleRate = resolveWorkingSampleRate(projection) ?: run {
            Log.e(TAG, "No supported sample rate found")
            lastError.value = "Device does not support required audio format"
            stopSelf()
            return
        }

        actualSampleRate = sampleRate
        if (sampleRate != SAMPLE_RATE_PRIMARY) {
            Log.w(TAG, "FALLBACK: Using ${sampleRate}Hz instead of ${SAMPLE_RATE_PRIMARY}Hz " +
                    "because device does not support the primary rate. " +
                    "PC receiver must be configured to match.")
        }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_ENCODING)
            .setSampleRate(sampleRate)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_ENCODING)
        // Use 4x chunk size as the AudioRecord internal buffer — enough to prevent overrun
        // while keeping total latency minimal
        val recordBufferSize = maxOf(minBufferSize, chunkSize(sampleRate) * 4)

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(recordBufferSize)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            lastError.value = "AudioRecord initialization failed"
            audioRecord?.release()
            audioRecord = null
            stopSelf()
            return
        }

        udpSender.start(ip, port)
        isCapturing = true
        audioRecord?.startRecording()

        captureThread = Thread({
            runCaptureLoop(sampleRate)
        }, "AudioBridge-Capture").also {
            it.priority = Thread.MAX_PRIORITY  // audio thread must not be preempted
            it.start()
        }

        Log.i(TAG, "Capture started → ${sampleRate}Hz, streaming to $ip:$port")
    }

    private fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false

        captureThread?.interrupt()
        captureThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        udpSender.stop()
        audioLevel.value = 0f

        Log.i(TAG, "Capture stopped")
    }

    // ── Capture loop — raw Thread, no coroutines ───────────────────────────────

    private fun runCaptureLoop(sampleRate: Int) {
        val chunkBytes = chunkSize(sampleRate)
        val buffer = ByteArray(chunkBytes)

        Log.d(TAG, "Capture loop started — chunk=${chunkBytes}B (${CHUNK_MS}ms)")

        while (isCapturing && !Thread.currentThread().isInterrupted) {
            val bytesRead = audioRecord?.read(buffer, 0, chunkBytes) ?: -1

            when {
                bytesRead == chunkBytes -> {
                    // Clone buffer before handing off — AudioRecord reuses the same memory
                    val chunk = buffer.copyOf()
                    udpSender.enqueue(chunk)
                    updateAudioLevel(chunk)
                }
                bytesRead > 0 -> {
                    // Short read — send what we have (uncommon but handle it)
                    udpSender.enqueue(buffer.copyOf(bytesRead))
                }
                bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                    Log.e(TAG, "AudioRecord: ERROR_INVALID_OPERATION")
                    break
                }
                bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                    Log.e(TAG, "AudioRecord: ERROR_BAD_VALUE")
                    break
                }
                else -> {
                    // bytesRead == 0 or other — just continue
                }
            }
        }

        Log.d(TAG, "Capture loop exited")
    }

    // ── Audio level metering ───────────────────────────────────────────────────

    /**
     * Compute peak amplitude of the chunk and update the shared StateFlow.
     * Range: 0.0 (silence) → 1.0 (full scale).
     * Formula: max(abs(samples)) / 32768
     */
    private fun updateAudioLevel(pcmBytes: ByteArray) {
        var peak = 0
        // Iterate as 16-bit little-endian samples
        var i = 0
        while (i < pcmBytes.size - 1) {
            val lo  = pcmBytes[i].toInt() and 0xFF
            val hi  = pcmBytes[i + 1].toInt()
            val sample = (hi shl 8) or lo  // signed 16-bit
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            i += 2
        }
        audioLevel.value = peak / 32768f
    }

    // ── Sample rate resolution ─────────────────────────────────────────────────

    /**
     * Try primary sample rate first, fall back to common alternatives.
     * Returns the first rate that AudioRecord accepts, or null if none work.
     */
    private fun resolveWorkingSampleRate(projection: MediaProjection): Int? {
        val candidates = listOf(48000, 44100, 22050, 16000)

        for (rate in candidates) {
            val size = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_ENCODING)
            if (size > 0) {
                if (rate != SAMPLE_RATE_PRIMARY) {
                    Log.w(TAG, "Primary rate ${SAMPLE_RATE_PRIMARY}Hz not supported — " +
                            "falling back to ${rate}Hz. " +
                            "Update PC receiver constant SAMPLE_RATE to $rate.")
                }
                return rate
            }
        }
        return null
    }

    // ── Notification ───────────────────────────────────────────────────────────

    /**
     * Builds the foreground notification.
     * Full notification UI (title, body, icon, colors) is in NotificationBuilder.kt (UI team).
     * This method delegates to it.
     *
     * If NotificationBuilder is not yet integrated, a minimal fallback is used.
     */
    private fun buildForegroundNotification(intent: Intent?): android.app.Notification {
        val ip   = intent?.getStringExtra(EXTRA_TARGET_IP) ?: "unknown"
        val port = intent?.getIntExtra(EXTRA_TARGET_PORT, 7355) ?: 7355

        // UI: handled by Gemini — replace this body with NotificationBuilder.build(this, ip, port)
        return try {
            // Attempt to call Gemini's NotificationBuilder via reflection so this compiles
            // independently. When UI is merged, replace with direct call.
            val cls    = Class.forName("com.audiobridge.ui.NotificationBuilder")
            val method = cls.getMethod("build", android.content.Context::class.java,
                String::class.java, Int::class.java)
            method.invoke(null, this, ip, port) as android.app.Notification
        } catch (e: Exception) {
            // Fallback minimal notification (satisfies foreground service requirement)
            buildMinimalNotification(ip, port)
        }
    }

    private fun buildMinimalNotification(ip: String, port: Int): android.app.Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "AudioBridge Stream",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioBridge Active")
            .setContentText("Streaming to $ip:$port")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }
}