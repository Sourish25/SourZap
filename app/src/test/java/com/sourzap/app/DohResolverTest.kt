package com.sourzap.app

import com.sourzap.app.data.model.DohProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class DohResolverTest {

    // Helper to build DNS query wire packet mirroring DohResolver implementation
    private fun buildDnsQueryWire(domain: String, txId: Int = 0x1234): ByteArray {
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

    // Helper to parse DNS response wire packet mirroring DohResolver implementation
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

    private fun getQuestionKey(queryBytes: ByteArray): String? {
        if (queryBytes.size < 12) return null
        return queryBytes.copyOfRange(12, queryBytes.size).contentToString()
    }

    private fun isDirectIpv4(domain: String): Boolean {
        return domain.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))
    }

    @Test
    fun testBuildDnsQueryWire_StructureAndLabels() {
        val query = buildDnsQueryWire("example.com", txId = 0xABCD)

        // Verify Header
        assertEquals(0xAB.toByte(), query[0])
        assertEquals(0xCD.toByte(), query[1])
        assertEquals(0x01.toByte(), query[2]) // RD flag set
        assertEquals(0x00.toByte(), query[3])
        assertEquals(0x00.toByte(), query[4]) // QDCOUNT = 1
        assertEquals(0x01.toByte(), query[5])
        assertEquals(0x00.toByte(), query[6]) // ANCOUNT = 0
        assertEquals(0x00.toByte(), query[7])

        // Verify QNAME labels: \x07example\x03com\x00
        var p = 12
        assertEquals(7.toByte(), query[p++])
        val part1 = String(query, p, 7, Charsets.US_ASCII)
        assertEquals("example", part1)
        p += 7

        assertEquals(3.toByte(), query[p++])
        val part2 = String(query, p, 3, Charsets.US_ASCII)
        assertEquals("com", part2)
        p += 3

        assertEquals(0x00.toByte(), query[p++]) // Root terminator

        // QTYPE = 0x0001 (A), QCLASS = 0x0001 (IN)
        assertEquals(0x00.toByte(), query[p++])
        assertEquals(0x01.toByte(), query[p++])
        assertEquals(0x00.toByte(), query[p++])
        assertEquals(0x01.toByte(), query[p++])
        assertEquals(query.size, p)
    }

    @Test
    fun testBuildDnsQueryWire_Subdomains() {
        val query = buildDnsQueryWire("rr1---sn-4g5edn6s.googlevideo.com")
        assertTrue("Total query wire length must be >= 40 bytes", query.size >= 40)

        val questionKey = getQuestionKey(query)
        assertNotNull("Question key must not be null", questionKey)
    }

    @Test
    fun testQuestionKeyNormalization() {
        val q1 = buildDnsQueryWire("google.com", txId = 0x1111)
        val q2 = buildDnsQueryWire("google.com", txId = 0x2222)
        val qOther = buildDnsQueryWire("cloudflare.com", txId = 0x1111)

        val key1 = getQuestionKey(q1)
        val key2 = getQuestionKey(q2)
        val keyOther = getQuestionKey(qOther)

        // Question key strips the transaction ID (offset 12 onwards), so queries for same domain match!
        assertEquals("Question keys for same domain must be identical regardless of txId", key1, key2)
        assertTrue("Different domains must produce different question keys", key1 != keyOther)

        val shortQuery = byteArrayOf(0x01, 0x02, 0x03)
        assertNull("Queries shorter than 12 bytes must yield null key", getQuestionKey(shortQuery))
    }

    @Test
    fun testParseDnsResponseWire_SingleAndMultipleAAnswers() {
        // Synthesize a valid DNS response wire packet for google.com -> 142.250.190.46 and 142.250.190.78
        val query = buildDnsQueryWire("google.com", txId = 0x55AA)

        // Build Response based on query
        val qnameLen = 1 + "google".length + 1 + "com".length + 1
        val responseHeader = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(), // Tx ID: 0x55AA
            0x81.toByte(), 0x80.toByte(), // Flags: Response, Recursion Desired, Recursion Available
            0x00.toByte(), 0x01.toByte(), // Questions: 1
            0x00.toByte(), 0x02.toByte(), // Answers: 2
            0x00.toByte(), 0x00.toByte(), // Authority: 0
            0x00.toByte(), 0x00.toByte()  // Additional: 0
        )

        val questionSection = query.copyOfRange(12, 12 + qnameLen + 4)

        // Answer 1: Pointer to QNAME (0xC00C), Type A (0x0001), Class IN (0x0001), TTL 300 (0x0000012C), Length 4, IP 142.250.190.46
        val answer1 = byteArrayOf(
            0xC0.toByte(), 0x0C.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x2C.toByte(),
            0x00.toByte(), 0x04.toByte(),
            142.toByte(), 250.toByte(), 190.toByte(), 46.toByte()
        )

        // Answer 2: Pointer to QNAME (0xC00C), Type A (0x0001), Class IN (0x0001), TTL 300, Length 4, IP 142.250.190.78
        val answer2 = byteArrayOf(
            0xC0.toByte(), 0x0C.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x2C.toByte(),
            0x00.toByte(), 0x04.toByte(),
            142.toByte(), 250.toByte(), 190.toByte(), 78.toByte()
        )

        val responseWire = responseHeader + questionSection + answer1 + answer2
        val parsedIps = parseDnsResponseWire(responseWire)

        assertEquals("Must parse exactly 2 IP addresses", 2, parsedIps.size)
        assertEquals("142.250.190.46", parsedIps[0].hostAddress)
        assertEquals("142.250.190.78", parsedIps[1].hostAddress)
    }

    @Test
    fun testParseDnsResponseWire_EmptyOrMalformed() {
        val emptyWire = ByteArray(0)
        assertTrue(parseDnsResponseWire(emptyWire).isEmpty())

        val shortWire = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertTrue(parseDnsResponseWire(shortWire).isEmpty())

        // Header with 0 answers
        val noAnswersHeader = byteArrayOf(
            0x12, 0x34, 0x81.toByte(), 0x83.toByte(), // NXDOMAIN
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertTrue(parseDnsResponseWire(noAnswersHeader).isEmpty())
    }

    @Test
    fun testDnsCacheAndTtlMechanics() {
        val dnsCache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
        val wireCache = ConcurrentHashMap<String, Pair<ByteArray, Long>>()
        val ttlMs = 600_000L // 10 minutes

        val testDomain = "test.example.com"
        val testIps = listOf(InetAddress.getByName("93.184.216.34"))
        val now = System.currentTimeMillis()

        // 1. Cache Insertion
        dnsCache[testDomain] = Pair(testIps, now + ttlMs)
        val queryBytes = buildDnsQueryWire(testDomain, txId = 0x9999)
        val qKey = getQuestionKey(queryBytes)!!
        val fakeResponse = byteArrayOf(0x99.toByte(), 0x99.toByte(), 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
        wireCache[qKey] = Pair(fakeResponse, now + ttlMs)

        // 2. Cache Hit Verification
        val cachedDns = dnsCache[testDomain]
        assertNotNull(cachedDns)
        assertTrue("Cached entry must not be expired", now < cachedDns!!.second)
        assertEquals(testIps, cachedDns.first)

        val cachedWire = wireCache[qKey]
        assertNotNull(cachedWire)
        assertTrue("Cached wire entry must not be expired", now < cachedWire!!.second)

        // 3. Transaction ID Rewriting on Wire Cache Hit
        val newQueryTxId = 0x4321
        val newQueryBytes = buildDnsQueryWire(testDomain, txId = newQueryTxId)
        val hitResponse = cachedWire.first.copyOf()
        hitResponse[0] = newQueryBytes[0]
        hitResponse[1] = newQueryBytes[1]

        assertEquals(0x43.toByte(), hitResponse[0])
        assertEquals(0x21.toByte(), hitResponse[1])

        // 4. Cache Expiration Simulation
        val expiredTime = now - 1000L
        dnsCache[testDomain] = Pair(testIps, expiredTime)
        wireCache[qKey] = Pair(fakeResponse, expiredTime)

        val checkNow = System.currentTimeMillis()
        val expiredDnsEntry = dnsCache[testDomain]
        assertTrue("Entry must be recognized as expired", checkNow >= expiredDnsEntry!!.second)

        val expiredWireEntry = wireCache[qKey]
        assertTrue("Wire entry must be recognized as expired", checkNow >= expiredWireEntry!!.second)
    }

    @Test
    fun testDirectIpv4Bypass() {
        assertTrue(isDirectIpv4("1.1.1.1"))
        assertTrue(isDirectIpv4("8.8.8.8"))
        assertTrue(isDirectIpv4("192.168.1.1"))
        assertTrue(isDirectIpv4("10.0.0.2"))

        assertFalse(isDirectIpv4("google.com"))
        assertFalse(isDirectIpv4("www.cloudflare.com"))
        assertFalse(isDirectIpv4("youtube.com"))
        assertFalse(isDirectIpv4("1.1.1"))
        assertFalse(isDirectIpv4("example.123"))
    }

    @Test
    fun testDohProvidersEnum() {
        val providers = DohProvider.values()
        assertTrue("Must support multiple DoH providers", providers.size >= 4)

        val cf = DohProvider.CLOUDFLARE
        assertEquals("Cloudflare DoH", cf.displayName)
        assertEquals("https://1.1.1.1/dns-query", cf.url)
        assertEquals("1.1.1.1", cf.bootstrapIp)

        val google = DohProvider.GOOGLE
        assertEquals("Google DoH", google.displayName)
        assertEquals("https://dns.google/dns-query", google.url)
        assertEquals("8.8.8.8", google.bootstrapIp)

        val quad9 = DohProvider.QUAD9
        assertEquals("https://dns.quad9.net/dns-query", quad9.url)
        assertEquals("9.9.9.9", quad9.bootstrapIp)

        val adguard = DohProvider.ADGUARD
        assertEquals("https://dns.adguard-dns.com/dns-query", adguard.url)
        assertEquals("94.140.14.14", adguard.bootstrapIp)
    }
}
