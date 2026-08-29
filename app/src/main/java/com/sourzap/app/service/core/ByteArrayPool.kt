package com.sourzap.app.service.core

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Ultra High-Performance Zero-Allocation Buffer Pool for High-Throughput Packet Streaming.
 * Prevents GC thrashing and memory pauses during 4K/8K video streaming and Gigabit downloads.
 */
object ByteArrayPool {
    const val BUFFER_SIZE = 65536 // 64 KB Jumbo Stream Buffer
    const val PACKET_BUFFER_SIZE = 32768 // 32 KB Packet Buffer

    private val streamPool = ConcurrentLinkedQueue<ByteArray>()
    private val packetPool = ConcurrentLinkedQueue<ByteArray>()
    private const val MAX_POOL_SIZE = 128

    fun obtainStreamBuffer(): ByteArray {
        return streamPool.poll() ?: ByteArray(BUFFER_SIZE)
    }

    fun recycleStreamBuffer(buffer: ByteArray) {
        if (buffer.size == BUFFER_SIZE && streamPool.size < MAX_POOL_SIZE) {
            streamPool.offer(buffer)
        }
    }

    fun obtainPacketBuffer(): ByteArray {
        return packetPool.poll() ?: ByteArray(PACKET_BUFFER_SIZE)
    }

    fun recyclePacketBuffer(buffer: ByteArray) {
        if (buffer.size == PACKET_BUFFER_SIZE && packetPool.size < MAX_POOL_SIZE) {
            packetPool.offer(buffer)
        }
    }
}