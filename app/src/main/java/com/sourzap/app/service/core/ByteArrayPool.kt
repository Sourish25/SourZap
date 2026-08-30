package com.sourzap.app.service.core

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ultra High-Performance Zero-Allocation Buffer Pool for High-Throughput Packet Streaming.
 * Uses O(1) AtomicInteger bounded queues to eliminate GC pauses and ConcurrentLinkedQueue size traversals.
 */
object ByteArrayPool {
    const val BUFFER_SIZE = 65536 // 64 KB Jumbo Stream Buffer
    const val PACKET_BUFFER_SIZE = 32768 // 32 KB Packet Buffer
    const val SMALL_BUFFER_SIZE = 4096 // 4 KB UDP / Synthesis Buffer

    private const val MAX_POOL_SIZE = 128

    private val streamPool = ConcurrentLinkedQueue<ByteArray>()
    private val streamCount = AtomicInteger(0)

    private val packetPool = ConcurrentLinkedQueue<ByteArray>()
    private val packetCount = AtomicInteger(0)

    private val smallPool = ConcurrentLinkedQueue<ByteArray>()
    private val smallCount = AtomicInteger(0)

    fun obtainStreamBuffer(): ByteArray {
        val buf = streamPool.poll()
        if (buf != null) {
            streamCount.decrementAndGet()
            return buf
        }
        return ByteArray(BUFFER_SIZE)
    }

    fun recycleStreamBuffer(buffer: ByteArray) {
        if (buffer.size == BUFFER_SIZE && streamCount.get() < MAX_POOL_SIZE) {
            streamPool.offer(buffer)
            streamCount.incrementAndGet()
        }
    }

    fun obtainPacketBuffer(): ByteArray {
        val buf = packetPool.poll()
        if (buf != null) {
            packetCount.decrementAndGet()
            return buf
        }
        return ByteArray(PACKET_BUFFER_SIZE)
    }

    fun recyclePacketBuffer(buffer: ByteArray) {
        if (buffer.size == PACKET_BUFFER_SIZE && packetCount.get() < MAX_POOL_SIZE) {
            packetPool.offer(buffer)
            packetCount.incrementAndGet()
        }
    }

    fun obtainSmallBuffer(): ByteArray {
        val buf = smallPool.poll()
        if (buf != null) {
            smallCount.decrementAndGet()
            return buf
        }
        return ByteArray(SMALL_BUFFER_SIZE)
    }

    fun recycleSmallBuffer(buffer: ByteArray) {
        if (buffer.size == SMALL_BUFFER_SIZE && smallCount.get() < MAX_POOL_SIZE) {
            smallPool.offer(buffer)
            smallCount.incrementAndGet()
        }
    }

    fun clear() {
        streamPool.clear()
        streamCount.set(0)
        packetPool.clear()
        packetCount.set(0)
        smallPool.clear()
        smallCount.set(0)
    }
}