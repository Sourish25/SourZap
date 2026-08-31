# Forensic Audit Report — Milestone M2 (BitTorrent & P2P DPI Evasion Resilience)

**Work Product**: `app/src/main/java/com/sourzap/app/service/core/` (`DpiEngine.kt`, `TunTcpRelay.kt`, `HttpParser.kt`, `LocalDpiProxyServer.kt`, `PacketParser.kt`) & unit test suites  
**Profile**: General Project  
**Auditor**: `auditor_m2`  
**Verdict**: **`CLEAN`**  

---

## 1. Observation

### 1.1 Source Code & Integrity Inspection
1. **`DpiEngine.kt`**:
   - `BT_PROTOCOL_BYTES`: Verbatim 20-byte RFC/BEP 0003 prefix (`\x13BitTorrent protocol`), `MIN_BT_HANDSHAKE_LEN = 68`, `BT_PREFIX_LEN = 20`.
   - `isBitTorrentHandshake`: Performs strict byte-for-byte comparison of the 20-byte prefix without shortcuts or mock stubs.
   - `applyBitTorrentDesync`: Implements genuine `BT_SPLIT(1)` and `BT_SPLIT(2)` segment splitting with `socket.tcpNoDelay = true` and immediate per-chunk flushing.
   - Zero hardcoded mock responses, dummy returns, or bypassed logic.

2. **`TunTcpRelay.kt`**:
   - `isHandshakeComplete`: Implements multi-protocol completion detection covering RFC 5246/8446 TLS records (5-byte header, length evaluation), BEP 0003 BitTorrent handshakes (68-byte validation), and HTTP request headers (`findHeaderBoundary`).
   - Non-DPI protocols (SSH, WhatsApp Noise stream, DNS, raw TCP) return `true` immediately to ensure 0ms latency passthrough.
   - Multi-chunk handshake buffering in `startUpstreamConnection` buffers up to `MAX_HANDSHAKE_BUFFER_SIZE` (4096B) with `HANDSHAKE_BUFFER_TIMEOUT_MS` (150ms) before executing DPI desync, followed by immediate deallocation (`handshakeBuffer = null`) and direct high-speed streaming.

3. **`HttpParser.kt`**:
   - `findHeaderBoundary`: Detects all 4 valid RFC delimiter sequences (`\r\n\r\n` [4B], `\n\n` [2B], `\r\n\n` [3B], and `\n\r\n` [3B]).
   - `parseHttpRequest` & `desyncHttpPayload`: Utilizes `Charsets.ISO_8859_1` for lossless 1:1 character-to-byte encoding.
   - `desyncHttpPayload` & `splitHttpHeader`: Isolates the header slice and leaves attached binary body payloads (`0x80..0xFF`) 100% untouched via raw array slicing (`System.arraycopy`).

4. **`LocalDpiProxyServer.kt`**:
   - `parseHostAndPort`: Accurately handles bracketed IPv6 with ports (`[2001:db8::1]:8080`), bracketed IPv6 without ports (`[2001:db8::1]`), unbracketed IPv6 literals (`2001:db8::1`, `::1`), and hostnames.
   - `normalizeUriPath`: Normalizes absolute URIs into origin-form relative paths without regex replace, eliminating `PatternSyntaxException` on tracker query strings with metacharacters (`?`, `&`, `+`, `*`, `(`, `)`).
   - Preserves initial binary body bytes read during initial read cycle.

5. **`PacketParser.kt`**:
   - Dual-stack IPv4/IPv6 zero-exception parsing (`parseIpHeader`, `parseIpv4Header`, `parseIpv6Header`, `parseTcpHeader`, `parseUdpHeader`).
   - RFC 791, RFC 793, RFC 768, RFC 4443 checksum engines supporting IPv4 (4B) and IPv6 (16B) pseudo-headers.
   - Complete RFC synthesizers (`buildIpHeader`, `buildTcpPacket`, `buildTcpIpPacket`, `buildSynAckPacket`, `buildRstPacket`, `buildUdpIpPacket`, `buildIcmpv4DestinationUnreachablePacket`, `buildIcmpPortUnreachablePacket`, `buildIcmpv6DestinationUnreachablePacket`, `buildIcmpv6AddressUnreachablePacket`, `buildIcmpv6PortUnreachablePacket`, `buildIcmpv6AdminProhibitedPacket`).

