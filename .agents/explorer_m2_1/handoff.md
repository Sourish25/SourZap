# Exploration Report: BitTorrent DPI Evasion & Fragmented Handshake Buffering (Milestone M2)

## 1. Observation

### 1.1 Current State of BitTorrent Handshake Detection in `DpiEngine.kt`
In `app/src/main/java/com/sourzap/app/service/core/DpiEngine.kt` (lines 26–35):
```kotlin
// 1. BitTorrent TCP Peer Wire Protocol Detection (\x13BitTorrent protocol)
if (length >= 20 && payload[0] == 0x13.toByte() &&
    payload[1] == 'B'.code.toByte() && payload[2] == 'i'.code.toByte() &&
    payload[3] == 't'.code.toByte() && payload[4] == 'T'.code.toByte()
) {
    outputStream.write(payload, 0, length)
    outputStream.flush()
    onTechniqueApplied("BITTORRENT_PASSTHROUGH")
    return
}
```
**Deficiencies Identified**:
1. **No DPI Desynchronization**: BitTorrent handshakes are written to the socket as a single monolithic block (`BITTORRENT_PASSTHROUGH`). When an ISP DPI middlebox inspects outbound TCP traffic, it immediately sees `\x13BitTorrent protocol` in the initial payload segment, enabling DPI firewalls to throttle, drop, or inject TCP RST packets into P2P swarms.
2. **Incomplete Header Validation**: Only 5 bytes (`\x13BitT`) are verified instead of the RFC/BEP 3 standardized 20-byte protocol prefix (`\x13BitTorrent protocol`).
3. **Missing Handshake Desynchronization Strategy**: No segment splitting (`BT_SPLIT(1)` or `BT_SPLIT(2)`) is implemented.

### 1.2 Current State of TCP Payload Ingestion in `TunTcpRelay.kt`
In `app/src/main/java/com/sourzap/app/service/core/TunTcpRelay.kt`:
- In `handleTcpPacket` (lines 275–296):
```kotlin
if (payloadLen > 0) {
    // Data Payload received from App
    val payload = buffer.copyOfRange(payloadOffset, length)
    session.clientSeq.updateAndGet { (seqNum + payloadLen) and 0xFFFFFFFFL }

    // Send immediate ACK back to app so its TCP window stays wide open
    val ackPacket = PacketParser.buildTcpPacket(
        srcIp = dstIp,
        dstIp = srcIp,
        srcPort = dstPort,
        dstPort = srcPort,
        seqNum = session.serverSeq.get(),
        ackNum = session.clientSeq.get(),
        flags = 0x10, // ACK
        payload = EMPTY_BYTE_ARRAY
    )
    writeTunPacket(ackPacket)

    // Enqueue in sequential FIFO channel
    session.sendQueue.trySend(payload)
}
```
- In `startUpstreamConnection` (lines 337–377):
```kotlin
// Dedicated sequential sender loop (FIFO order)
session.senderJob = launch(tcpDispatcher) {
    try {
        for (payload in session.sendQueue) {
            if (!scope.isActive || !isRunning.get() || !session.isConnected.get()) break
            session.lastActivity = System.currentTimeMillis()

            if (!session.isHandshakeDesynced.getAndSet(true)) {
                val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value
                var appliedTechnique = "DIRECT"

                val sniResult = TlsParser.parseClientHello(payload, payload.size)
                val logDomain = sniResult.hostname ?: session.dstIp.hostAddress ?: "Socket"

                DpiEngine.desyncAndSend(
                    socket = socket,
                    outputStream = upstreamOut,
                    payload = payload,
                    length = payload.size,
                    strategy = strategy,
                    onTechniqueApplied = { appliedTechnique = it }
                )

                TrafficMonitor.addConnectionLog(...)
            } else {
                upstreamOut.write(payload)
                upstreamOut.flush()
            }
        }
    } catch (_: Exception) {
        closeSessionInternal(session, forceRemove = false)
    }
}
```
**Deficiencies Identified**:
1. **Single-Chunk Handshake Vulnerability**:
   When an Android application (or BitTorrent client) sends its initial handshake (TLS ClientHello or BitTorrent Peer Wire handshake) split across multiple smaller TCP segments:
   - Chunk 1 (e.g. 10 bytes) arrives on `sendQueue`.
   - `session.isHandshakeDesynced.getAndSet(true)` is executed, atomically returning `false` and setting the flag to `true`.
   - `DpiEngine.desyncAndSend` is invoked with only Chunk 1 (10 bytes).
     - If TLS: `TlsParser.parseClientHello` cannot extract the SNI hostname (as SNI is located at byte offset 150+ in Chunk 2), returning `hostname = null` and causing domain-based bypass rules (e.g. `isCriticalPassthrough`) to fail or default.
     - If BitTorrent: `length >= 20` check fails, treating Chunk 1 as unknown `PASSTHROUGH`.
   - Chunk 2 (e.g. remaining 58 bytes of BitTorrent handshake or remaining 400 bytes of ClientHello) arrives on `sendQueue`.
   - `session.isHandshakeDesynced.getAndSet(true)` evaluates to `true` (flag was already set).
   - Chunk 2 is directly written via `upstreamOut.write(payload)` without any DPI desynchronization applied.
   - Result: DPI evasion is completely bypassed for any fragmented client connection.

