package com.sourzap.app.service.core

object TlsParser {

    data class SniResult(
        val hostname: String?,
        val sniExtensionOffset: Int,
        val sniHostOffset: Int,
        val isClientHello: Boolean
    )

    /**
     * Checks if the buffer starts with a TLS ClientHello and extracts the SNI hostname and byte offsets.
     */
    fun parseClientHello(buffer: ByteArray, length: Int): SniResult {
        if (length < 5) return SniResult(null, -1, -1, false)

        // TLS Record Header: ContentType (0x16 = Handshake), Version Major (0x03), Version Minor (0x01..0x03)
        if (buffer[0] != 0x16.toByte() || buffer[1] != 0x03.toByte()) {
            return SniResult(null, -1, -1, false)
        }

        val recordLength = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)
        if (length < 5 + 4) return SniResult(null, -1, -1, true)

        var pos = 5
        // Handshake type 0x01 = ClientHello
        if (buffer[pos] != 0x01.toByte()) {
            return SniResult(null, -1, -1, false)
        }

        pos += 4 // Skip Handshake Type (1 byte) + Length (3 bytes)
        pos += 2 // Skip Client Version (2 bytes)
        pos += 32 // Skip Random (32 bytes)

        if (pos >= length) return SniResult(null, -1, -1, true)

        // Session ID
        val sessionIdLen = buffer[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen
        if (pos + 2 > length) return SniResult(null, -1, -1, true)

        // Cipher Suites
        val cipherSuitesLen = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
        pos += 2 + cipherSuitesLen
        if (pos + 1 > length) return SniResult(null, -1, -1, true)

        // Compression Methods
        val compressionLen = buffer[pos].toInt() and 0xFF
        pos += 1 + compressionLen
        if (pos + 2 > length) return SniResult(null, -1, -1, true)

        // Extensions Length
        val extensionsLen = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
        pos += 2
        val extensionsEnd = (pos + extensionsLen).coerceAtMost(length)

        // Loop through extensions
        while (pos + 4 <= extensionsEnd) {
            val extType = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
            val extLen = ((buffer[pos + 2].toInt() and 0xFF) shl 8) or (buffer[pos + 3].toInt() and 0xFF)
            val extOffset = pos
            pos += 4

            // Extension 0x0000 = server_name (SNI)
            if (extType == 0) {
                if (pos + 2 <= extensionsEnd) {
                    val serverNameListLen = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
                    var namePos = pos + 2
                    val nameListEnd = (namePos + serverNameListLen).coerceAtMost(extensionsEnd)

                    while (namePos + 3 <= nameListEnd) {
                        val nameType = buffer[namePos].toInt() and 0xFF
                        val nameLen = ((buffer[namePos + 1].toInt() and 0xFF) shl 8) or (buffer[namePos + 2].toInt() and 0xFF)
                        val hostOffset = namePos + 3

                        if (nameType == 0 && hostOffset + nameLen <= length) { // host_name
                            val hostname = String(buffer, hostOffset, nameLen, Charsets.US_ASCII)
                            return SniResult(
                                hostname = hostname,
                                sniExtensionOffset = extOffset,
                                sniHostOffset = hostOffset,
                                isClientHello = true
                            )
                        }
                        namePos += 3 + nameLen
                    }
                }
            }
            pos += extLen
        }

        return SniResult(null, -1, -1, true)
    }

    /**
     * Generates a realistic fake TLS ClientHello packet targeting a benign domain (e.g. google.com or cloudflare.com)
     */
    fun createFakeClientHello(fakeSni: String): ByteArray {
        val sniBytes = fakeSni.toByteArray(Charsets.US_ASCII)
        val sniListLen = sniBytes.size + 3
        val sniExtLen = sniListLen + 2
        val totalExtLen = sniExtLen + 4

        val clientHelloLen = 2 + 32 + 1 + 2 + 4 + 1 + 1 + 2 + totalExtLen
        val handshakeLen = 4 + clientHelloLen
        val recordLen = handshakeLen

        val packet = ByteArray(5 + recordLen)
        var p = 0

        // TLS Record Header
        packet[p++] = 0x16.toByte() // Handshake
        packet[p++] = 0x03.toByte() // TLS 1.0
        packet[p++] = 0x01.toByte()
        packet[p++] = ((recordLen shr 8) and 0xFF).toByte()
        packet[p++] = (recordLen and 0xFF).toByte()

        // Handshake Header
        packet[p++] = 0x01.toByte() // ClientHello
        packet[p++] = 0x00.toByte()
        packet[p++] = ((clientHelloLen shr 8) and 0xFF).toByte()
        packet[p++] = (clientHelloLen and 0xFF).toByte()

        // Client Version TLS 1.2
        packet[p++] = 0x03.toByte()
        packet[p++] = 0x03.toByte()

        // Random 32 bytes
        val random = java.security.SecureRandom()
        val randBytes = ByteArray(32)
        random.nextBytes(randBytes)
        System.arraycopy(randBytes, 0, packet, p, 32)
        p += 32

        // Session ID (0)
        packet[p++] = 0x00.toByte()

        // Cipher Suites (2 suites: TLS_AES_128_GCM_SHA256, TLS_CHACHA20_POLY1305_SHA256)
        packet[p++] = 0x00.toByte()
        packet[p++] = 0x04.toByte()
        packet[p++] = 0x13.toByte()
        packet[p++] = 0x01.toByte()
        packet[p++] = 0x13.toByte()
        packet[p++] = 0x03.toByte()

        // Compression Methods (1: null)
        packet[p++] = 0x01.toByte()
        packet[p++] = 0x00.toByte()

        // Extensions Length
        packet[p++] = ((totalExtLen shr 8) and 0xFF).toByte()
        packet[p++] = (totalExtLen and 0xFF).toByte()

        // Server Name Extension
        packet[p++] = 0x00.toByte()
        packet[p++] = 0x00.toByte()
        packet[p++] = ((sniExtLen shr 8) and 0xFF).toByte()
        packet[p++] = (sniExtLen and 0xFF).toByte()

        // SNI List Length
        packet[p++] = ((sniListLen shr 8) and 0xFF).toByte()
        packet[p++] = (sniListLen and 0xFF).toByte()

        // SNI Type (host_name = 0)
        packet[p++] = 0x00.toByte()
        packet[p++] = ((sniBytes.size shr 8) and 0xFF).toByte()
        packet[p++] = (sniBytes.size and 0xFF).toByte()

        // SNI Hostname bytes
        System.arraycopy(sniBytes, 0, packet, p, sniBytes.size)

        return packet
    }
}