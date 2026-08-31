# Investigation Report: BitTorrent & P2P DPI Evasion Resilience (Requirement R2)

## 1. Observation

### 1.1 BitTorrent Wire Protocol Detection & Evasion in `DpiEngine.kt`
- **File**: `app/src/main/java/com/sourzap/app/service/core/DpiEngine.kt`, lines 26–35
- **Code**:
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
- **Direct Observation**: `DpiEngine` detects BitTorrent wire protocol (`\x13BitTorrent protocol`), but performs **raw passthrough** (`BITTORRENT_PASSTHROUGH`) without any desynchronization or segment splitting. When connecting to unencrypted BitTorrent swarm peers on ISP networks with active DPI (e.g., Sandvine, Huawei, Procera, Russian TSPU), the middlebox detects the 20-byte string `\x13BitTorrent protocol` in the initial TCP packet and drops or injects TCP RSTs.

### 1.2 Fragmented Handshake State Machine in `TunTcpRelay.kt`
- **File**: `app/src/main/java/com/sourzap/app/service/core/TunTcpRelay.kt`, lines 319–347
- **Code**:
  ```kotlin
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
      ...
  } else {
      upstreamOut.write(payload)
      upstreamOut.flush()
  }
  ```
- **Direct Observation**: `session.isHandshakeDesynced` is unconditionally set to `true` on the very first incoming data segment, regardless of whether `payload` contains a complete handshake or just a tiny initial chunk (e.g. 1–5 bytes). If a BitTorrent client or TLS client sends a fragmented handshake across multiple TCP packets:
  1. Chunk 1 (`\x13` or `\x16\x03`) arrives with `length < 20`: `DpiEngine` fails protocol matching and falls through to `PASSTHROUGH`.
  2. Chunk 2 (`BitTorrent protocol...` or the rest of the `ClientHello`) arrives: `isHandshakeDesynced` is already `true`, so Chunk 2 is sent directly to `upstreamOut.write(payload)` without desynchronization.

### 1.3 HTTP Tracker Request Manipulation & Binary Safety in `HttpParser.kt` and `LocalDpiProxyServer.kt`
- **File**: `app/src/main/java/com/sourzap/app/service/core/HttpParser.kt`, lines 40–44
- **Code**:
  ```kotlin
  fun desyncHttpPayload(buffer: ByteArray, length: Int): ByteArray {
      val text = String(buffer, 0, length, Charsets.US_ASCII)
      val modified = text.replace(Regex("(?i)\r\nHost: "), "\r\nhOst:  ")
      return modified.toByteArray(Charsets.US_ASCII)
  }
  ```
- **File**: `app/src/main/java/com/sourzap/app/service/core/DpiEngine.kt`, lines 177–182
- **Code**:
  ```kotlin
  val desynced = HttpParser.desyncHttpPayload(payload, length)
  val splitPos = (desynced.size / 2).coerceIn(1, desynced.size - 1)
  ```
- **File**: `app/src/main/java/com/sourzap/app/service/core/LocalDpiProxyServer.kt`, lines 258–268
- **Code**:
  ```kotlin
  val uriPath = try {
      val uri = java.net.URI(fullUrl)
      val path = uri.rawPath.ifEmpty { "/" }
      val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
      "$path$query"
  } catch (_: Exception) {
      val afterSlash = fullUrl.substringAfter("://").substringAfter("/", "/")
      if (afterSlash.startsWith("/")) afterSlash else "/$afterSlash"
  }
  ```
- **Direct Observation**:
  1. In `HttpParser.desyncHttpPayload`, the entire buffer is converted to a `US_ASCII` String. If an HTTP request carries binary body bytes (e.g. POST tracker requests, compact peer uploads, or non-ASCII characters in `info_hash`), any byte >= 0x80 is corrupted to `?` (0x3F).
  2. In `DpiEngine.applyHttpDesync`, if `desynced.size <= 1`, `coerceIn(1, desynced.size - 1)` evaluates to `coerceIn(1, 0)` which throws `IllegalArgumentException: Cannot coerce value to an empty range`.
  3. In `LocalDpiProxyServer.kt`, BitTorrent tracker URLs often contain unescaped raw bytes in `info_hash=%xx` query parameters which cause `java.net.URI(fullUrl)` to throw `URISyntaxException`. When falling back, if `fullUrl` lacks a trailing slash (e.g., `http://tracker.com:8080`), `substringAfter("/", "/")` evaluates to `"/"`, resulting in `"//"`.

