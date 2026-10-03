package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.data.model.DohProvider
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Ultra-Fast High-Performance Asynchronous DNS-over-HTTPS & Protected UDP Resolver.
 * Features an in-memory thread-safe LRU DNS Cache with TTL expiration and in-flight query coalescing (Singleflight).
 * Resilient multi-provider failover across Cloudflare, Google, Quad9, and AdGuard with redundant bootstrap IPs.
 * Instant winner resolution with proactive coroutine cancellation to eliminate socket timeout latency barriers.
 */
object DohResolver {

    @Volatile
    var defaultProvider: DohProvider = DohProvider.CLOUDFLARE

    private var vpnServiceRef: VpnService? = null

    const val DEFAULT_CACHE_TTL_MS = 300_000L // 5 minutes standard TTL
    private const val MIN_CACHE_TTL_MS = 60_000L   // 1 minute floor
    private const val MAX_CACHE_TTL_MS = 600_000L  // 10 minutes ceiling
    private const val MAX_LRU_CAPACITY = 4096

    /**
     * High-performance, thread-safe generic LRU Cache with TTL expiration.
     */
    class DnsLruCache<K, V>(
        private val maxCapacity: Int = MAX_LRU_CAPACITY,
        private val defaultTtlMs: Long = DEFAULT_CACHE_TTL_MS
    ) {
        private val lock = Any()
        private val map = object : LinkedHashMap<K, CacheEntry<V>>(maxCapacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CacheEntry<V>>?): Boolean {
                return size > maxCapacity
            }
        }

        data class CacheEntry<V>(
            val value: V,
            val expireTimeMs: Long
        )

