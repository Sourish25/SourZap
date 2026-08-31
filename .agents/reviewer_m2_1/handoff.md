# Milestone M2 Review & Adversarial Critic Report

**Reviewer**: `reviewer_m2_1`  
**Target Milestone**: M2 (BitTorrent & P2P DPI Evasion Resilience)  
**Date**: 2026-08-31  
**Verdict**: **APPROVE**  

---

## 1. Observation

A comprehensive code audit, integrity inspection, adversarial challenge testing, and independent test suite verification were conducted across all modified files and test targets for Milestone M2:

1. **`app/src/main/java/com/sourzap/app/service/core/DpiEngine.kt`**:
   - `BT_PROTOCOL_BYTES` defines the 20-byte RFC / BEP 0003 protocol prefix (`\x13BitTorrent protocol`), `MIN_BT_HANDSHAKE_LEN = 68`, and `BT_PREFIX_LEN = 20`.
   - `isBitTorrentHandshake(payload, length)` performs bounds-safe 20-byte prefix comparison against `BT_PROTOCOL_BYTES`.
   - `applyBitTorrentDesync` implements `BT_SPLIT(1)` and `BT_SPLIT(2)` segment splitting with `socket.tcpNoDelay = true` and explicit chunk flushing (`outputStream.flush()`) on each segment.
   - Evaluated first in `desyncAndSend` pipeline before SSH, TLS, HTTP, and generic passthrough.

2. **`app/src/main/java/com/sourzap/app/service/core/TunTcpRelay.kt`**:
   - `isHandshakeComplete(buffer, length)` accurately detects completion for:
     - TLS records (`0x16 0x03`) by parsing record length bytes and checking `safeLen >= fullLen` (clamped to `MAX_HANDSHAKE_BUFFER_SIZE = 4096B`).
     - BitTorrent handshakes (`0x13`) by verifying prefix match and waiting for the full 68-byte handshake (`MIN_BT_HANDSHAKE_LEN`).
     - HTTP requests by detecting any of the 4 header boundaries via `HttpParser.findHeaderBoundary`.
     - Non-DPI protocols (SSH, WhatsApp Noise, DNS, raw TCP) return `true` immediately to ensure 0ms passthrough latency on initial chunk.
   - `senderJob` buffers multi-chunk handshake fragments with `HANDSHAKE_BUFFER_TIMEOUT_MS = 150L` timeout fallback and `MAX_HANDSHAKE_BUFFER_SIZE = 4096B` ceiling before transitioning to high-speed direct streaming.

3. **`app/src/main/java/com/sourzap/app/service/core/HttpParser.kt`**:
   - `findHeaderBoundary` correctly identifies all 4 standard and non-standard HTTP header delimiters (`\r\n\r\n` (4B), `\n\n` (2B), `\r\n\n` (3B), and `\n\r\n` (3B)).
   - Uses `Charsets.ISO_8859_1` for 100% lossless 1:1 character-to-byte mapping without Unicode substitution corruption.
   - `desyncHttpPayload` and `splitHttpHeader` isolate header slices from body slices, modifying only header casing (`hOst:  `) and copying binary request bodies (`0x00..0xFF`) via bit-exact byte arrays (`System.arraycopy`).

4. **`app/src/main/java/com/sourzap/app/service/core/LocalDpiProxyServer.kt`**:
   - `parseHostAndPort` parses IPv6 bracketed hosts with ports (`[2001:db8::1]:8080`), IPv6 bracketed without ports (`[2001:db8::1]`), unbracketed IPv6 (`2001:db8::1`, `::1`), and hostnames.
   - `normalizeUriPath` strips absolute proxy URIs into origin-form relative paths without invoking regex `replaceFirst` or `java.net.URI`, avoiding `PatternSyntaxException` or `URISyntaxException` on unescaped binary query strings (e.g. BitTorrent `info_hash` parameters, brackets, plus signs).
   - Preserves initial binary body bytes read during initial read cycle and writes them to upstream socket.

5. **`app/src/main/java/com/sourzap/app/service/core/PacketParser.kt`**:
   - Bounds-safe pre-conditions across `parseIpv4Header`, `parseIpv6Header`, `parseTcpHeader`, and `parseUdpHeader`, guarded by `try-catch (Throwable)`.
   - Dual-stack RFC checksum engines (`computeIpChecksum`, `computeTcpChecksum`, `computeUdpChecksum`, `computeIcmpv6Checksum`) supporting both IPv4 (4-byte) and IPv6 (16-byte) pseudo-headers.
   - Zero-exception RFC packet synthesizers: `buildIpHeader`, `buildTcpPacket`, `buildTcpIpPacket`, `buildSynAckPacket` (with MSS 1400 option), `buildRstPacket`, `buildUdpIpPacket`, and `buildIcmpv6DestinationUnreachablePacket` (with standard 1232B payload clipping).