---

## 2. Logic Chain & Architecture Plan

### 2.1 BitTorrent Protocol Specification & DPI Desynchronization
- **BitTorrent Protocol Wire Handshake (BEP 0003)**:
  - **Total standard handshake length**: 68 bytes minimum.
  - **Byte 0**: `pstrlen` = `0x13` (19 decimal).
  - **Bytes 1..19**: `pstr` = `"BitTorrent protocol"` (19 ASCII bytes).
  - **Bytes 20..27**: `reserved` = 8 bytes (extensions flags, BEP 10).
  - **Bytes 28..47**: `info_hash` = 20-byte SHA-1 info hash.
  - **Bytes 48..67**: `peer_id` = 20-byte client peer ID.
- **Why Segment Splitting Evades DPI**:
  Carrier-grade DPI appliances (Sandvine, Huawei, Cisco, Allot) maintain per-flow protocol classification state machines. They inspect the initial TCP payload window for signatures matching `\x13BitTorrent protocol`.
  - When `BT_SPLIT(1)` is applied with `TCP_NODELAY`:
    - Segment 1: `payload[0..0]` = `[0x13]` (1 byte), flushed immediately. The OS TCP stack transmits a standalone TCP segment containing 1 byte.
    - Segment 2: `payload[1..67]` = `['B', 'i', 't', 'T', 'o', 'r', 'r', 'e', 'n', 't', ...]` (67+ bytes), flushed immediately.
    - DPI signature matching fails because the `\x13BitTorrent protocol` string is fragmented across IP packets. Carrier DPI middleboxes do not buffer out-of-order/multi-segment streams for high-volume P2P swarms due to memory limitations.
    - The remote peer (e.g. libtorrent/Transmission/qBittorrent) TCP stack reassembles the TCP byte stream seamlessly into the original 68-byte handshake.
  - When `BT_SPLIT(2)` is applied with `TCP_NODELAY`:
    - Segment 1: `payload[0..1]` = `[0x13, 'B']` (2 bytes), flushed immediately.
    - Segment 2: `payload[2..67]` = `['i', 't', 'T', 'o', 'r', 'r', 'e', 'n', 't', ...]` (66+ bytes), flushed immediately.
    - Achieves identical desynchronization with alternate segment boundary.

