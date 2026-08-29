package com.sourzap.app.service.core

import com.sourzap.app.data.model.DohProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DohResolver {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val dnsCache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>() // Domain -> (IPs, ExpireTime)
    private const val CACHE_TTL_MS = 300_000L // 5 minutes

    suspend fun resolve(domain: String, provider: DohProvider = DohProvider.CLOUDFLARE): List<InetAddress> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dnsCache[domain]?.let { (ips, exp) ->
            if (now < exp && ips.isNotEmpty()) {
                return@withContext ips
            }
        }

        // Try direct IP if it's already an IP
        try {
            if (domain.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))) {
                val ip = InetAddress.getByName(domain)
                return@withContext listOf(ip)
            }
        } catch (_: Exception) {}

        try {
            // Build raw DNS wire-format query (Type A)
            val queryWire = buildDnsQueryWire(domain)
            val requestBody = queryWire.toRequestBody("application/dns-message".toMediaType())

            val request = Request.Builder()
                .url(provider.url)
                .post(requestBody)
                .addHeader("Accept", "application/dns-message")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBytes = response.body?.bytes()
                    if (responseBytes != null) {
                        val ips = parseDnsResponseWire(responseBytes)
                        if (ips.isNotEmpty()) {
                            dnsCache[domain] = Pair(ips, now + CACHE_TTL_MS)
                            return@withContext ips
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to local resolver if DoH fails
        }

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
     * Resolves wire DNS query bytes received from UDP port 53 and returns wire DNS response bytes
     */
    suspend fun resolveWireQuery(queryBytes: ByteArray, provider: DohProvider = DohProvider.CLOUDFLARE): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val requestBody = queryBytes.toRequestBody("application/dns-message".toMediaType())
            val request = Request.Builder()
                .url(provider.url)
                .post(requestBody)
                .addHeader("Accept", "application/dns-message")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext response.body?.bytes()
                }
            }
        } catch (_: Exception) {}
        null
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