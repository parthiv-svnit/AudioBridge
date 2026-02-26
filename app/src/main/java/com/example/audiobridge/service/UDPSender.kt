package com.example.audiobridge.service

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDPSender — fire-and-forget raw PCM sender over UDP.
 *
 * Packet protocol:
 *   Bytes [0–3]  → uint32 sequence number (big-endian)
 *   Bytes [4+]   → raw PCM payload (signed 16-bit, little-endian, stereo interleaved)
 *
 * Design decisions:
 *   - Dedicated raw Thread (not coroutine) — avoids scheduling jitter
 *   - No retry, no ACK, no buffering beyond a minimal send queue
 *   - Silent exception handling — log only, never crash the service
 *   - Send queue capacity = 4 packets (~40ms max buildup before drop)
 *     Dropping is intentional: a stale audio packet is worse than a missing one.
 */
class UDPSender {

    companion object {
        private const val TAG = "UDPSender"
        private const val HEADER_SIZE = 4
        // Max queued packets before we start dropping (latency protection).
        // 4 packets = ~40ms. If we're this far behind, drop oldest not newest.
        private const val SEND_QUEUE_CAPACITY = 4
    }

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var targetAddress: InetAddress? = null
    @Volatile private var targetPort: Int = 0

    private val sequenceNumber = AtomicInteger(0)
    private val running = AtomicBoolean(false)

    // Bounded queue: if send thread falls behind, drop oldest packets.
    // A stale audio chunk is worse than a gap — latency is the priority.
    private val sendQueue = ArrayBlockingQueue<ByteArray>(SEND_QUEUE_CAPACITY)

    private var sendThread: Thread? = null

    val isRunning: Boolean get() = running.get()

    /**
     * Start sender. Opens UDP socket and begins send loop thread.
     * Safe to call from any thread.
     */
    fun start(ip: String, port: Int) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "start() called while already running — ignoring")
            return
        }
        sequenceNumber.set(0)
        sendQueue.clear()

        try {
            targetAddress = InetAddress.getByName(ip)
            targetPort = port
            socket = DatagramSocket().apply {
                sendBufferSize = 1920 * 8 // 8 chunks of socket buffer headroom
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open socket to $ip:$port — ${e.message}")
            running.set(false)
            return
        }

        sendThread = Thread({
            Log.i(TAG, "Send thread started → $ip:$port")
            sendLoop()
            Log.i(TAG, "Send thread exited")
        }, "audiobridge-udp-send").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    /**
     * Stop sender. Closes socket and joins send thread.
     * Safe to call from any thread.
     */
    fun stop() {
        if (!running.getAndSet(false)) return
        sendQueue.offer(ByteArray(0)) // Poison pill — unblocks take()
        socket?.close()
        socket = null
        sendThread?.join(200)
        sendThread = null
        Log.i(TAG, "UDPSender stopped")
    }

    /**
     * Enqueue a raw PCM chunk for sending.
     * Non-blocking — called from the hot audio capture loop.
     * If queue is full, oldest packet is dropped to protect latency.
     */
    fun send(pcmChunk: ByteArray) {
        if (!running.get()) return
        if (!sendQueue.offer(pcmChunk)) {
            sendQueue.poll()          // Drop oldest
            sendQueue.offer(pcmChunk) // Enqueue newest
            Log.w(TAG, "Send queue full — dropped oldest packet to protect latency")
        }
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    private fun sendLoop() {
        while (running.get()) {
            val pcmChunk = try {
                sendQueue.take() // Blocks until data or poison pill
            } catch (e: InterruptedException) {
                break
            }
            if (pcmChunk.isEmpty()) break // Poison pill

            val sock = socket ?: break
            val addr = targetAddress ?: break

            try {
                sock.send(buildPacket(pcmChunk, addr))
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Send error (seq=${sequenceNumber.get()}): ${e.message}")
                }
                // Never crash — log and continue
            }
        }
    }

    /**
     * Assemble UDP packet:
     *   [0–3] = uint32 sequence number, big-endian
     *   [4+]  = raw PCM bytes
     */
    private fun buildPacket(pcmChunk: ByteArray, addr: InetAddress): DatagramPacket {
        val seq = sequenceNumber.getAndIncrement()
        val payload = ByteArray(HEADER_SIZE + pcmChunk.size)

        payload[0] = (seq ushr 24 and 0xFF).toByte()
        payload[1] = (seq ushr 16 and 0xFF).toByte()
        payload[2] = (seq ushr  8 and 0xFF).toByte()
        payload[3] = (seq         and 0xFF).toByte()

        System.arraycopy(pcmChunk, 0, payload, HEADER_SIZE, pcmChunk.size)
        return DatagramPacket(payload, payload.size, addr, targetPort)
    }
}