### 1.4 UDP Relay NAT Table Sizing and DHT Burst Dynamics in `TunUdpRelay.kt`
- **File**: `app/src/main/java/com/sourzap/app/service/core/TunUdpRelay.kt`, lines 39–41, 98–109
- **Code**:
  ```kotlin
  private const val MAX_NAT_ENTRIES = 4096
  private const val NAT_IDLE_TIMEOUT_MS = 60_000L

  val mapping = ClientMapping(srcIp, srcPort, System.currentTimeMillis())
  val natKeyExact = "${dstIp.hostAddress}:$dstPort#$socketIndex"
  val natKeyHost = "${dstIp.hostAddress}#$socketIndex"

  if (natTable.size >= MAX_NAT_ENTRIES) {
      pruneOldestNatEntries()
  }

  natTable[natKeyExact] = mapping
  natTable[natKeyHost] = mapping
  ```
- **Direct Observation**:
  1. `natKeyHost` overwrites the mapping for an IP across different destination ports on the same socket index, creating misrouted responses if multiple services or peers are contacted on the same remote host.
  2. BitTorrent DHT (BEP 5) bootstrap/lookup storms regularly query 1,000–5,000 distinct node IPs within seconds. The 4,096 entry limit and batch eviction (`pruneOldestNatEntries` removing 512 entries) causes rapid cache churn and drops valid DHT KRPC replies.
  3. In `runReceiverLoop`, when building UDP reply packets (`PacketParser.buildUdpIpPacket`), the packet header sets DF=1 (`Don't Fragment`), and if `payloadLen + 28 > 1500`, large datagrams (e.g. large DHT nodes lists or DNS responses) exceed the TUN interface MTU.

### 1.5 Safety Bounds and Zero-Exception Guarantees in `PacketParser.kt`
- **File**: `app/src/main/java/com/sourzap/app/service/core/PacketParser.kt`
- **Direct Observation**:
  1. `parseTcpHeader(buffer, tcpOffset, totalLength)`: Does not guard against `tcpOffset < 0` or `buffer.size < totalLength`, potentially throwing `ArrayIndexOutOfBoundsException` on negative offsets or mismatched buffer capacities.
  2. `parseUdpHeader(buffer, udpOffset, totalLength)`: Does not guard against `udpOffset < 0` or `buffer.size < totalLength`.
  3. `computeIpChecksum`, `computeTcpChecksum`, `computeUdpChecksum`, `computeIcmpv6Checksum`: Lack bounds validation for `offset + length > packet.size` or truncated pseudo-header IP arrays (`srcIp.size < 4` or `dstIp.size < 4`).
  4. `buildTcpPacket` / `buildUdpIpPacket`: `System.arraycopy(payload, payloadOffset, packet, ...)` throws `IndexOutOfBoundsException` if `payloadOffset < 0` or `payloadOffset + payloadLen > payload.size`.

---

## 2. Logic Chain

