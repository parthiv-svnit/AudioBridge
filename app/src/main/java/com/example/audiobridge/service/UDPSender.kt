package com.audiobridge.service

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDPSender — fire-and-forget UDP packet sender.
 *
 * Packet structure (per shared protocol):
 *   Bytes [0–3]  → uint32 sequence number (big-endian)
 *   Bytes [4+]   → raw PCM payload (signed 16-bit, little-endian, stereo interleaved)
 *
 * Rules:
 * - No retry, no ACK, no buffering beyond the send queue
 * - All exceptions are logged only — never crash the service
 * - Runs on its own dedicated Thread
 */
class UDPSender {

    companion object {
        private const val TAG = "UDPSender"
        private const val SEND_QUEUE_CAPACITY = 8  // small — if we're this behind, drop is fine
    }

    // ── State ──────────────────────────────────────────────────────────────────

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var targetAddress: InetAddress? = null
    @Volatile private var targetPort: Int = 0

    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    private val sequenceNumber = AtomicInteger(0)

    // Bounded queue — if full, drop oldest rather than blocking the audio capture thread
    private val sendQueue = ArrayBlockingQueue<ByteArray>(SEND_QUEUE_CAPACITY)

    private var senderThread: Thread? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Start the sender. Safe to call multiple times — no-op if already running.
     */
    fun start(ip: String, port: Int) {
        if (_isRunning.getAndSet(true)) return

        try {
            targetAddress = InetAddress.getByName(ip)
            targetPort = port
            socket = DatagramSocket()
            socket?.sendBufferSize = 65536  // 64KB kernel send buffer
            sequenceNumber.set(0)
            Log.i(TAG, "UDP sender ready → $ip:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open socket: ${e.message}")
            _isRunning.set(false)
            return
        }

        senderThread = Thread({
            runSendLoop()
        }, "AudioBridge-UDPSender").also {
            it.priority = Thread.MAX_PRIORITY  // latency matters
            it.start()
        }
    }

    /**
     * Stop the sender. Clears queue, closes socket.
     */
    fun stop() {
        if (!_isRunning.getAndSet(false)) return
        sendQueue.clear()
        senderThread?.interrupt()
        socket?.close()
        socket = null
        senderThread = null
        Log.i(TAG, "UDP sender stopped")
    }

    /**
     * Enqueue a raw PCM chunk for sending. Called from the audio capture thread.
     * If the queue is full, the oldest packet is dropped to maintain low latency.
     *
     * @param pcmChunk raw PCM bytes — caller must not reuse this buffer after passing it here
     */
    fun enqueue(pcmChunk: ByteArray) {
        if (!_isRunning.get()) return

        val packet = buildPacket(pcmChunk)

        // Drop oldest if full — we'd rather skip a packet than add latency
        if (!sendQueue.offer(packet)) {
            sendQueue.poll()
            sendQueue.offer(packet)
            Log.w(TAG, "Send queue full — dropped oldest packet")
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun buildPacket(pcmChunk: ByteArray): ByteArray {
        val seq = sequenceNumber.getAndIncrement()
        val packet = ByteArray(4 + pcmChunk.size)

        // 4-byte big-endian sequence number
        packet[0] = (seq ushr 24 and 0xFF).toByte()
        packet[1] = (seq ushr 16 and 0xFF).toByte()
        packet[2] = (seq ushr  8 and 0xFF).toByte()
        packet[3] = (seq         and 0xFF).toByte()

        // Raw PCM payload
        System.arraycopy(pcmChunk, 0, packet, 4, pcmChunk.size)
        return packet
    }

    private fun runSendLoop() {
        Log.d(TAG, "Send loop started")
        while (_isRunning.get()) {
            try {
                // Block until a packet is available (with timeout to check isRunning)
                val data = sendQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: continue

                val addr = targetAddress ?: continue
                val port = targetPort

                val datagram = DatagramPacket(data, data.size, addr, port)
                socket?.send(datagram)

            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                // Log only — never crash
                Log.e(TAG, "Send error: ${e.message}")
            }
        }
        Log.d(TAG, "Send loop exited")
    }
}