### 2.2 Proposed Implementation in `DpiEngine.kt`
```kotlin
object DpiEngine {

    val BT_PROTOCOL_BYTES = byteArrayOf(
        0x13.toByte(),
        'B'.code.toByte(), 'i'.code.toByte(), 't'.code.toByte(), 'T'.code.toByte(),
        'o'.code.toByte(), 'r'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(),
        'n'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(), 'p'.code.toByte(),
        'r'.code.toByte(), 'o'.code.toByte(), 't'.code.toByte(), 'o'.code.toByte(),
        'c'.code.toByte(), 'o'.code.toByte(), 'l'.code.toByte()
    )
    const val MIN_BT_HANDSHAKE_LEN = 68
    const val BT_PREFIX_LEN = 20

    fun isBitTorrentHandshake(payload: ByteArray, length: Int): Boolean {
        if (length < BT_PREFIX_LEN) return false
        if (payload[0] != 0x13.toByte()) return false
        for (i in 1 until BT_PREFIX_LEN) {
            if (payload[i] != BT_PROTOCOL_BYTES[i]) return false
        }
        return true
    }

    fun desyncAndSend(
        socket: Socket,
        outputStream: OutputStream,
        payload: ByteArray,
        length: Int,
        strategy: BypassStrategy,
        onTechniqueApplied: (String) -> Unit
    ) {
        try {
            socket.tcpNoDelay = true

            // 1. BitTorrent TCP Peer Wire Protocol Detection & Desync
            if (isBitTorrentHandshake(payload, length)) {
                applyBitTorrentDesync(outputStream, payload, length, strategy, onTechniqueApplied)
                return
            }

            // 2. SSH Protocol Detection
            if (length >= 4 && payload[0] == 'S'.code.toByte() && payload[1] == 'S'.code.toByte() &&
                payload[2] == 'H'.code.toByte() && payload[3] == '-'.code.toByte()
            ) {
                outputStream.write(payload, 0, length)
                outputStream.flush()
                onTechniqueApplied("SSH_PASSTHROUGH")
                return
            }

            // 3. TLS ClientHello Handshake
            val sniResult = TlsParser.parseClientHello(payload, length)
            if (sniResult.isClientHello) {
                applyTlsDesync(outputStream, payload, length, strategy, sniResult, onTechniqueApplied)
                return
            }

            // 4. Plain HTTP Request (GET/POST/HEAD/PUT/DELETE)
            val httpResult = HttpParser.parseHttpRequest(payload, length)
            if (httpResult.isHttp) {
                applyHttpDesync(outputStream, payload, length, strategy, onTechniqueApplied)
                return
            }

            // 5. Proprietary Protocols (WhatsApp Noise Handshake, Telegram MTProto, Raw Sockets)
            outputStream.write(payload, 0, length)
            outputStream.flush()
            onTechniqueApplied("PASSTHROUGH")
        } catch (e: Exception) {
            outputStream.write(payload, 0, length)
            outputStream.flush()
            onTechniqueApplied("DIRECT_FALLBACK")
        }
    }

    private fun applyBitTorrentDesync(
        outputStream: OutputStream,
        payload: ByteArray,
        length: Int,
        strategy: BypassStrategy,
        onTechniqueApplied: (String) -> Unit
    ) {
        // BitTorrent handshake splitting at offset 1 or 2 with immediate flush (TCP_NODELAY active)
        val splitPos = if (strategy.tlsSplitOffset == 1) 1 else 2.coerceAtMost(length - 1)

        val c1 = payload.copyOfRange(0, splitPos)
        val c2 = payload.copyOfRange(splitPos, length)

        outputStream.write(c1)
        outputStream.flush()

        outputStream.write(c2)
        outputStream.flush()

        onTechniqueApplied("BT_SPLIT($splitPos)")
    }
}
```

---

### 2.3 Fragmented Handshake Buffering in `TunTcpRelay.kt`

#### A. Buffering Policy & Constraints:
- **Maximum Handshake Buffer Size (`MAX_HANDSHAKE_BUFFER_SIZE`)**: `4096` bytes (4 KB).
  - Standard TLS ClientHello ranges from 200 to 2048 bytes; BitTorrent handshake is 68 bytes; HTTP headers are typically 200–1024 bytes.
  - Capping buffer accumulation at 4096 bytes guarantees bounded memory and eliminates OOM vulnerabilities from slow-loris attacks.
