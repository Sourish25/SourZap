package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.NetworkIpHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for NetworkIpHelper.
 * Verifies that loopback, bogon, private LAN (RFC 1918), and cached public IPs
 * are strictly identified as self/local to eliminate ghost peer self-connection loops.
 */
class NetworkIpHelperTest {

    @Test
    fun testLoopbackAndBogonDetection() {
        assertTrue("0.0.0.0 must be self/local", NetworkIpHelper.isSelfOrLocal("0.0.0.0"))
        assertTrue("127.0.0.1 must be self/local", NetworkIpHelper.isSelfOrLocal("127.0.0.1"))
        assertTrue("127.1.2.3 must be self/local", NetworkIpHelper.isSelfOrLocal("127.1.2.3"))
        assertTrue("169.254.10.20 APIPA must be self/local", NetworkIpHelper.isSelfOrLocal("169.254.10.20"))
        assertTrue("Empty string must be treated as self/local", NetworkIpHelper.isSelfOrLocal(""))
        assertTrue("Null string must be treated as self/local", NetworkIpHelper.isSelfOrLocal(null))
    }

    @Test
    fun testPrivateRfc1918Detection() {
        // 10.0.0.0/8
        assertTrue("10.0.0.1 must be private", NetworkIpHelper.isSelfOrLocal("10.0.0.1"))
        assertTrue("10.10.10.1 must be private", NetworkIpHelper.isSelfOrLocal("10.10.10.1"))
        assertTrue("10.255.255.255 must be private", NetworkIpHelper.isSelfOrLocal("10.255.255.255"))

        // 192.168.0.0/16
        assertTrue("192.168.0.1 must be private", NetworkIpHelper.isSelfOrLocal("192.168.0.1"))
        assertTrue("192.168.0.103 must be private", NetworkIpHelper.isSelfOrLocal("192.168.0.103"))
        assertTrue("192.168.1.1 must be private", NetworkIpHelper.isSelfOrLocal("192.168.1.1"))

        // 172.16.0.0/12 (172.16.x.x - 172.31.x.x)
        assertTrue("172.16.0.1 must be private", NetworkIpHelper.isSelfOrLocal("172.16.0.1"))
        assertTrue("172.24.100.5 must be private", NetworkIpHelper.isSelfOrLocal("172.24.100.5"))
        assertTrue("172.31.255.254 must be private", NetworkIpHelper.isSelfOrLocal("172.31.255.254"))

        // Non-private 172.x.x.x
        assertFalse("172.15.1.1 is public routable", NetworkIpHelper.isSelfOrLocal("172.15.1.1"))
        assertFalse("172.32.0.1 is public routable", NetworkIpHelper.isSelfOrLocal("172.32.0.1"))
    }

    @Test
    fun testPublicWanIpMatching() {
        // Initially an arbitrary public IP is not self
        val testPublicIp = "117.250.120.195"
        val otherPublicIp = "83.254.78.74"

        assertFalse("Public peer 83.254.78.74 must not be flagged before setting self IP", NetworkIpHelper.isSelfOrLocal(otherPublicIp))

        // Set device WAN IP (learned from tracker or DoH)
        NetworkIpHelper.setPublicIp(testPublicIp)

        // Now testPublicIp MUST be flagged as self
        assertTrue("Device WAN IP must be strictly flagged as self", NetworkIpHelper.isSelfOrLocal(testPublicIp))
        assertFalse("Different public IP must remain valid peer", NetworkIpHelper.isSelfOrLocal(otherPublicIp))
    }
}