1. **Premise 1: ISP DPI BitTorrent Detection Mechanism**: ISPs use stateful and stateless DPI hardware (e.g. Sandvine, Cisco DPI, Procera, Russian TSPU) configured with regular expressions and signature matchers. For BitTorrent, the primary signature is the 20-byte string `\x13BitTorrent protocol` at offset 0 of the first TCP data packet on any port.
2. **Inference 1**: Because `DpiEngine.desyncAndSend` currently outputs the entire BitTorrent handshake without modification (`BITTORRENT_PASSTHROUGH`), any BitTorrent connection where MSE (Message Stream Encryption) is disabled or during initial peer discovery is vulnerable to immediate ISP RST injection or traffic throttling.
3. **Premise 2: Stream Segmentation & TCP Reassembly**: TCP is an ordered stream abstraction (RFC 793). When a client sends a 68-byte handshake as two distinct TCP segments (e.g. Segment 1 = `[0x13]`, Segment 2 = `["BitTorrent protocol...", reserved, info_hash, peer_id]`), middlebox DPI engines scanning single packet windows fail to match `\x13BitTorrent protocol`. Meanwhile, the destination BitTorrent peer reassembles the segments seamlessly.
4. **Premise 3: Handshake Fragmentation Vulnerability**: In both `TunTcpRelay` and `LocalDpiProxyServer`, DPI desynchronization is applied only to the first segment of a connection using `session.isHandshakeDesynced.getAndSet(true)`.
5. **Inference 2**: When handshakes arrive fragmented from the local client application across multiple packets, Chunk 1 fails the length threshold in `DpiEngine`, is sent as passthrough, and sets `isHandshakeDesynced = true`. Chunk 2 is then sent un-desynchronized, completely negating DPI bypass.
6. **Premise 4: P2P Swarm Scale and DHT Burst Dynamics**: A BitTorrent client running BEP 5 DHT and BEP 15 UDP trackers communicates with thousands of nodes concurrently using short-lived UDP datagrams.
7. **Inference 3**: In `TunUdpRelay`, the NAT table mapping remote nodes back to local client sockets must support high concurrency, avoid remote host key collisions across ports, and maintain sufficient capacity (>= 8,192 to 16,384 entries) with fast LRU eviction so valid DHT search responses are not discarded.
8. **Premise 5: Zero-Exception Invariant for Rootless VPN Relay**: In an Android `VpnService` packet loop running on dedicated I/O coroutines, any uncaught runtime exception (`IllegalArgumentException`, `IndexOutOfBoundsException`, `NullPointerException`) drops packets or tears down the connection loop. All parsers and synthesizers must return `null` or empty arrays on all edge cases.

---

## 3. Caveats

1. **Encrypted BitTorrent Peers (BEP 8 / MSE / PE)**: Many modern BitTorrent clients enable header encryption (Diffie-Hellman P=768 key exchange) by default. For encrypted connections, the initial payload is cryptographically random and does not contain `\x13BitTorrent protocol`. `DpiEngine` will correctly treat this as `PASSTHROUGH`. However, BitTorrent desynchronization remains essential for unencrypted peers, tracker announcements, and fallback connections.
2. **uTP vs TCP Swarms**: Micro Transport Protocol (uTP / BEP 29) encapsulates BitTorrent over UDP. uTP packets in SourZap are routed through `TunUdpRelay`. uTP header evasion is distinct from TCP wire protocol evasion and relies on UDP NAT routing fidelity.
3. **ISP IPv6 DPI**: When IPv6 is enabled on mobile carrier networks (5G/LTE), some ISPs block P2P/DPI evasion over IPv6. SourZap's RFC 4443 ICMPv6 unreachable synthesis forces RFC 6555 Happy Eyeballs to fall back to IPv4 in 0ms, which is the correct and optimal behavior.

---

## 4. Conclusion & Recommendations

To fulfill Requirement R2 with maximum resilience, the following concrete enhancements should be implemented:

### Component 1: `BitTorrentParser` & BitTorrent Handshake DPI Evasion in `DpiEngine`
- **Add a dedicated `BitTorrentParser`**:
  - `parseHandshake(payload, length)`: Detects complete (68-byte) and partial (1..67 byte) BitTorrent handshakes (`\x13BitTorrent protocol`).
  - `parseExtensionMessage(payload, length)`: Identifies BEP 10 extension handshake (`\x14\x00d1:m...`).
  - `parseUtpHeader(payload, length)`: Detects BEP 29 uTP headers.
- **Implement BitTorrent Desynchronization in `DpiEngine.desyncAndSend`**:
  - When a BitTorrent handshake is detected, split the payload into two segments at offset 1 (`[0x13]` | `[BitTorrent protocol...]`) or offset 2 (`[0x13, 'B']` | `['i', 't', ...]`).
  - Flush Segment 1 immediately with `TCP_NODELAY`, followed by Segment 2.
  - Tag connection log with `BT_SPLIT(1)` or `BT_SPLIT(2)`.

