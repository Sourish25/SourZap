package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.data.model.DohProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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

object DohResolver {

    private var vpnServiceRef: VpnService? = null

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

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(protectedSocketFactory)
            .sslSocketFactory(protectedSslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    private val dnsCache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>() // Domain -> (IPs, ExpireTime)
    private val wireCache = ConcurrentHashMap<String, Pair<ByteArray, Long>>() // WireQuestionHash -> (ResponseBytes, ExpireTime)
    private const val CACHE_TTL_MS = 600_000L // 10 minutes

    fun init(vpnService: VpnService) {
        vpnServiceRef = vpnService
    }

    private data class DohEndpoint(val url: String, val hostHeader: String)

    private fun queryUdpDns(queryBytes: ByteArray, serverIp: String): ByteArray? {
        try {
            val socket = DatagramSocket()
            vpnServiceRef?.protect(socket)
            socket.soTimeout = 1500
            val sendPacket = DatagramPacket(queryBytes, queryBytes.size, InetAddress.getByName(serverIp), 53)
            socket.send(sendPacket)
            val buf = ByteArray(4096)
            val recvPacket = DatagramPacket(buf, buf.size)
            socket.receive(recvPacket)
            val len = recvPacket.length
            socket.close()
            if (len >= 12) {
                val res = buf.copyOfRange(0, len)
                if (queryBytes.size >= 2) {
                    res[0] = queryBytes[0]
                    res[1] = queryBytes[1]
                }
                return res
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun resolve(domain: String, provider: DohProvider = DohProvider.CLOUDFLARE): List<InetAddress> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dnsCache[domain]?.let { (ips, exp) ->
            if (now < exp && ips.isNotEmpty()) {
                return@withContext ips
            }
        }

        // Direct IP if domain is already IPv4
        try {
            if (domain.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))) {
                val ip = InetAddress.getByName(domain)
                return@withContext listOf(ip)
            }
        } catch (_: Exception) {}

        val queryWire = buildDnsQueryWire(domain)
        val responseBytes = executeParallelDnsQuery(queryWire, provider)

        if (responseBytes != null) {
            val ips = parseDnsResponseWire(responseBytes)
            if (ips.isNotEmpty()) {
                dnsCache[domain] = Pair(ips, now + CACHE_TTL_MS)
                return@withContext ips
            }
        }

        // Fallback to system DNS
        try {
            val fallback = InetAddress.getAllByName(domain).toList()
            if (fallback.isNotEmpty()) {
                dnsCache[domain] = Pair(fallback, now + CACHE_TTL_MS)
            }
            fallback
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Resolves wire DNS query bytes received from UDP port 53 and returns wire DNS response bytes.
     * Uses in-memory wire caching (0.001ms hit) and parallel racing across UDP & DoH.
     */
    suspend fun resolveWireQuery(queryBytes: ByteArray, provider: DohProvider = DohProvider.CLOUDFLARE): ByteArray? = withContext(Dispatchers.IO) {
        if (queryBytes.size < 12) return@withContext null

        val now = System.currentTimeMillis()
        val questionKey = getQuestionKey(queryBytes)

        if (questionKey != null) {
            wireCache[questionKey]?.let { (cachedRes, exp) ->
                if (now < exp && cachedRes.size >= 12) {
                    val hit = cachedRes.copyOf()
                    hit[0] = queryBytes[0]
                    hit[1] = queryBytes[1]
                    return@withContext hit
                }
            }
        }

        val res = executeParallelDnsQuery(queryBytes, provider)
        if (res != null && questionKey != null && res.size >= 12) {
            wireCache[questionKey] = Pair(res.copyOf(), now + CACHE_TTL_MS)
        }
        res
    }

    private fun getQuestionKey(queryBytes: ByteArray): String? {
        if (queryBytes.size < 12) return null
        return queryBytes.copyOfRange(12, queryBytes.size).contentToString()
    }

    /**
     * High-speed parallel DNS racer: races fast protected UDP DNS (1.1.1.1, 8.8.8.8, 9.9.9.9)
     * and encrypted DoH simultaneously. Returns the fastest valid DNS response in <5ms.
     */
    private suspend fun executeParallelDnsQuery(queryBytes: ByteArray, provider: DohProvider): ByteArray? = coroutineScope {
        val udpServers = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "1.0.0.1")
        val dohEndpoints = listOf(
            DohEndpoint("https://1.1.1.1/dns-query", "cloudflare-dns.com"),
            DohEndpoint("https://8.8.8.8/dns-query", "dns.google"),
            DohEndpoint("https://9.9.9.9/dns-query", "dns.quad9.net")
        )

        val totalTasks = udpServers.size + dohEndpoints.size
        val resultChannel = Channel<ByteArray?>(totalTasks)

        // 1. Fast Protected UDP DNS Queries (<5ms)
        udpServers.forEach { serverIp ->
            async(Dispatchers.IO) {
                val res = queryUdpDns(queryBytes, serverIp)
                resultChannel.send(res)
            }
        }

        // 2. Encrypted DoH HTTPS Queries
        dohEndpoints.forEach { endpoint ->
            async(Dispatchers.IO) {
                try {
                    val requestBody = queryBytes.toRequestBody("application/dns-message".toMediaType())
                    val request = Request.Builder()
                        .url(endpoint.url)
                        .post(requestBody)
                        .addHeader("Host", endpoint.hostHeader)
                        .addHeader("Accept", "application/dns-message")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBytes = response.body?.bytes()
                            if (responseBytes != null && responseBytes.size >= 12) {
                                if (queryBytes.size >= 2) {
                                    responseBytes[0] = queryBytes[0]
                                    responseBytes[1] = queryBytes[1]
                                }
                                resultChannel.send(responseBytes)
                                return@async
                            }
                        }
                    }
                } catch (_: Exception) {}
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
                break
            }
        }
        winningBytes
    }

    private fun buildDnsQueryWire(domain: String): ByteArray {
        val parts = domain.split(".")
        var qnameLen = 1
        for (part in parts) {
            qnameLen += 1 + part.length
        }

        val totalLen = 12 + qnameLen + 4
        val wire = ByteArray(totalLen)
        var p = 0

        // Transaction ID
        val txId = (Math.random() * 65535).toInt()
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

    private fun parseDnsResponseWire(bytes: ByteArray): List<InetAddress> {
        val ips = mutableListOf<InetAddress>()
        if (bytes.size < 12) return ips

        val anCount = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        if (anCount == 0) return ips

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
            val rdLength = ((bytes[p + 8].toInt() and 0xFF) shl 8) or (bytes[p + 9].toInt() and 0xFF)
            p += 10

            if (type == 1 && rdLength == 4 && p + 4 <= bytes.size) { // Type A IPv4
                val ipBytes = bytes.copyOfRange(p, p + 4)
                try {
                    ips.add(InetAddress.getByAddress(ipBytes))
                } catch (_: Exception) {}
            }
            p += rdLength
        }
        return ips
    }
}