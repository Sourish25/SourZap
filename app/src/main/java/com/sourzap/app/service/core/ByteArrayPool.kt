package com.sourzap.app.service.core

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ultra High-Performance Zero-Allocation Tiered Buffer Pool for High-Throughput Packet Streaming.
 * Employs lock-free bounded concurrent queues with CAS guarantees to eliminate GC pauses and
 * allocation thrashing under multi-gigabit workloads.
 *
 * Supported Tier Sizes:
 * - 64 KB (65,536 bytes): Jumbo Socket Streams, TCP/HTTP Stream Pumpers, UDP Receiver Loop
 * - 32 KB (32,768 bytes): TUN Interface Packet Buffers
 * - 16 KB (16,384 bytes): TLS Records, Noise Handshakes, & Proxy Handshakes
 * - 4 KB  (4,096 bytes) : UDP Datagrams, DNS Wire Frames, ICMP Synthesis
 */
object ByteArrayPool {
    const val BUFFER_SIZE = 65536 // 64 KB Jumbo Stream Buffer (legacy alias)
    const val BUFFER_64K = 65536
    const val BUFFER_32K = 32768
    const val PACKET_BUFFER_SIZE = 32768 // 32 KB Packet Buffer (legacy alias)
    const val BUFFER_16K = 16384 // 16 KB Handshake / Record Buffer
    const val SMALL_BUFFER_SIZE = 4096 // 4 KB UDP / Synthesis Buffer (legacy alias)
    const val BUFFER_4K = 4096

    private const val MAX_POOL_SIZE_64K = 256
    private const val MAX_POOL_SIZE_32K = 256
    private const val MAX_POOL_SIZE_16K = 256
    private const val MAX_POOL_SIZE_4K = 256

    private val pool64k = ConcurrentLinkedQueue<ByteArray>()
    private val count64k = AtomicInteger(0)

    private val pool32k = ConcurrentLinkedQueue<ByteArray>()
    private val count32k = AtomicInteger(0)

    private val pool16k = ConcurrentLinkedQueue<ByteArray>()
    private val count16k = AtomicInteger(0)

    private val pool4k = ConcurrentLinkedQueue<ByteArray>()
    private val count4k = AtomicInteger(0)

    // --- 64 KB Tier ---
    fun obtainStreamBuffer(): ByteArray = obtain64k()
    fun recycleStreamBuffer(buffer: ByteArray) = recycle64k(buffer)

    fun obtain64k(): ByteArray {
        val buf = pool64k.poll()
        if (buf != null) {
            count64k.decrementAndGet()
            return buf
        }
        return ByteArray(BUFFER_64K)
    }

    fun recycle64k(buffer: ByteArray) {
        if (buffer.size != BUFFER_64K) return
        while (true) {
            val current = count64k.get()
            if (current >= MAX_POOL_SIZE_64K) return
            if (count64k.compareAndSet(current, current + 1)) {
                pool64k.offer(buffer)
                return
            }
        }
    }

    // --- 32 KB Tier ---
    fun obtainPacketBuffer(): ByteArray = obtain32k()
    fun recyclePacketBuffer(buffer: ByteArray) = recycle32k(buffer)

    fun obtain32k(): ByteArray {
        val buf = pool32k.poll()
        if (buf != null) {
            count32k.decrementAndGet()
            return buf
        }
        return ByteArray(BUFFER_32K)
    }

    fun recycle32k(buffer: ByteArray) {
        if (buffer.size != BUFFER_32K) return
        while (true) {
            val current = count32k.get()
            if (current >= MAX_POOL_SIZE_32K) return
            if (count32k.compareAndSet(current, current + 1)) {
                pool32k.offer(buffer)
                return
            }
        }
    }

    // --- 16 KB Tier ---
    fun obtain16kBuffer(): ByteArray = obtain16k()
    fun recycle16kBuffer(buffer: ByteArray) = recycle16k(buffer)

    fun obtain16k(): ByteArray {
        val buf = pool16k.poll()
        if (buf != null) {
            count16k.decrementAndGet()
            return buf
        }
        return ByteArray(BUFFER_16K)
    }

    fun recycle16k(buffer: ByteArray) {
        if (buffer.size != BUFFER_16K) return
        while (true) {
            val current = count16k.get()
            if (current >= MAX_POOL_SIZE_16K) return
            if (count16k.compareAndSet(current, current + 1)) {
                pool16k.offer(buffer)
                return
            }
        }
    }

    // --- 4 KB Tier ---
    fun obtainSmallBuffer(): ByteArray = obtain4k()
    fun recycleSmallBuffer(buffer: ByteArray) = recycle4k(buffer)

    fun obtain4k(): ByteArray {
        val buf = pool4k.poll()
        if (buf != null) {
            count4k.decrementAndGet()
            return buf
        }
        return ByteArray(BUFFER_4K)
    }

    fun recycle4k(buffer: ByteArray) {
        if (buffer.size != BUFFER_4K) return
        while (true) {
            val current = count4k.get()
            if (current >= MAX_POOL_SIZE_4K) return
            if (count4k.compareAndSet(current, current + 1)) {
                pool4k.offer(buffer)
                return
            }
        }
    }

    // --- Dynamic Tier Acquisition & Recycling ---
    fun obtain(size: Int): ByteArray {
        return when {
            size <= BUFFER_4K -> obtain4k()
            size <= BUFFER_16K -> obtain16k()
            size <= BUFFER_32K -> obtain32k()
            size <= BUFFER_64K -> obtain64k()
            else -> ByteArray(size)
        }
    }

    fun recycle(buffer: ByteArray) {
        when (buffer.size) {
            BUFFER_4K -> recycle4k(buffer)
            BUFFER_16K -> recycle16k(buffer)
            BUFFER_32K -> recycle32k(buffer)
            BUFFER_64K -> recycle64k(buffer)
        }
    }

    fun getPoolSize64k(): Int = count64k.get().coerceAtLeast(0)
    fun getPoolSize32k(): Int = count32k.get().coerceAtLeast(0)
    fun getPoolSize16k(): Int = count16k.get().coerceAtLeast(0)
    fun getPoolSize4k(): Int = count4k.get().coerceAtLeast(0)

    fun clear() {
        pool64k.clear()
        count64k.set(0)
        pool32k.clear()
        count32k.set(0)
        pool16k.clear()
        count16k.set(0)
        pool4k.clear()
        count4k.set(0)
    }
}