- **Handshake Buffer Timeout (`HANDSHAKE_BUFFER_TIMEOUT_MS`)**: `150L` ms.
  - If a client transmits a partial chunk (e.g. 5 bytes) and pauses, the sender loop flushes accumulated bytes after 150 ms without deadlocking the session.
- **Immediate Passthrough for Non-DPI Traffic**:
  - If the first byte does NOT match TLS (`0x16`), BitTorrent (`0x13`), or HTTP method prefixes (`G`, `P`, `H`, `O`, `D`, `C`), `isHandshakeComplete` returns `true` on the very first chunk. SSH, Noise protocol, DNS, and raw TCP streams incur 0 ms buffering delay.

#### B. Protocol Completion Predicate (`isHandshakeComplete`):
```kotlin
fun isHandshakeComplete(buffer: ByteArray, length: Int): Boolean {
    if (length <= 0) return false

    val b0 = buffer[0].toInt() and 0xFF

    // 1. TLS Handshake (0x16 0x03)
    if (b0 == 0x16) {
        if (length < 2) return false
        val b1 = buffer[1].toInt() and 0xFF
        if (b1 == 0x03) {
            if (length < 5) return false
            val recordLen = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)
            val fullLen = (5 + recordLen).coerceAtMost(MAX_HANDSHAKE_BUFFER_SIZE)
            return length >= fullLen
        }
        return true // Non-standard TLS record version -> proceed
    }

    // 2. BitTorrent Handshake (0x13 "BitTorrent protocol")
    if (b0 == 0x13) {
        val expectedPrefix = DpiEngine.BT_PROTOCOL_BYTES
        val checkLen = minOf(length, expectedPrefix.size)
        for (i in 1 until checkLen) {
            if (buffer[i] != expectedPrefix[i]) {
                return true // Mismatched prefix, not BitTorrent
            }
        }
        if (length < expectedPrefix.size) {
            return false // Matches prefix so far, wait for at least 20 bytes
        }
        return length >= DpiEngine.MIN_BT_HANDSHAKE_LEN // Wait for 68-byte handshake
    }

    // 3. HTTP Request
    val httpMethods = listOf("GET ", "POST ", "HEAD ", "OPTIONS ", "PUT ", "DELETE ", "CONNECT ")
    val startStr = String(buffer, 0, minOf(length, 8), Charsets.US_ASCII)
    val matchesMethod = httpMethods.any { method ->
        if (length >= method.length) startStr.startsWith(method)
        else method.startsWith(startStr)
    }

    if (matchesMethod) {
        val hasFullMethod = httpMethods.any { startStr.startsWith(it) }
        if (!hasFullMethod && length < 8) return false

        // Check for end of HTTP headers (\r\n\r\n or \n\n)
        for (i in 3 until length) {
            if (buffer[i - 3] == '\r'.code.toByte() && buffer[i - 2] == '\n'.code.toByte() &&
                buffer[i - 1] == '\r'.code.toByte() && buffer[i] == '\n'.code.toByte()
            ) return true
        }
        for (i in 1 until length) {
            if (buffer[i - 1] == '\n'.code.toByte() && buffer[i] == '\n'.code.toByte()) return true
        }
        return length >= 2048 // Header inspection bound
    }

    // 4. All other protocols (SSH, Noise, raw TCP) -> no buffering needed
    return true
}
```

