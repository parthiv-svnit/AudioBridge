package com.example.audiobridge.service

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UDPSender {

    companion object {
        private const val TAG = "UDPSender"
        private const val SEND_QUEUE_CAPACITY = 8
    }

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var targetAddress: InetAddress? = null
    @Volatile private var targetPort: Int = 0

    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    private val sequenceNumber = AtomicInteger(0)
    private val sendQueue = ArrayBlockingQueue<ByteArray>(SEND_QUEUE_CAPACITY)
    private var senderThread: Thread? = null

    fun start(ip: String, port: Int) {
        if (_isRunning.getAndSet(true)) return

        try {
            targetAddress = InetAddress.getByName(ip)
            targetPort = port
            socket = DatagramSocket()
            socket?.sendBufferSize = 65536
            sequenceNumber.set(0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open socket: ${e.message}")
            _isRunning.set(false)
            return
        }

        senderThread = Thread({
            runSendLoop()
        }, "AudioBridge-UDPSender").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        if (!_isRunning.getAndSet(false)) return
        sendQueue.clear()
        senderThread?.interrupt()
        socket?.close()
        socket = null
        senderThread = null
    }

    fun enqueue(pcmChunk: ByteArray) {
        if (!_isRunning.get()) return
        val packet = buildPacket(pcmChunk)
        if (!sendQueue.offer(packet)) {
            sendQueue.poll()
            sendQueue.offer(packet)
        }
    }

    private fun buildPacket(pcmChunk: ByteArray): ByteArray {
        val seq = sequenceNumber.getAndIncrement()
        val packet = ByteArray(4 + pcmChunk.size)

        packet[0] = (seq ushr 24 and 0xFF).toByte()
        packet[1] = (seq ushr 16 and 0xFF).toByte()
        packet[2] = (seq ushr  8 and 0xFF).toByte()
        packet[3] = (seq         and 0xFF).toByte()

        System.arraycopy(pcmChunk, 0, packet, 4, pcmChunk.size)
        return packet
    }

    private fun runSendLoop() {
        while (_isRunning.get()) {
            try {
                val data = sendQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val addr = targetAddress ?: continue
                val datagram = DatagramPacket(data, data.size, addr, targetPort)
                socket?.send(datagram)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "Send error: ${e.message}")
            }
        }
    }
}