        fun get(key: K): V? {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val entry = map[key] ?: return null
                if (now > entry.expireTimeMs) {
                    map.remove(key)
                    return null
                }
                return entry.value
            }
        }

        fun put(key: K, value: V, ttlMs: Long = defaultTtlMs) {
            val expireTimeMs = System.currentTimeMillis() + ttlMs.coerceAtLeast(1L)
            synchronized(lock) {
                map[key] = CacheEntry(value, expireTimeMs)
            }
        }

        fun remove(key: K) {
            synchronized(lock) {
                map.remove(key)
            }
        }

        fun clear() {
            synchronized(lock) {
                map.clear()
            }
        }

        fun size(): Int = synchronized(lock) { map.size }

        fun pruneExpired() {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val iterator = map.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now > entry.value.expireTimeMs) {
                        iterator.remove()
                    }
                }
            }
        }
    }

    /**
     * Efficient, immutable key for DNS wire questions (skipping 12-byte header transaction ID)
     * avoids expensive string formatting / allocations.
     */
    class WireQuestionKey(private val questionBytes: ByteArray) {
        private val hash: Int = questionBytes.contentHashCode()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WireQuestionKey) return false
            return questionBytes.contentEquals(other.questionBytes)
        }

        override fun hashCode(): Int = hash

        companion object {
            fun fromQuery(queryBytes: ByteArray): WireQuestionKey? {
                if (queryBytes.size < 12) return null
                val q = queryBytes.copyOfRange(12, queryBytes.size)
                return WireQuestionKey(q)
            }
        }
    }

    // In-memory Thread-Safe LRU Caches with 5-Minute TTL
    private val domainCache = DnsLruCache<String, List<InetAddress>>(MAX_LRU_CAPACITY, DEFAULT_CACHE_TTL_MS)
    private val wireCache = DnsLruCache<WireQuestionKey, ByteArray>(MAX_LRU_CAPACITY, DEFAULT_CACHE_TTL_MS)

    // Singleflight in-flight domain query deduplication map
    private val inFlightDomainQueries = ConcurrentHashMap<String, Deferred<List<InetAddress>>>()
    // Singleflight in-flight wire query deduplication map
    private val inFlightWireQueries = ConcurrentHashMap<WireQuestionKey, Deferred<ByteArray?>>()

    private val dnsDispatcher = Dispatchers.IO.limitedParallelism(16)

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
    }

    private val protectedSslSocketFactory: SSLSocketFactory by lazy {
        object : SSLSocketFactory() {
            private val delegate = sslContext.socketFactory

            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

            override fun createSocket(): Socket {
                val s = delegate.createSocket()
                vpnServiceRef?.protect(s)
                return s
            }

            override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                vpnServiceRef?.protect(s)
                val ssl = delegate.createSocket(s, host, port, autoClose)
                vpnServiceRef?.protect(ssl)
                return ssl
            }

            override fun createSocket(host: String, port: Int): Socket {
                val s = delegate.createSocket(host, port)
                vpnServiceRef?.protect(s)
                return s
            }

            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
                val s = delegate.createSocket(host, port, localHost, localPort)
                vpnServiceRef?.protect(s)
                return s
            }

            override fun createSocket(host: InetAddress, port: Int): Socket {
                val s = delegate.createSocket(host, port)
                vpnServiceRef?.protect(s)
                return s
            }

            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
                val s = delegate.createSocket(address, port, localAddress, localPort)
                vpnServiceRef?.protect(s)
                return s
            }
        }
    }

    private val protectedSocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket {
            val s = Socket()
            vpnServiceRef?.protect(s)
            return s
        }

        override fun createSocket(host: String, port: Int): Socket {
            val s = Socket()
            vpnServiceRef?.protect(s)
            s.connect(InetSocketAddress(host, port), 2000)
            return s
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            val s = Socket()
            vpnServiceRef?.protect(s)
            s.bind(InetSocketAddress(localHost, localPort))
            s.connect(InetSocketAddress(host, port), 2000)
            return s
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            val s = Socket()
            vpnServiceRef?.protect(s)
            s.connect(InetSocketAddress(host, port), 2000)
            return s
        }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
            val s = Socket()
            vpnServiceRef?.protect(s)
            s.bind(InetSocketAddress(localAddress, localPort))
            s.connect(InetSocketAddress(address, port), 2000)
            return s
        }
    }

    private val okHttpConnectionPool = ConnectionPool(32, 5, TimeUnit.MINUTES)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(okHttpConnectionPool)
            .socketFactory(protectedSocketFactory)
            .sslSocketFactory(protectedSslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun init(vpnService: VpnService) {
        vpnServiceRef = vpnService
    }

    fun clearCache() {
        domainCache.clear()
        wireCache.clear()
        inFlightDomainQueries.clear()
        inFlightWireQueries.clear()
    }

    fun getCacheSize(): Int = domainCache.size()

    private data class DohEndpoint(val url: String, val hostHeader: String)

    private fun queryUdpDns(
        queryBytes: ByteArray,
        serverIp: String,
        onSocketCreated: (DatagramSocket) -> Unit = {}
    ): ByteArray? {
        var socket: DatagramSocket? = null
        try {
            val s = DatagramSocket()
            socket = s
            onSocketCreated(s)
            vpnServiceRef?.protect(s)
            s.soTimeout = 1500
            val sendPacket = DatagramPacket(queryBytes, queryBytes.size, InetAddress.getByName(serverIp), 53)
            s.send(sendPacket)
            val buf = ByteArray(4096)
            val recvPacket = DatagramPacket(buf, buf.size)
            s.receive(recvPacket)
            val len = recvPacket.length
            if (len >= 12 && isValidDnsResponse(buf, len)) {
                val res = buf.copyOfRange(0, len)
                if (queryBytes.size >= 2) {
                    res[0] = queryBytes[0]
                    res[1] = queryBytes[1]
                }
                return res
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Checks if a wire DNS response is valid (Response flag QR set, RCODE == 0 / NOERROR, and length >= 12).
     */
    private fun isValidDnsResponse(buffer: ByteArray, length: Int): Boolean {
        if (length < 12) return false
        val flagsHigh = buffer[2].toInt() and 0xFF
        val flagsLow = buffer[3].toInt() and 0xFF
        val isResponse = (flagsHigh and 0x80) != 0
        val rcode = flagsLow and 0x0F
        val anCount = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
        return isResponse && rcode == 0 && anCount > 0
    }

    /**
     * Resolves a domain to a list of IP addresses.
     * Uses in-memory thread-safe LRU caching (0ms hit) with TTL and Singleflight deduplication.
     */
    suspend fun resolve(domain: String, provider: DohProvider = DohProvider.CLOUDFLARE): List<InetAddress> = withContext(dnsDispatcher) {
        val normalizedDomain = domain.trim().lowercase().removeSuffix(".")
        if (normalizedDomain.isEmpty()) return@withContext emptyList()

        // 1. Check in-memory LRU Cache (0ms hit)
        domainCache.get(normalizedDomain)?.let { cachedIps ->
            if (cachedIps.isNotEmpty()) {
                return@withContext cachedIps
            }
        }

        // 2. Direct IP check if domain is already an IPv4 literal
        try {
            if (normalizedDomain.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))) {
                val ip = InetAddress.getByName(normalizedDomain)
                val res = listOf(ip)
                domainCache.put(normalizedDomain, res, DEFAULT_CACHE_TTL_MS)
                return@withContext res
            }
        } catch (_: Exception) {}

        // 3. Singleflight coalescing: coalesce concurrent requests for the exact same domain
        val deferred = inFlightDomainQueries.computeIfAbsent(normalizedDomain) {
            async(dnsDispatcher) {
                try {
                    val queryWire = buildDnsQueryWire(normalizedDomain)
                    val responseBytes = executeParallelDnsQuery(queryWire, provider)

                    if (responseBytes != null) {
                        val parseResult = parseDnsResponseWireWithTtl(responseBytes)
                        if (parseResult.ips.isNotEmpty()) {
                            domainCache.put(normalizedDomain, parseResult.ips, parseResult.ttlMs)
                            return@async parseResult.ips
                        }
                    }

                    // Fallback to system DNS
                    try {
                        val fallback = InetAddress.getAllByName(normalizedDomain).toList()
                        if (fallback.isNotEmpty()) {
                            domainCache.put(normalizedDomain, fallback, DEFAULT_CACHE_TTL_MS)
                        }
                        fallback
                    } catch (_: Exception) {
                        emptyList()
                    }
                } finally {
                    inFlightDomainQueries.remove(normalizedDomain)
                }
            }
        }

        deferred.await()
    }

    /**
     * Resolves wire DNS query bytes received from UDP port 53 and returns wire DNS response bytes.
     * Uses in-memory wire caching (0ms hit), singleflight deduplication, and bounded parallel racing across UDP & DoH.
     */
    suspend fun resolveWireQuery(queryBytes: ByteArray, provider: DohProvider = DohProvider.CLOUDFLARE): ByteArray? = withContext(dnsDispatcher) {
        if (queryBytes.size < 12) return@withContext null

        val questionKey = WireQuestionKey.fromQuery(queryBytes)

        if (questionKey != null) {
            wireCache.get(questionKey)?.let { cachedRes ->
                if (cachedRes.size >= 12) {
                    val hit = cachedRes.copyOf()
                    hit[0] = queryBytes[0]
                    hit[1] = queryBytes[1]
                    return@withContext hit
                }
            }
        }

        if (questionKey == null) {
            return@withContext executeParallelDnsQuery(queryBytes, provider)
        }

        // In-Flight Singleflight Deduplication for wire queries
        val deferred = inFlightWireQueries.computeIfAbsent(questionKey) {
            async(dnsDispatcher) {
                try {
                    val res = executeParallelDnsQuery(queryBytes, provider)
                    if (res != null && res.size >= 12) {
                        val parseResult = parseDnsResponseWireWithTtl(res)
                        wireCache.put(questionKey, res.copyOf(), parseResult.ttlMs)
                    }
                    res
                } finally {
                    inFlightWireQueries.remove(questionKey)
                }
            }
        }

        val res = deferred.await()
        if (res != null && res.size >= 12) {
            val response = res.copyOf()
            response[0] = queryBytes[0]
            response[1] = queryBytes[1]
            response
        } else {
            res
        }
    }

    /**
     * High-speed bounded parallel DNS racer: races top 2-3 fast protected UDP DNS and top 2 encrypted DoH endpoints
     * on a dedicated bounded dispatcher. Proactively cancels child OkHttp calls and closes DatagramSockets
     * upon receiving the first valid winner to eliminate thread pool starvation and socket timeout latency barriers.
     */
    private suspend fun executeParallelDnsQuery(queryBytes: ByteArray, provider: DohProvider): ByteArray? = coroutineScope {
        val primaryHost = provider.hostHeader.ifEmpty { "cloudflare-dns.com" }

        // Bound to top 2-3 UDP endpoints
        val udpServers = listOfNotNull(
            provider.bootstrapIp,
            provider.backupIps.firstOrNull() ?: (if (provider.bootstrapIp != "1.1.1.1") "1.1.1.1" else "8.8.8.8"),
            if (provider.bootstrapIp != "8.8.8.8" && !provider.backupIps.contains("8.8.8.8")) "8.8.8.8" else "1.1.1.1"
        ).distinct().take(3)

        // Bound to top 2 DoH endpoints
        val fallbackProvider = DohProvider.values().firstOrNull { it != provider } ?: DohProvider.GOOGLE
        val fallbackHost = fallbackProvider.hostHeader.ifEmpty { "dns.google" }

        val dohEndpoints = listOf(
            DohEndpoint("https://${provider.bootstrapIp}/dns-query", primaryHost),
            if (provider.backupIps.isNotEmpty()) {
                DohEndpoint("https://${provider.backupIps.first()}/dns-query", primaryHost)
            } else {
                DohEndpoint("https://${fallbackProvider.bootstrapIp}/dns-query", fallbackHost)
            }
        ).distinct().take(2)

        val totalTasks = udpServers.size + dohEndpoints.size
        val resultChannel = Channel<ByteArray?>(totalTasks)

        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<DatagramSocket>())
        val activeCalls = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>())

        // 1. Fast Protected UDP DNS Queries (Bounded & trackable)
        udpServers.forEach { serverIp ->
            async(dnsDispatcher) {
                val res = queryUdpDns(queryBytes, serverIp) { s ->
                    activeSockets.add(s)
                }
                resultChannel.send(res)
            }
        }

        // 2. Encrypted DoH HTTPS Queries (Bounded & cancelable)
        dohEndpoints.forEach { endpoint ->
            async(dnsDispatcher) {
                var call: okhttp3.Call? = null
                try {
                    val requestBody = queryBytes.toRequestBody("application/dns-message".toMediaType())
                    val request = Request.Builder()
                        .url(endpoint.url)
                        .post(requestBody)
                        .addHeader("Host", endpoint.hostHeader)
                        .addHeader("Accept", "application/dns-message")
                        .build()

                    val c = httpClient.newCall(request)
                    call = c
                    activeCalls.add(c)

                    c.execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBytes = response.body?.bytes()
                            if (responseBytes != null && responseBytes.size >= 12 && isValidDnsResponse(responseBytes, responseBytes.size)) {
                                if (queryBytes.size >= 2) {
                                    responseBytes[0] = queryBytes[0]
                                    responseBytes[1] = queryBytes[1]
                                }
                                resultChannel.send(responseBytes)
                                return@async
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    call?.let { activeCalls.remove(it) }
                }
                resultChannel.send(null)
            }
        }

        var completed = 0
        var winningBytes: ByteArray? = null
        while (completed < totalTasks) {
            val res = resultChannel.receive()
            completed++
            if (res != null) {
                winningBytes = res
                // Proactively abort all slower racer tasks immediately in 0ms:
                // 1. Cancel OkHttp calls to abort active HTTP/2 or TCP streams
                activeCalls.forEach { try { it.cancel() } catch (_: Exception) {} }
                // 2. Close DatagramSockets to unblock blocking socket.receive() syscalls immediately
                activeSockets.forEach { try { it.close() } catch (_: Exception) {} }
                // 3. Cancel coroutine children
                coroutineContext[Job]?.cancelChildren()
                break
            }
        }
        winningBytes
    }

    fun buildDnsQueryWire(domain: String, txId: Int = (Math.random() * 65535).toInt()): ByteArray {
        val parts = domain.split(".")
        var qnameLen = 1
        for (part in parts) {
            qnameLen += 1 + part.length
        }

        val totalLen = 12 + qnameLen + 4
        val wire = ByteArray(totalLen)
        var p = 0

        // Transaction ID
        wire[p++] = ((txId shr 8) and 0xFF).toByte()
        wire[p++] = (txId and 0xFF).toByte()

        // Flags: Standard query, Recursion Desired (0x0100)
        wire[p++] = 0x01.toByte()
        wire[p++] = 0x00.toByte()

        // Questions: 1
        wire[p++] = 0x00.toByte()
        wire[p++] = 0x01.toByte()

        // Answer RRs: 0, Authority RRs: 0, Additional RRs: 0
        p += 6

        // QNAME
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.US_ASCII)
            wire[p++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, wire, p, bytes.size)
            p += bytes.size
        }
        wire[p++] = 0x00.toByte() // End of QNAME

        // QTYPE: A (0x0001)
        wire[p++] = 0x00.toByte()
        wire[p++] = 0x01.toByte()

        // QCLASS: IN (0x0001)
        wire[p++] = 0x00.toByte()
        wire[p++] = 0x01.toByte()

        return wire
    }

    data class DnsParseResult(
        val ips: List<InetAddress>,
        val ttlMs: Long
    )

    fun parseDnsResponseWireWithTtl(bytes: ByteArray): DnsParseResult {
        val ips = mutableListOf<InetAddress>()
        if (bytes.size < 12) return DnsParseResult(ips, DEFAULT_CACHE_TTL_MS)

        val anCount = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        if (anCount == 0) return DnsParseResult(ips, DEFAULT_CACHE_TTL_MS)

        var minTtlSec = DEFAULT_CACHE_TTL_MS / 1000L
        var p = 12

        // Skip Question Section
        while (p < bytes.size && bytes[p].toInt() != 0) {
            if ((bytes[p].toInt() and 0xC0) == 0xC0) {
                p += 2
                break
            } else {
                val len = bytes[p].toInt() and 0xFF
                p += 1 + len
            }
        }
        if (p < bytes.size && bytes[p].toInt() == 0) p += 1 // 0x00 root
        p += 4 // Skip QTYPE + QCLASS

        // Parse Answer RRs
        for (i in 0 until anCount) {
            if (p >= bytes.size) break
            // NAME (either pointer or labels)
            if ((bytes[p].toInt() and 0xC0) == 0xC0) {
                p += 2
            } else {
                while (p < bytes.size && bytes[p].toInt() != 0) {
                    val len = bytes[p].toInt() and 0xFF
                    p += 1 + len
                }
                if (p < bytes.size) p += 1
            }

            if (p + 10 > bytes.size) break
            val type = ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)
            val ttl = (((bytes[p + 4].toLong() and 0xFF) shl 24) or
                    ((bytes[p + 5].toLong() and 0xFF) shl 16) or
                    ((bytes[p + 6].toLong() and 0xFF) shl 8) or
                    (bytes[p + 7].toLong() and 0xFF))
            val rdLength = ((bytes[p + 8].toInt() and 0xFF) shl 8) or (bytes[p + 9].toInt() and 0xFF)
            p += 10

            if (ttl in 1..86400) {
                minTtlSec = minOf(minTtlSec, ttl)
            }

            if (type == 1 && rdLength == 4 && p + 4 <= bytes.size) { // Type A IPv4
                val ipBytes = bytes.copyOfRange(p, p + 4)
                try {
                    ips.add(InetAddress.getByAddress(ipBytes))
                } catch (_: Exception) {}
            }
            p += rdLength
        }

        val effectiveTtlMs = (minTtlSec * 1000L).coerceIn(MIN_CACHE_TTL_MS, MAX_CACHE_TTL_MS)
        return DnsParseResult(ips, effectiveTtlMs)
    }
}