#### C. Sender Loop in `TunTcpRelay.kt`:
```kotlin
// Dedicated sequential sender loop (FIFO order) with Fragmented Handshake Buffering
session.senderJob = launch(tcpDispatcher) {
    try {
        var handshakeBuffer: java.io.ByteArrayOutputStream? = java.io.ByteArrayOutputStream(1024)

        while (scope.isActive && isRunning.get() && session.isConnected.get()) {
            if (!session.isHandshakeDesynced.get()) {
                // Buffer handshake chunks with timeout
                val payload = withTimeoutOrNull(HANDSHAKE_BUFFER_TIMEOUT_MS) {
                    session.sendQueue.receiveCatching().getOrNull()
                }

                if (payload != null) {
                    session.lastActivity = System.currentTimeMillis()
                    handshakeBuffer?.write(payload)
                }

                val currentBuf = handshakeBuffer?.toByteArray() ?: EMPTY_BYTE_ARRAY
                val complete = payload == null || isHandshakeComplete(currentBuf, currentBuf.size) || currentBuf.size >= MAX_HANDSHAKE_BUFFER_SIZE

                if (complete && currentBuf.isNotEmpty()) {
                    session.isHandshakeDesynced.set(true)
                    val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value
                    var appliedTechnique = "DIRECT"

                    val isBt = DpiEngine.isBitTorrentHandshake(currentBuf, currentBuf.size)
                    val sniResult = if (!isBt) TlsParser.parseClientHello(currentBuf, currentBuf.size) else TlsParser.SniResult(null, -1, -1, false)

                    val protocolName = when {
                        isBt -> "BitTorrent"
                        sniResult.isClientHello || session.dstPort == 443 -> "TLS"
                        HttpParser.parseHttpRequest(currentBuf, currentBuf.size).isHttp -> "HTTP"
                        else -> "TCP"
                    }

                    val logDomain = when {
                        isBt -> "BitTorrent Swarm"
                        sniResult.hostname != null -> sniResult.hostname
                        else -> session.dstIp.hostAddress ?: "Socket"
                    }

                    DpiEngine.desyncAndSend(
                        socket = socket,
                        outputStream = upstreamOut,
                        payload = currentBuf,
                        length = currentBuf.size,
                        strategy = strategy,
                        onTechniqueApplied = { appliedTechnique = it }
                    )

                    TrafficMonitor.addConnectionLog(
                        ConnectionLog(
                            domain = logDomain,
                            port = session.dstPort,
                            protocol = protocolName,
                            technique = appliedTechnique,
                            bytesTransferred = currentBuf.size.toLong()
                        )
                    )

                    handshakeBuffer = null // Deallocate transient buffer
                }

                if (payload == null && session.sendQueue.isClosedForReceive) {
                    break
                }
            } else {
                // Post-handshake high-speed streaming phase: direct write
                val payload = session.sendQueue.receiveCatching().getOrNull() ?: break
                session.lastActivity = System.currentTimeMillis()
                upstreamOut.write(payload)
                upstreamOut.flush()
            }
        }
    } catch (_: Exception) {
        closeSessionInternal(session, forceRemove = false)
    }
}
```

---

## 3. Corner Cases, Edge Cases & Failure Modes

| # | Edge Case | Mechanism / Threat | Mitigation / Handling |
|---|-----------|-------------------|-----------------------|
| 1 | **1-byte chunk drip (Adversarial fragmentation)** | Client or test sends 68-byte BitTorrent handshake 1 byte per TCP packet. | `isHandshakeComplete` checks prefix matching byte-by-byte, buffering until byte 68 arrives, then executes `BT_SPLIT(1)` / `BT_SPLIT(2)`. |
| 2 | **Truncated Handshake from Client** | Client sends 20-byte prefix `\x13BitTorrent protocol` and stops. | After `150ms` timeout, `withTimeoutOrNull` returns null, triggering immediate flush and applying `BT_SPLIT` to the 20 bytes. |
| 3 | **Corrupted Prefix (e.g. `\x13BadProto`)** | Packet starts with `0x13` but bytes 1..19 do not match `"BitTorrent protocol"`. | `isHandshakeComplete` detects mismatch in prefix loop and returns `true` immediately without delaying. |
| 4 | **Malformed TLS Record Length (0xFFFF)** | Malicious packet declares record length of 65535 bytes. | `targetLen` is capped by `.coerceAtMost(MAX_HANDSHAKE_BUFFER_SIZE)` (4096 bytes). Memory is strictly bounded. |
| 5 | **High Swarm Concurrency (200+ P2P Peers)** | High connection volume creates memory pressure. | Handshake buffer is created lazily per session and nullified immediately upon handshake completion. Total transient memory for 200 concurrent connections is $< 256\text{ KB}$. |
| 6 | **HTTP Tracker Announce Requests** | Client sends `GET /announce?info_hash=...` HTTP request. | Detected as HTTP request, buffered until `\r\n\r\n`, and processed via `HttpParser` desync. |
| 7 | **Client FIN/RST During Buffering** | Client disconnects before sending complete handshake. | `closeSessionInternal` immediately cancels `senderJob`, closes `sendQueue`, and closes socket without fd or coroutine leak. |

