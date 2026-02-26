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
 *   Bytes [4+]   → raw PCM payload (signed 16-bit LE, stereo interleaved)
 */
class UDPSender {

    private val TAG = "UDPSender"
    private val HEADER_SIZE = 4
    // 4 packets ≈ 40ms. If we fall this far behind, drop oldest to protect latency.
    private val QUEUE_CAPACITY = 4

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var targetAddress: InetAddress? = null
    @Volatile private var targetPort: Int = 0

    private val sequenceNumber = AtomicInteger(0)
    private val running = AtomicBoolean(false)
    private val sendQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var sendThread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start(ip: String, port: Int) {
        if (running.getAndSet(true)) return
        sequenceNumber.set(0)
        sendQueue.clear()

        try {
            targetAddress = InetAddress.getByName(ip)
            targetPort = port
            socket = DatagramSocket().apply {
                sendBufferSize = 1920 * 8
            }
        } catch (e: Exception) {
            Log.e(TAG, "Socket open failed: ${e.message}")
            running.set(false)
            return
        }

        sendThread = Thread({
            sendLoop()
        }, "audiobridge-udp-send").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }

        Log.i(TAG, "UDPSender started → $ip:$port")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        sendQueue.offer(ByteArray(0)) // poison pill
        socket?.close()
        socket = null
        sendThread?.join(200)
        sendThread = null
        Log.i(TAG, "UDPSender stopped")
    }

    /** Non-blocking enqueue. Drops oldest packet if full — latency over completeness. */
    fun send(pcmChunk: ByteArray) {
        if (!running.get()) return
        if (!sendQueue.offer(pcmChunk)) {
            sendQueue.poll()
            sendQueue.offer(pcmChunk)
        }
    }

    private fun sendLoop() {
        while (running.get()) {
            val chunk = try {
                sendQueue.take()
            } catch (e: InterruptedException) {
                break
            }
            if (chunk.isEmpty()) break // poison pill

            val sock = socket ?: break
            val addr = targetAddress ?: break

            try {
                sock.send(buildPacket(chunk, addr))
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Send error: ${e.message}")
            }
        }
    }

    private fun buildPacket(chunk: ByteArray, addr: InetAddress): DatagramPacket {
        val seq = sequenceNumber.getAndIncrement()
        val payload = ByteArray(HEADER_SIZE + chunk.size)
        payload[0] = (seq ushr 24 and 0xFF).toByte()
        payload[1] = (seq ushr 16 and 0xFF).toByte()
        payload[2] = (seq ushr  8 and 0xFF).toByte()
        payload[3] = (seq         and 0xFF).toByte()
        System.arraycopy(chunk, 0, payload, HEADER_SIZE, chunk.size)
        return DatagramPacket(payload, payload.size, addr, targetPort)
    }
}