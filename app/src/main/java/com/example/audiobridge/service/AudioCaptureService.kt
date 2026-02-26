package com.example.audiobridge.service

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"

        private const val SAMPLE_RATE_PRIMARY   = 48000
        private const val CHANNEL_CONFIG        = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_ENCODING        = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE      = 2
        private const val CHANNELS              = 2
        private const val CHUNK_MS              = 10

        private fun chunkSize(sampleRate: Int): Int =
            sampleRate * CHANNELS * BYTES_PER_SAMPLE * CHUNK_MS / 1000

        const val EXTRA_RESULT_CODE      = "extra_result_code"
        const val EXTRA_RESULT_DATA      = "extra_result_data"
        const val EXTRA_TARGET_IP        = "extra_target_ip"
        const val EXTRA_TARGET_PORT      = "extra_target_port"

        const val NOTIFICATION_ID        = 1001
        const val CHANNEL_ID             = "audiobridge_channel"

        const val ACTION_STOP            = "com.example.audiobridge.ACTION_STOP"

        val audioLevel  = MutableStateFlow(0f)
        val lastError   = MutableStateFlow<String?>(null)
    }

    private var audioRecord: AudioRecord? = null
    private val udpSender   = UDPSender()
    private var captureThread: Thread? = null

    @Volatile private var isCapturing = false
    private var actualSampleRate = SAMPLE_RATE_PRIMARY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification(intent)
        startForeground(NOTIFICATION_ID, notification)

        val targetIp        = intent?.getStringExtra(EXTRA_TARGET_IP)   ?: return START_NOT_STICKY
        val targetPort      = intent?.getIntExtra(EXTRA_TARGET_PORT, 7355) ?: 7355

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED) ?: android.app.Activity.RESULT_CANCELED
        val resultData = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        if (resultData == null || resultCode != android.app.Activity.RESULT_OK) {
            Log.e(TAG, "Invalid MediaProjection intent")
            lastError.value = "MediaProjection missing"
            stopSelf()
            return START_NOT_STICKY
        }

        val mediaProjectionManager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection from system")
            lastError.value = "Failed to acquire screen capture"
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

    private fun startCapture(projection: MediaProjection, ip: String, port: Int) {
        if (isCapturing) return

        val sampleRate = resolveWorkingSampleRate(projection) ?: run {
            lastError.value = "Device does not support required audio format"
            stopSelf()
            return
        }

        actualSampleRate = sampleRate

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
        val recordBufferSize = maxOf(minBufferSize, chunkSize(sampleRate) * 4)

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(recordBufferSize)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
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
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
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
    }

    private fun runCaptureLoop(sampleRate: Int) {
        val chunkBytes = chunkSize(sampleRate)
        val buffer = ByteArray(chunkBytes)

        while (isCapturing && !Thread.currentThread().isInterrupted) {
            val bytesRead = audioRecord?.read(buffer, 0, chunkBytes) ?: -1

            when {
                bytesRead == chunkBytes -> {
                    val chunk = buffer.copyOf()
                    udpSender.enqueue(chunk)
                    updateAudioLevel(chunk)
                }
                bytesRead > 0 -> udpSender.enqueue(buffer.copyOf(bytesRead))
                bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> break
                bytesRead == AudioRecord.ERROR_BAD_VALUE -> break
            }
        }
    }

    private fun updateAudioLevel(pcmBytes: ByteArray) {
        var peak = 0
        var i = 0
        while (i < pcmBytes.size - 1) {
            val lo  = pcmBytes[i].toInt() and 0xFF
            val hi  = pcmBytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            i += 2
        }
        audioLevel.value = peak / 32768f
    }

    private fun resolveWorkingSampleRate(projection: MediaProjection): Int? {
        val candidates = listOf(48000, 44100, 22050, 16000)
        for (rate in candidates) {
            val size = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_ENCODING)
            if (size > 0) return rate
        }
        return null
    }

    private fun buildForegroundNotification(intent: Intent?): android.app.Notification {
        val ip   = intent?.getStringExtra(EXTRA_TARGET_IP) ?: "unknown"
        val port = intent?.getIntExtra(EXTRA_TARGET_PORT, 7355) ?: 7355

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return try {
            val cls    = Class.forName("com.example.audiobridge.ui.NotificationBuilder")
            val method = cls.getMethod("buildForegroundNotification", android.content.Context::class.java,
                String::class.java, Int::class.java, android.app.PendingIntent::class.java)
            method.invoke(null, this, ip, port, stopPi) as android.app.Notification
        } catch (e: Exception) {
            buildMinimalNotification(ip, port)
        }
    }

    private fun buildMinimalNotification(ip: String, port: Int): android.app.Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "AudioBridge Stream", android.app.NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = android.app.PendingIntent.getService(this, 0, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE)

        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioBridge Active")
            .setContentText("Streaming to $ip:$port")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }
}