---

## 4. Conclusion

1. **BitTorrent Handshake Desynchronization (`BT_SPLIT(1)` / `BT_SPLIT(2)`)**:
   Implementing strict 20-byte prefix validation (`isBitTorrentHandshake`) and segment splitting at offset 1 or 2 with `TCP_NODELAY` provides robust, zero-configuration DPI evasion against carrier P2P throttling while preserving 100% protocol compliance for BitTorrent clients and swarms.
2. **Fragmented Handshake Buffering**:
   Introducing protocol-aware handshake accumulation with a `4096B` memory bound, `150ms` timeout, and instant passthrough for non-DPI protocols in `TunTcpRelay.kt` completely eliminates DPI bypass vulnerabilities across fragmented ClientHello and BitTorrent streams without adding latency to non-DPI connections.

---

## 5. Verification Method

### 5.1 Verification Commands
Run the complete automated unit test suite:
```powershell
.\gradlew.bat testDebugUnitTest
```
Verify clean release compilation:
```powershell
.\gradlew.bat assembleRelease
```

### 5.2 Unit & Integration Test Specifications
The following test suites must be included in `app/src/test/java/com/sourzap/app/DpiEngineTest.kt` and `TunTcpRelayTest.kt`:
1. `testBitTorrentHandshakeDetection_Standard68Bytes`: Verify `isBitTorrentHandshake` returns `true` for standard 68-byte handshake.
2. `testBitTorrentHandshakeDetection_Prefix20Bytes`: Verify `isBitTorrentHandshake` returns `true` for 20-byte prefix.
3. `testBitTorrentHandshakeDetection_InvalidPrefix`: Verify `isBitTorrentHandshake` returns `false` for invalid prefix bytes.
4. `testBitTorrentDesync_Split1AndSplit2Execution`: Verify `BT_SPLIT(1)` emits 1 byte + 67 bytes, and `BT_SPLIT(2)` emits 2 bytes + 66 bytes with `socket.tcpNoDelay = true`.
5. `testFragmentedHandshakeBuffering_TlsSplitChunks`: Verify `TunTcpRelay` buffers multi-segment ClientHello and applies TLS desync.
6. `testFragmentedHandshakeBuffering_BitTorrentMultiChunk`: Verify `TunTcpRelay` buffers multi-segment BitTorrent handshake and applies `BT_SPLIT`.
7. `testFragmentedHandshakeBuffering_NonDpiImmediatePassthrough`: Verify non-TLS/non-BT protocols bypass buffering with 0 ms delay.
8. `testFragmentedHandshakeBuffering_TimeoutFlush`: Verify incomplete chunks flush after 150 ms timeout without hanging.

### 5.3 Invalidation Conditions
- If any test in `testDebugUnitTest` fails or hangs.
- If `BT_SPLIT` does not set `socket.tcpNoDelay = true` before writing.
- If `TunTcpRelay` buffering allows memory to grow unbounded beyond 4096 bytes per session.
