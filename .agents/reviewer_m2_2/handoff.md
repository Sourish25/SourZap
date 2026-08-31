# Handoff Report — Milestone M2 Adversarial & Robustness Review

**Reviewer**: `reviewer_m2_2`  
**Roles**: `reviewer`, `critic`  
**Milestone**: M2 (BitTorrent & P2P DPI Evasion Resilience)  
**Date**: 2026-08-31  
**Verdict**: **`APPROVE`**

---

## 1. Observation

### 1.1 Source Code Audit
1. **`DpiEngine.kt`** (`app/src/main/java/com/sourzap/app/service/core/DpiEngine.kt`):
   - Lines 15–37: Defines `BT_PROTOCOL_BYTES` (`\x13BitTorrent protocol`), `MIN_BT_HANDSHAKE_LEN = 68`, `BT_PREFIX_LEN = 20`. `isBitTorrentHandshake` checks array boundaries (`minOf(payload.size, length) >= 20`) and performs byte-by-byte comparison.
   - Lines 48, 91–111: `socket.tcpNoDelay = true` is explicitly enabled. `applyBitTorrentDesync` cleanly splits at offset 1 (`BT_SPLIT(1)`) or offset 2 (`BT_SPLIT(2)`) based on `strategy.tlsSplitOffset`, flushing both segments immediately.

2. **`TunTcpRelay.kt`** (`app/src/main/java/com/sourzap/app/service/core/TunTcpRelay.kt`):
   - Lines 61–117: `isHandshakeComplete(buffer, length)` correctly parses TLS record lengths (RFC 5246/8446), validates full 68-byte BitTorrent handshakes, inspects HTTP header delimiters via `HttpParser.findHeaderBoundary`, and returns `true` immediately (0ms passthrough) for non-DPI protocols (SSH, WhatsApp Noise, DNS, raw TCP).
   - Lines 426–512: `senderJob` buffers handshake chunks with `withTimeoutOrNull(HANDSHAKE_BUFFER_TIMEOUT_MS)` (150ms timeout) and caps at `MAX_HANDSHAKE_BUFFER_SIZE` (4096B). Once complete or timed out, it triggers `DpiEngine.desyncAndSend` and transitions cleanly to high-speed unbuffered streaming (`session.sendQueue.receiveCatching()`).

3. **`HttpParser.kt`** (`app/src/main/java/com/sourzap/app/service/core/HttpParser.kt`):
   - Lines 23–53: `findHeaderBoundary` checks for `\r\n\r\n` (4 bytes), `\n\n` (2 bytes), `\r\n\n` (3 bytes), and `\n\r\n` (3 bytes).
   - Lines 59–94, 101–130: `parseHttpRequest` and `desyncHttpPayload` use `Charsets.ISO_8859_1` for lossless 1:1 character-to-byte mapping. `desyncHttpPayload` isolates header bytes (`0 until headerEnd`) for case modification, leaving body bytes (`headerEnd until safeLen`) as raw `ByteArray` slices copied via `System.arraycopy`.

4. **`LocalDpiProxyServer.kt`** (`app/src/main/java/com/sourzap/app/service/core/LocalDpiProxyServer.kt`):
   - Lines 40–71: `parseHostAndPort` parses bracketed IPv6 with port (`[2001:db8::1]:8080`), bracketed IPv6 without port (`[2001:db8::1]`), unbracketed IPv6 (`2001:db8::1`, `::1`), and standard hostnames with port fallback bounds (`1..65535`).
   - Lines 78–119: `normalizeUriPath` parses proxy-style URIs without `Regex.replaceFirst` or `java.net.URI`, preventing `PatternSyntaxException` and `URISyntaxException` on tracker query strings with unescaped binary bytes (`info_hash=%80%9F%A1%B2`) or IPv6 brackets.
   - Lines 189–206, 384–392: Initial binary body bytes read during header parsing are preserved and prepended to outgoing payload.

5. **`PacketParser.kt`** (`app/src/main/java/com/sourzap/app/service/core/PacketParser.kt`):
   - Lines 70–145, 151–228: `parseIpv4Header`, `parseIpv6Header`, `parseTcpHeader`, `parseUdpHeader` contain strict pre-condition clamping against buffer bounds and return `null` on truncation, invalid version, malformed IHL (<20 or >60 or >buffer), negative offsets, or integer underflows.
   - Lines 236–410: RFC 791, 793, 768, 4443, 8200 checksum engines support dual-stack IPv4 (4-byte) and IPv6 (16-byte) pseudo-headers with 16-bit 1's complement accumulation.
   - Lines 418–921: Zero-exception packet synthesizers (`buildIpHeader`, `buildTcpPacket`, `buildTcpIpPacket`, `buildSynAckPacket`, `buildRstPacket`, `buildUdpIpPacket`, `buildIcmpv4DestinationUnreachablePacket`, `buildIcmpPortUnreachablePacket`, `buildIcmpv6DestinationUnreachablePacket`, `buildIcmpv6AddressUnreachablePacket`, `buildIcmpv6PortUnreachablePacket`, `buildIcmpv6AdminProhibitedPacket`).