6. **Integrity Audit**:
   - Zero hardcoded mock results or cheating facades found in source files.
   - Zero bypassed task logic.
   - 100% genuine algorithmic implementations.

---

## 2. Logic Chain

1. **BitTorrent DPI Evasion**:
   - ISPs detect BitTorrent traffic via continuous stream signature inspection for `\x13BitTorrent protocol`. Splitting the handshake at byte 1 (`[0x13]` | `[BitTorrent protocol...]`) or byte 2 (`[0x13, 'B']` | `[itTorrent protocol...]`) with `socket.tcpNoDelay = true` breaks middlebox inspection while allowing the remote peer's TCP stack to seamlessly reassemble the stream.
2. **Fragmented Handshake Buffering**:
   - When a client app fragments the handshake across small MTU packets, evaluating DPI on fragment 1 without buffering misses the protocol signature. Multi-chunk buffering in `TunTcpRelay` buffers until the handshake is complete, 4096B is reached, or 150ms timeout expires, ensuring DPI evasion is applied reliably without stalling non-DPI traffic (which bypasses in 0ms).
3. **Binary Safety**:
   - Using `US_ASCII` string decoding corrupts binary payloads ($\ge 0x80$) into `?` (`0x3F`). Isolating the header boundary and applying `ISO_8859_1` strictly to headers while streaming body slices as raw `ByteArray` ensures 100% bit-exact fidelity for binary tracker queries and uploads.
4. **Proxy URI Normalization**:
   - Tracker query strings frequently contain arbitrary bytes and regex metacharacters (`?`, `&`, `+`, `*`, `[`, `]`, `(`, `)`). Normalizing paths via deterministic character index scanning eliminates regex crashes and URI syntax parsing exceptions.
5. **Zero-Exception Tunnel Reliability**:
   - The VPN TUN interface receives arbitrary, truncated, and corrupted packets from apps and malicious networks. Wrapping all packet parsing, checksum calculation, and synthesis in strict boundary validation with defensive exception handling guarantees zero crashes of the VPN worker loop.

---

## 3. Caveats

- `MAX_HANDSHAKE_BUFFER_SIZE` is capped at 4096 bytes. Any non-standard protocol sending headers $> 4096$ bytes will flush at 4096B, which protects against memory exhaustion.
- `HANDSHAKE_BUFFER_TIMEOUT_MS` (150ms) ensures slow-loris clients do not hold buffers indefinitely.
- Physical TUN buffer starvation under extreme kernel pressure was verified at the coroutine and socket layer in unit tests; end-to-end OS-level TUN device throughput will be further validated in Android integration testing.

---

## 4. Conclusion

**Verdict**: **APPROVE**

Milestone M2 (BitTorrent & P2P DPI Evasion Resilience) satisfies all requirements defined in `ORIGINAL_REQUEST.md` and `PROJECT.md`. The implementation is robust, binary-safe, exception-resilient, and fully verified by unit and adversarial stress tests.

---

## 5. Verification Method

### 5.1 Verification Commands Run & Results

1. **Clean Test Suite Execution**:
   ```powershell
   .\gradlew.bat clean testDebugUnitTest
   ```
   **Result**: `BUILD SUCCESSFUL in 2m 28s`  
   **Executed**: 138 tests, 0 failures, 0 errors, 0 skipped.

2. **Release Build Assembly**:
   ```powershell
   .\gradlew.bat assembleRelease
   ```
   **Result**: `BUILD SUCCESSFUL in 2m 42s`  
   **Output APK**: Generated cleanly with 0 compiler warnings.

### 5.2 Test Breakdown Summary

| Test Suite Class | Tests | Failures | Errors | Skipped | Status |
|---|---|---|---|---|---|
| `ChallengerM2StressTest` | 9 | 0 | 0 | 0 | **PASS** |
| `M2EmpiricalChallengeTest` | 6 | 0 | 0 | 0 | **PASS** |
| `DpiEngineTest` | 28 | 0 | 0 | 0 | **PASS** |
| `PacketParserTest` | 22 | 0 | 0 | 0 | **PASS** |
| `PacketParserFuzzAndRelayChallengerTest` | 19 | 0 | 0 | 0 | **PASS** |
| `M1EmpiricalChallengeTest` | 12 | 0 | 0 | 0 | **PASS** |
| `DohResolverTest` | 15 | 0 | 0 | 0 | **PASS** |
| `TrafficStatsTest` | 13 | 0 | 0 | 0 | **PASS** |
| `UpdateManagerTest` | 14 | 0 | 0 | 0 | **PASS** |
| **Total** | **138** | **0** | **0** | **0** | **100% PASS** |
