package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.data.model.DohProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

object DohResolver {

    private var vpnServiceRef: VpnService? = null

    private val protectedSocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket {
            val s = Socket()
            vpnServiceRef?.protect(s)
            return s
        }

        override fun createSocket(host: String, port: Int): Socket {
            val s = Socket()
            vpnServiceRef?.protect(s)
            s.connect(InetSocketAddress(host, port), 4000)
            return s
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            val s = Socket()
            vpnServiceRef?.protect(s)
            s.bind(InetSocketAddress(localHost, localPort))
            s.connect(InetSocketAddress(host, port), 4000)
            return s
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            val s = Socket()
            vpnServiceRef?.protect(s)
            s.connect(InetSocketAddress(host, port), 4000)
            return s
        }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
            val s = Socket()
            vpnServiceRef?.protect(s)
            s.bind(InetSocketAddress(localAddress, localPort))
            s.connect(InetSocketAddress(address, port), 4000)
            return s
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .socketFactory(protectedSocketFactory)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val dnsCache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>() // Domain -> (IPs, ExpireTime)
    private const val CACHE_TTL_MS = 600_000L // 10 minutes

    fun init(vpnService: VpnService) {
        vpnServiceRef = vpnService
    }

    // Direct IP DoH endpoints to avoid any DNS bootstrapping lookup
    private data class DohEndpoint(val url: String, val hostHeader: String)

    private val ENDPOINTS = mapOf(
        DohProvider.CLOUDFLARE to listOf(
            DohEndpoint("https://1.1.1.1/dns-query", "cloudflare-dns.com"),
            DohEndpoint("https://1.0.0.1/dns-query", "cloudflare-dns.com")
        ),
        DohProvider.GOOGLE to listOf(
            DohEndpoint("https://8.8.8.8/dns-query", "dns.google"),
            DohEndpoint("https://8.8.4.4/dns-query", "dns.google")
        ),
        DohProvider.QUAD9 to listOf(
            DohEndpoint("https://9.9.9.9/dns-query", "dns.quad9.net"),
            DohEndpoint("https://149.112.112.112/dns-query", "dns.quad9.net")
        )
    )

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
        val responseBytes = executeDohQuery(queryWire, provider)

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
     * Uses protected sockets to prevent recursive routing loops.
     */
    suspend fun resolveWireQuery(queryBytes: ByteArray, provider: DohProvider = DohProvider.CLOUDFLARE): ByteArray? = withContext(Dispatchers.IO) {
        executeDohQuery(queryBytes, provider)
    }

    private fun executeDohQuery(queryBytes: ByteArray, provider: DohProvider): ByteArray? {
        val orderedProviders = listOf(provider) + DohProvider.entries.filter { it != provider }

        for (p in orderedProviders) {
            val endpoints = ENDPOINTS[p] ?: continue
            for (endpoint in endpoints) {
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
                                return responseBytes
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return null
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