# Handoff Report — Milestone M2 (BitTorrent & P2P DPI Evasion Resilience)

**Worker**: `worker_m2`  
**Milestone**: M2 (BitTorrent & P2P DPI Evasion Resilience)  
**Date**: 2026-08-31  

---

## 1. Observation

### 1.1 Codebase Audit & Modifications Implemented

1. **`app/src/main/java/com/sourzap/app/service/core/DpiEngine.kt`**:
   - Added `BT_PROTOCOL_BYTES` (20-byte RFC/BEP 0003 prefix `\x13BitTorrent protocol`), `MIN_BT_HANDSHAKE_LEN = 68`, and `BT_PREFIX_LEN = 20`.
   - Implemented `isBitTorrentHandshake(payload: ByteArray, length: Int): Boolean` for strict 20-byte prefix validation.
   - Implemented `applyBitTorrentDesync` executing `BT_SPLIT(1)` or `BT_SPLIT(2)` segment splitting with `socket.tcpNoDelay = true` and immediate flushing of each chunk.

2. **`app/src/main/java/com/sourzap/app/service/core/TunTcpRelay.kt`**:
   - Implemented `isHandshakeComplete(buffer: ByteArray, length: Int): Boolean` to evaluate protocol completion for TLS records (RFC 5246/8446), BitTorrent handshakes (BEP 0003), and HTTP request headers.
   - Refactored `senderJob` in `startUpstreamConnection` to implement multi-chunk handshake buffering:
     - Buffers initial fragments until `isHandshakeComplete` returns true, or `MAX_HANDSHAKE_BUFFER_SIZE` (4096B) is reached, or `HANDSHAKE_BUFFER_TIMEOUT_MS` (150L ms) expires.
     - Guarantees 0ms immediate passthrough on the first chunk for non-DPI protocols (SSH, WhatsApp Noise protocol, DNS, raw TCP).
     - Seamlessly transitions to direct high-speed streaming post-handshake.

3. **`app/src/main/java/com/sourzap/app/service/core/HttpParser.kt`**:
   - Added `findHeaderBoundary(buffer: ByteArray, length: Int): Pair<Int, Int>?` supporting `\r\n\r\n`, `\n\n`, `\r\n\n`, and `\n\r\n`.
   - Converted all string decoding in `parseHttpRequest` and `desyncHttpPayload` to `Charsets.ISO_8859_1` to ensure lossless 1:1 byte-to-char mapping.
   - Refactored `desyncHttpPayload` and `splitHttpHeader` to isolate header bytes from binary body bytes (`0x80..0xFF`), preserving body byte slices untouched.

4. **`app/src/main/java/com/sourzap/app/service/core/LocalDpiProxyServer.kt`**:
   - Eliminated regex `replaceFirst` in HTTP request rewriting, preventing `PatternSyntaxException` on IPv6 `[` brackets and tracker query strings with metacharacters (`?`, `&`, `+`, `*`, `(`, `)`).
   - Implemented deterministic `normalizeUriPath` supporting raw bytes in tracker URLs and IPv6 bracketed hosts without throwing `URISyntaxException`.
   - Added companion helpers `parseHostAndPort` and `isIpLiteral` supporting bracketed IPv6 (`[2001:db8::1]:8080`), unbracketed IPv6 (`2001:db8::1`, `::1`), and HTTP/1.0 request line host fallbacks.
   - Preserved initial binary body bytes read during initial read cycle.

5. **`app/src/main/java/com/sourzap/app/service/core/PacketParser.kt`**:
   - Implemented zero-exception pre-condition bounds clamping and defensive wrappers across `parseIpHeader`, `parseIpv4Header`, `parseIpv6Header`, `parseTcpHeader`, `parseUdpHeader`.
   - Upgraded checksum engines (`computeIpChecksum`, `computeTcpChecksum`, `computeUdpChecksum`, `computeIcmpv6Checksum`) with dual-stack IPv4 (4B) and IPv6 (16B) pseudo-header support.
   - Added RFC synthesizers: `buildIpHeader`, `buildTcpPacket`, `buildTcpIpPacket`, `buildSynAckPacket`, `buildRstPacket`, `buildUdpIpPacket`, `buildIcmpv4DestinationUnreachablePacket`, `buildIcmpPortUnreachablePacket`, `buildIcmpv6DestinationUnreachablePacket`, `buildIcmpv6AddressUnreachablePacket`, `buildIcmpv6PortUnreachablePacket`, `buildIcmpv6AdminProhibitedPacket`.