### 1.2 Prohibited Patterns Check
- Hardcoded test results: **NONE**
- Facade implementations: **NONE**
- Fabricated verification outputs: **NONE**
- Self-certifying tests: **NONE**
- Execution delegation / external dependency violations: **NONE**

### 1.3 Behavioral Verification Results
- **Unit Test Suite**:
  ```powershell
  .\gradlew.bat testDebugUnitTest --no-parallel --max-workers=1
  ```
  **Result**: `BUILD SUCCESSFUL` — **138 tests executed, 0 failures, 0 errors, 0 ignored (100% success rate)**.
  - `ChallengerM2StressTest`: 9 passed
  - `DohResolverTest`: 15 passed
  - `DpiEngineTest`: 28 passed
  - `M1EmpiricalChallengeTest`: 12 passed
  - `M2EmpiricalChallengeTest`: 6 passed
  - `PacketParserFuzzAndRelayChallengerTest`: 19 passed
  - `PacketParserTest`: 22 passed
  - `TrafficStatsTest`: 13 passed
  - `UpdateManagerTest`: 14 passed

- **Release Build**:
  ```powershell
  .\gradlew.bat assembleRelease --no-parallel --max-workers=1
  ```
  **Result**: `BUILD SUCCESSFUL` — Release APK generated at `app/build/outputs/apk/release/app-release.apk` with 0 compiler warnings.

---

## 2. Logic Chain

1. **Protocol Authenticity**:
   The BitTorrent handshake and DPI evasion routines implement the standard BEP 0003 specification. Segment splitting (`BT_SPLIT(1)` / `BT_SPLIT(2)`) operates directly on the output stream with `TCP_NODELAY` and verified chunk reconstruction.

2. **Fragmentation Buffering & Bounded Invariants**:
   Handshake buffering correctly resolves packet fragmentation for TLS and BitTorrent without risking unbounded memory growth or indefinite blocking. The 4096-byte ceiling and 150ms timeout ensure resilience against anomalous or slow streams.

3. **Lossless Encoding Safety**:
   Using `ISO-8859-1` on isolated header bytes and raw array copy on body slices prevents ASCII replacement corruption (`?` / `0x3F`), preserving raw binary tracker info-hashes and POST request bodies bit-for-bit.

4. **Zero-Exception Boundary Clamping**:
   All packet parsing and checksum routines validate lengths and array bounds with defensive guards (`try ... catch (_: Throwable) { return null / 0 }`), eliminating VPN crash vectors under adversarial packet fuzzing (verified over 5,000 randomized packets).

---

## 3. Caveats

- In Windows test environments, running Gradle test/build tasks concurrently with multi-worker parallelism can encounter transient file-lock contention on `app\build\test-results\...\output.bin`. Executing with `--no-parallel --max-workers=1` ensures deterministic execution.

---

## 4. Conclusion

Milestone M2 (BitTorrent & P2P DPI Evasion Resilience) is fully implemented, mathematically genuine, and RFC-compliant. All 138 unit tests pass with 100% reliability, release APK builds cleanly, and no integrity violations exist.

**Final Audit Verdict**: **`CLEAN`**

---

## 5. Verification Method

To independently reproduce this verification:

```powershell
# 1. Run full unit test suite
.\gradlew.bat testDebugUnitTest --no-parallel --max-workers=1

# 2. Build release APK
.\gradlew.bat assembleRelease --no-parallel --max-workers=1
```