### Component 2: Fragmented Handshake Buffering & Multi-Chunk Desynchronization in `TunTcpRelay`
- In `TunTcpRelay`:
  - If the initial payload is small and looks like the start of a handshake (e.g. `0x13` or `0x16` or `GET `) but is incomplete, allow buffering of the initial segment up to a short threshold (or apply split on whatever prefix is available) before locking `isHandshakeDesynced`.

### Component 3: Binary-Safe HTTP Host Modification in `HttpParser`
- Refactor `HttpParser.desyncHttpPayload` to work directly on byte arrays or only parse up to the `\r\n\r\n` header delimiter:
  - Search for `\r\nHost: ` and `\nHost: ` case-insensitively using byte scanning.
  - Replace `Host: ` with `hOst:  ` in-place on header bytes without decoding or modifying any binary payload bytes after the header boundary.
  - Fix `applyHttpDesync` split offset calculation: `val splitPos = if (desynced.size > 1) (desynced.size / 2).coerceIn(1, desynced.size - 1) else 1`.

### Component 4: High-Throughput UDP NAT & DHT Burst Resilience in `TunUdpRelay`
- Expand NAT table capacity from 4,096 to 8,192 (or 16,384).
- Key NAT table strictly by `RemoteIP:RemotePort#SocketIndex` for primary lookup, and use a secondary index for host-only fallback without overwriting distinct port mappings.
- Ensure `PacketParser.buildUdpIpPacket` clamps datagram payloads to maximum segment limits.

### Component 5: Zero-Exception Hardening in `PacketParser.kt`
- Add comprehensive bounds checking to all parser and checksum functions:
  - `parseTcpHeader`: Check `tcpOffset in 0 until totalLength`, `totalLength <= buffer.size`, `dataOffset >= 20`.
  - `parseUdpHeader`: Check `udpOffset in 0 until totalLength`, `totalLength <= buffer.size`.
  - `computeIpChecksum` / `computeTcpChecksum` / `computeUdpChecksum` / `computeIcmpv6Checksum`: Check array boundaries and return `0.toShort()` on invalid ranges.
  - `buildTcpPacket` / `buildUdpIpPacket`: Validate `payloadOffset >= 0 && payloadLen >= 0 && payloadOffset + payloadLen <= payload.size`.

---

## 5. Verification Method

### 5.1 Automated Test Execution
Run the complete unit test suite to verify no regressions:
```bash
./gradlew.bat testDebugUnitTest
```

### 5.2 Specific Test Cases to Expand in `DpiEngineTest.kt` & `PacketParserTest.kt`:
1. **BitTorrent Handshake Desynchronization Test**:
   - Verify that passing a standard 68-byte BitTorrent handshake (`\x13BitTorrent protocol...`) through `DpiEngine.desyncAndSend` splits the handshake at offset 1 or 2, sending 2 discrete segments that reassemble to the exact original 68 bytes.
2. **Fragmented Handshake Test**:
   - Verify that 1-byte, 5-byte, 20-byte, and 68-byte chunks are handled safely without exceptions or dropping connections.
3. **HTTP Binary Payload Integrity Test**:
   - Construct an HTTP POST request containing binary bytes (`0x80..0xFF`) in the body and a `Host:` header; verify `HttpParser.desyncHttpPayload` alters only the `Host:` header and leaves all body bytes byte-for-byte identical.
4. **UDP NAT DHT Burst Test**:
   - Simulate 10,000 rapid concurrent DHT query/response packets across 20 coroutine workers; verify zero dropped mappings and 100% routing fidelity.
5. **PacketParser Adversarial Fuzzing & Boundary Offset Test**:
   - Feed negative offsets, `totalLength > buffer.size`, corrupt checksum headers, and truncated payloads into `parseTcpHeader`, `parseUdpHeader`, and all `compute*Checksum` methods; verify zero unhandled exceptions.