6. **Test Suites Expanded**:
   - `app/src/test/java/com/sourzap/app/DpiEngineTest.kt`: Added BitTorrent detection, `BT_SPLIT(1)`/`BT_SPLIT(2)`, HTTP header boundary, binary safety, proxy parsing tests.
   - `app/src/test/java/com/sourzap/app/PacketParserTest.kt`: Added fuzzing, dual-stack checksums, and synthesizer compliance tests.
   - `app/src/test/java/com/sourzap/app/M2EmpiricalChallengeTest.kt`: Added comprehensive end-to-end challenge suite covering all M2 evasion, buffering, and parsing requirements.

---

## 2. Logic Chain

1. **BitTorrent Evasion**:
   Middleboxes identify BitTorrent traffic by looking for `\x13BitTorrent protocol` at the start of a TCP connection. Splitting this handshake across two TCP segments at offset 1 (`\x13` | `BitTorrent protocol...`) or offset 2 (`\x13B` | `itTorrent protocol...`) with `socket.tcpNoDelay = true` breaks the middlebox's continuous stream inspection without disrupting the remote BitTorrent peer's reassembly.

2. **Multi-Chunk Handshake Buffering**:
   When clients fragment initial handshakes across small TCP packets, evaluating DPI evasion on the first fragment fails to extract SNI or match protocol signatures. By buffering until `isHandshakeComplete` returns true (or 4096B max / 150ms timeout), `DpiEngine` receives the complete handshake to apply evasion. Non-DPI protocols bypass buffering instantly (0ms delay).

3. **Binary Safety**:
   `US_ASCII` string conversion replaces bytes with value $\ge 0x80$ with `?` (`0x3F`), corrupting binary BitTorrent tracker requests and POST uploads. Using `Charsets.ISO_8859_1` on the header portion and leaving body bytes as raw byte arrays guarantees byte-exact preservation.

4. **Zero-Exception Hardening**:
   TUN interfaces receive arbitrary, potentially corrupted packets. Pre-clamping all offsets, lengths, and indices against physical array bounds ensures `PacketParser` returns `null` or safe sentinels instead of crashing the VPN loop.

---

## 3. Caveats

- `MAX_HANDSHAKE_BUFFER_SIZE` is set to 4096 bytes, which comfortably covers standard TLS ClientHello, BitTorrent, and HTTP headers. Any atypical protocol sending headers larger than 4096B will flush at 4096B, preventing memory exhaustion.
- `HANDSHAKE_BUFFER_TIMEOUT_MS` (150ms) ensures slow-loris or stalled connections never hang indefinitely.

---

## 4. Conclusion

All Milestone M2 requirements are fully implemented, hardened, and verified with zero regressions, zero compiler warnings, 100% test pass rate across 105 tests, and successful release APK assembly.

---

## 5. Verification Method

### 5.1 Verification Commands Run

1. **Unit Test Suite**:
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
   **Result**: `BUILD SUCCESSFUL in 52s` — 105 tests executed, 0 failures, 0 errors, 0 skipped.

2. **Release Build**:
   ```powershell
   .\gradlew.bat assembleRelease
   ```
   **Result**: `BUILD SUCCESSFUL in 1m 2s` — Clean release APK generated with 0 compiler warnings.

### 5.2 Test Breakdown Summary
- `M2EmpiricalChallengeTest`: 6 tests (BitTorrent split, fragmented buffering, binary-safe HTTP, proxy normalization, PacketParser fuzzing, RFC synthesizers) — PASS
- `DpiEngineTest`: 28 tests — PASS
- `PacketParserTest`: 22 tests — PASS
- `M1EmpiricalChallengeTest`: 24 tests — PASS
- `DohResolverTest`: 12 tests — PASS
- `TrafficStatsTest`: 7 tests — PASS
- `UpdateManagerTest`: 6 tests — PASS
- **Total**: 105 tests passing.