### 1.2 Automated Test Execution
- Command executed: `.\gradlew.bat testDebugUnitTest`
- Result: `BUILD SUCCESSFUL in 1m 22s`
- Test Suite Breakdown:
  | Test Suite | Tests | Failures | Errors | Skipped | Status |
  |---|---|---|---|---|---|
  | `ChallengerM2StressTest` | 9 | 0 | 0 | 0 | **PASS** |
  | `DohResolverTest` | 15 | 0 | 0 | 0 | **PASS** |
  | `DpiEngineTest` | 28 | 0 | 0 | 0 | **PASS** |
  | `M1EmpiricalChallengeTest` | 12 | 0 | 0 | 0 | **PASS** |
  | `M2EmpiricalChallengeTest` | 6 | 0 | 0 | 0 | **PASS** |
  | `PacketParserFuzzAndRelayChallengerTest` | 19 | 0 | 0 | 0 | **PASS** |
  | `PacketParserTest` | 22 | 0 | 0 | 0 | **PASS** |
  | `TrafficStatsTest` | 13 | 0 | 0 | 0 | **PASS** |
  | `UpdateManagerTest` | 14 | 0 | 0 | 0 | **PASS** |
  | **Total** | **138** | **0** | **0** | **0** | **100% PASS** |

### 1.3 Integrity Verification
- No hardcoded test results, fake responses, or stubbed bypasses were found.
- Implementations perform genuine byte manipulation, RFC-compliant checksum calculations, and real socket streaming.

---

## 2. Logic Chain

1. **Slow-loris & Fragmented Stream Resilience**:
   In `TunTcpRelay`, `senderJob` waits without timeout for the first chunk. Once `currentBufSize > 0`, it polls subsequent chunks with `withTimeoutOrNull(HANDSHAKE_BUFFER_TIMEOUT_MS)` (150ms). If a slow-loris client trickle-feeds 1 byte every 200ms, the 150ms timer expires, `complete` evaluates to `true`, accumulated bytes are dispatched via `DpiEngine.desyncAndSend`, and `isHandshakeDesynced` is set to `true`. All subsequent trickle chunks pass directly through the unbuffered channel without delay or starvation.

2. **Binary Body Safety**:
   In `LocalDpiProxyServer` and `HttpParser`, `findHeaderBoundary` locates the exact delimiter offset (`\r\n\r\n`, `\n\n`, `\r\n\n`, or `\n\r\n`). Only the bytes preceding this delimiter are converted to `String` using `Charsets.ISO_8859_1` (guaranteeing exact 1:1 character-to-byte mapping without Unicode substitution). Body bytes (`0x80..0xFF`) are untouched and copied directly as raw `ByteArray` slices via `System.arraycopy`.

3. **Dual-Stack IPv6 & URI Normalization**:
   In `LocalDpiProxyServer`, `parseHostAndPort` extracts bracketed (`[2001:db8::1]:8080`) and unbracketed (`2001:db8::1`) IPv6 literals and validates port bounds (`1..65535`). `normalizeUriPath` parses the authority and path using linear character scanning past brackets rather than regular expressions or `java.net.URI`, completely eliminating `PatternSyntaxException` and `URISyntaxException` on tracker query strings with metacharacters.

4. **Zero-Exception TUN Boundary Safety**:
   In `PacketParser`, all parsing functions validate `offset >= 0`, `length >= 20/40/8`, and `minOf(length, buffer.size)`. Every function is wrapped in `try-catch (_: Throwable)` returning `null` or `EMPTY_BYTE_ARRAY`. Fuzz testing over 10,000 randomized iterations confirmed zero uncaught exceptions.

---

## 3. Caveats

- `MAX_HANDSHAKE_BUFFER_SIZE` is fixed at 4096 bytes; protocols with headers exceeding 4096B will flush at the 4096B boundary, which is the intended security and memory guardrail.
- `HANDSHAKE_BUFFER_TIMEOUT_MS` (150ms) balances handshake accumulation against interactive latency.

---

## 4. Conclusion

The Milestone M2 implementation satisfies all requirements set forth in `ORIGINAL_REQUEST.md` and `PROJECT.md`. The codebase exhibits excellent architectural hygiene, zero integrity violations, robust corner-case handling, and 100% test pass rate across 138 unit tests.

**Verdict**: **`APPROVE`**

---

## 5. Verification Method

To independently verify the test suite:
```powershell
.\gradlew.bat testDebugUnitTest
```
Expected result: `BUILD SUCCESSFUL`, 138 tests passing across all test suites with 0 failures and 0 errors.
