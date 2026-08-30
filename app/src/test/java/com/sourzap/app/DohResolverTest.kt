package com.sourzap.app

import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.service.core.DohResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DohResolverTest {

    // Helper to build DNS query wire packet mirroring DohResolver implementation
    private fun buildDnsQueryWire(domain: String, txId: Int = 0x1234, qType: Int = 1): ByteArray {
        val parts = domain.split(".").filter { it.isNotEmpty() }
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

        // QTYPE
        wire[p++] = ((qType shr 8) and 0xFF).toByte()
        wire[p++] = (qType and 0xFF).toByte()

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
    fun testBuildDnsQueryWire_ExtremeDomains() {
        // Single letter labels: a.b
        val shortQuery = buildDnsQueryWire("a.b", txId = 0x1111)
        assertEquals(12 + 1 + 1 + 1 + 1 + 1 + 4, shortQuery.size)

        // Deep subdomain tree
        val deepDomain = "a.b.c.d.e.f.g.h.i.j.k.example.org"
        val deepQuery = buildDnsQueryWire(deepDomain)
        val deepKey = getQuestionKey(deepQuery)
        assertNotNull(deepKey)

        // 63-character max DNS label
        val maxLabel = "a".repeat(63)
        val maxLabelDomain = "$maxLabel.com"
        val maxQuery = buildDnsQueryWire(maxLabelDomain)
        assertEquals(63.toByte(), maxQuery[12])
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
    fun testParseDnsResponseWire_CorruptedPointersAndLoops() {
        // 1. Out-of-bounds pointer 0xC0FF (points past buffer length)
        val query = buildDnsQueryWire("example.com")
        val malformedHeader = byteArrayOf(
            0x12, 0x34, 0x81.toByte(), 0x80.toByte(),
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
        )
        val questionSection = query.copyOfRange(12, query.size)
        val corruptAnswer = byteArrayOf(
            0xC0.toByte(), 0xFF.toByte(),
            0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,
            0x00, 0x04,
            1, 2, 3, 4
        )
        val wireWithOobPointer = malformedHeader + questionSection + corruptAnswer
        val ips = parseDnsResponseWire(wireWithOobPointer)
        assertEquals(1, ips.size)
        assertEquals("1.2.3.4", ips[0].hostAddress)

        // 2. Declared answer count = 50, but packet truncates after 1 answer
        val truncatedAnswerHeader = byteArrayOf(
            0x12, 0x34, 0x81.toByte(), 0x80.toByte(),
            0x00, 0x01, 0x00, 0x32, 0x00, 0x00, 0x00, 0x00 // anCount = 50
        )
        val wireTruncatedAnswers = truncatedAnswerHeader + questionSection + corruptAnswer
        val parsedFromTruncated = parseDnsResponseWire(wireTruncatedAnswers)
        assertEquals(1, parsedFromTruncated.size)

        // 3. Answer record with rdLength = 4, but only 2 bytes of payload left in packet
        val truncatedIpAnswer = byteArrayOf(
            0xC0.toByte(), 0x0C.toByte(),
            0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,
            0x00, 0x04,
            1, 2 // Only 2 bytes!
        )
        val wireWithShortIp = malformedHeader + questionSection + truncatedIpAnswer
        val ipsShort = parseDnsResponseWire(wireWithShortIp)
        assertTrue("Truncated IP payload must not be parsed or crash", ipsShort.isEmpty())
    }

    @Test
    fun testParseDnsResponseWire_MixedNonARecords() {
        // DNS response containing CNAME (type 5) + AAAA (type 28) + A (type 1)
        val query = buildDnsQueryWire("cdn.example.com")
        val header = byteArrayOf(
            0x12, 0x34, 0x81.toByte(), 0x80.toByte(),
            0x00, 0x01, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00 // anCount = 3
        )
        val question = query.copyOfRange(12, query.size)

        // Answer 1: CNAME (Type 5, rdLength 10)
        val cnameAnswer = byteArrayOf(
            0xC0.toByte(), 0x0C.toByte(),
            0x00, 0x05, // Type 5 (CNAME)
            0x00, 0x01,
            0x00, 0x00, 0x01, 0x00,
            0x00, 0x06,
            0x03, 'e'.code.toByte(), 'd'.code.toByte(), 'g'.code.toByte(), 0x00, 0x00
        )

        // Answer 2: AAAA (Type 28, rdLength 16 - IPv6)
        val aaaaAnswer = byteArrayOf(
            0xC0.toByte(), 0x0C.toByte(),
            0x00, 0x1C.toByte(), // Type 28 (AAAA)
            0x00, 0x01,
            0x00, 0x00, 0x01, 0x00,
            0x00, 0x10, // rdLength 16
            0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        )

        // Answer 3: A (Type 1, rdLength 4 - IPv4 93.184.216.34)
        val aAnswer = byteArrayOf(
            0xC0.toByte(), 0x0C.toByte(),
            0x00, 0x01, // Type 1 (A)
            0x00, 0x01,
            0x00, 0x00, 0x01, 0x00,
            0x00, 0x04,
            93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()
        )

        val wire = header + question + cnameAnswer + aaaaAnswer + aAnswer
        val ips = parseDnsResponseWire(wire)

        assertEquals("Must extract only the IPv4 A record", 1, ips.size)
        assertEquals("93.184.216.34", ips[0].hostAddress)
    }

    @Test
    fun testDnsLruCache_HighConcurrencyStress50Coroutines() = runBlocking {
        val cache = DohResolver.DnsLruCache<String, String>(maxCapacity = 100, defaultTtlMs = 5000L)
        val threadCount = 50
        val opsPerThread = 500

        val errorCount = AtomicInteger(0)

        val jobs = (1..threadCount).map { threadId ->
            async(Dispatchers.IO) {
                try {
                    for (i in 0 until opsPerThread) {
                        val key = "domain-${(threadId * 100 + i) % 150}.com"
                        val ip = "10.0.${threadId % 250}.${i % 250}"

                        when (i % 5) {
                            0 -> cache.put(key, ip, 5000L)
                            1 -> cache.get(key)
                            2 -> cache.remove(key)
                            3 -> cache.pruneExpired()
                            4 -> cache.size()
                        }
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                }
            }
        }

        jobs.awaitAll()

        assertEquals("No concurrency race exceptions should occur under 50 coroutines stress", 0, errorCount.get())
        assertTrue("Cache size must never exceed max capacity (100)", cache.size() <= 100)
    }

    @Test
    fun testDnsLruCache_LruEvictionAndOrderIntegrity() {
        val cache = DohResolver.DnsLruCache<String, Int>(maxCapacity = 4, defaultTtlMs = 60_000L)

        cache.put("k1", 1)
        cache.put("k2", 2)
        cache.put("k3", 3)
        cache.put("k4", 4)
        assertEquals(4, cache.size())

        // Access k1, k2 -> k3 becomes the least recently used
        assertEquals(1, cache.get("k1"))
        assertEquals(2, cache.get("k2"))

        // Add k5 -> k3 must be evicted!
        cache.put("k5", 5)
        assertEquals(4, cache.size())
        assertNull("k3 should have been evicted as LRU", cache.get("k3"))
        assertNotNull("k1 was accessed recently, must stay", cache.get("k1"))
        assertNotNull("k2 was accessed recently, must stay", cache.get("k2"))
        assertNotNull("k4 must stay", cache.get("k4"))
        assertNotNull("k5 must exist", cache.get("k5"))

        // Clear cache
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("k1"))
    }

    @Test
    fun testDnsLruCache_TtlExpirationAndPruning() {
        val cache = DohResolver.DnsLruCache<String, String>(maxCapacity = 10, defaultTtlMs = 50L)

        cache.put("fast.expire", "1.2.3.4", 50L) // 50ms TTL
        cache.put("slow.expire", "5.6.7.8", 10_000L) // 10s TTL

        assertEquals("1.2.3.4", cache.get("fast.expire"))
        assertEquals("5.6.7.8", cache.get("slow.expire"))

        Thread.sleep(80)

        // fast.expire is expired on get
        assertNull("Expired entry must return null on get", cache.get("fast.expire"))
        assertNotNull("Non-expired entry must still be valid", cache.get("slow.expire"))

        cache.put("another.expired", "9.9.9.9", 10L)
        Thread.sleep(30)
        cache.pruneExpired()
        assertNull(cache.get("another.expired"))
    }

    @Test
    fun testWireQuestionKey_AdversarialByteArrays() {
        // Query shorter than 12 bytes
        val short1 = ByteArray(11)
        assertNull(DohResolver.WireQuestionKey.fromQuery(short1))

        val empty = ByteArray(0)
        assertNull(DohResolver.WireQuestionKey.fromQuery(empty))

        // Valid queries with different TxIDs
        val q1 = buildDnsQueryWire("test.local", txId = 0xAAAA, qType = 1)
        val q2 = buildDnsQueryWire("test.local", txId = 0xBBBB, qType = 1)
        val k1 = DohResolver.WireQuestionKey.fromQuery(q1)
        val k2 = DohResolver.WireQuestionKey.fromQuery(q2)

        assertNotNull(k1)
        assertNotNull(k2)
        assertEquals(k1, k2)
        assertEquals(k1.hashCode(), k2.hashCode())

        // Different QTYPE (A vs AAAA)
        val q3 = buildDnsQueryWire("test.local", txId = 0xAAAA, qType = 28)
        val k3 = DohResolver.WireQuestionKey.fromQuery(q3)
        assertNotNull(k3)
        assertFalse("Different QTYPE must not produce equal question key", k1 == k3)

        // HashMap storage stress with 1,000 keys
        val map = ConcurrentHashMap<DohResolver.WireQuestionKey, String>()
        for (i in 0 until 1000) {
            val q = buildDnsQueryWire("sub-$i.example.com", txId = i)
            val k = DohResolver.WireQuestionKey.fromQuery(q)!!
            map[k] = "ip-$i"
        }
        assertEquals(1000, map.size)
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
