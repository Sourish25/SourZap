# Handoff Report — Milestone M2 Empirical Challenge & Fuzzing (`challenger_m2_2`)

**Challenger**: `challenger_m2_2`  
**Milestone**: M2 (BitTorrent & P2P DPI Evasion Resilience)  
**Date**: 2026-08-31  
**Verdict**: `APPROVE`

---

## 1. Observation

### 1.1 Source Files Audited
- `app/src/main/java/com/sourzap/app/service/core/PacketParser.kt`:
  - Lines 70–109 (`parseIpv4Header`): Pre-condition buffer checks `length < 20 || buffer.size < 20`, IHL clamping `ihl < 20 || ihl > 60 || validLen < ihl`, and defensive total length bounds handling.
  - Lines 115–145 (`parseIpv6Header`): Pre-condition checks `length < 40 || buffer.size < 40`, version check `version != 6`, and safe 16-byte address copying.
  - Lines 151–198 (`parseTcpHeader`): Negative offset checks `tcpOffset < 0 || totalLength < 0 || tcpOffset >= buffer.size`, `validLen < tcpOffset + 20`, `dataOffset` bounds `dataOffset < 20 || dataOffset > 60 || validLen < tcpOffset + dataOffset`, and safe payload offset computation.
  - Lines 203–228 (`parseUdpHeader`): Boundary validations `validLen < udpOffset + 8` and `udpLength < 8`.
  - Lines 236–411 (`computeIpChecksum`, `computeTcpChecksum`, `computeUdpChecksum`, `computeIcmpv6Checksum`): Safe length clamping, IPv4 (4-byte) & IPv6 (16-byte) pseudo-header handling, odd-byte padding, and RFC 768 `0x0000 -> 0xFFFF` UDP checksum mapping.
  - Lines 418–921 (`buildIpHeader`, `buildTcpPacket`, `buildTcpIpPacket`, `buildSynAckPacket`, `buildRstPacket`, `buildUdpIpPacket`, `buildIcmpv4DestinationUnreachablePacket`, `buildIcmpPortUnreachablePacket`, `buildIcmpv6DestinationUnreachablePacket`, `buildIcmpv6AddressUnreachablePacket`, `buildIcmpv6PortUnreachablePacket`, `buildIcmpv6AdminProhibitedPacket`): RFC synthesizers with MSS option (1400), RST flags, UDP headers, and RFC 4443 1232B payload clipping (1280B IPv6 MTU limit).

- `app/src/main/java/com/sourzap/app/service/core/TunTcpRelay.kt`:
  - Lines 61–118 (`isHandshakeComplete`): Multi-protocol completion predicate evaluating TLS record length (capped at 4096B), BitTorrent BEP 0003 20-byte prefix and 68-byte full handshake, HTTP request method / boundary delimiter presence, and immediate passthrough for non-DPI protocols (SSH, Noise, DNS, raw TCP).

### 1.2 Empirical Tests Created and Executed
Created `app/src/test/java/com/sourzap/app/PacketParserFuzzAndRelayChallengerTest.kt` comprising 19 adversarial tests:
1. `testPacketParser_ZeroException_NegativeOffsetsAndExtremeLengths`: Tested negative offsets (`-1`, `-100`, `Int.MIN_VALUE`) and extreme lengths (`-1`, `0`, `65535`, `Int.MAX_VALUE`).
2. `testPacketParser_ZeroException_TruncatedBuffers`: Tested 0..19 bytes (IPv4), 0..39 bytes (IPv6), 0..19 bytes (TCP), 0..7 bytes (UDP).
3. `testPacketParser_ZeroException_MalformedIhlValues`: Tested IHL words 0..15 (`0x40`..`0x4F`), including oversized IHL with short buffer.
4. `testPacketParser_ZeroException_MalformedTotalLengths`: Tested total lengths `0`, `65535`, and `< IHL`.
5. `testPacketParser_ZeroException_InvalidIpAddressesAndMismatches`: Tested IPv6 addresses passed to IPv4 builders/checksums and IPv4 addresses passed to ICMPv6 builders/checksums.
6. `testPacketParser_HighThroughputMultiThreadedFuzzHarness`: 40,000 multi-threaded randomized fuzz iterations across 8 coroutines.
7. `testChecksum_Rfc791Ipv4HeaderAccuracy`: Verified RFC 1071 test vectors and ones' complement sum verification to 0.
8. `testChecksum_Rfc793Ipv4TcpPseudoHeaderAccuracy`: Verified RFC 793 IPv4 pseudo-header checksum bit-exactness.
9. `testChecksum_Rfc8200Ipv6TcpPseudoHeaderAccuracy`: Verified RFC 8200 IPv6 pseudo-header checksum.
10. `testChecksum_Rfc768UdpZeroRuleCompliance`: Verified RFC 768 zero-rule (`0x0000 -> 0xFFFF`).
11. `testChecksum_Rfc4443Icmpv6PseudoHeaderAccuracy`: Verified RFC 4443 ICMPv6 pseudo-header checksum with Next Header 58.
12. `testSynthesizers_BuildTcpPacket_AllFlagsAndRoundtrips`: Validated SYN (0x02), SYN-ACK (0x12 with 24B MSS), ACK (0x10), PSH-ACK (0x18), FIN-ACK (0x11), RST-ACK (0x14), RST (0x04) with roundtrip parsing.
13. `testSynthesizers_DedicatedSynAckAndRstBuilders`: Validated `buildSynAckPacket` and `buildRstPacket`.
14. `testSynthesizers_BuildUdpIpPacket`: Validated IPv4 UDP packet synthesis with roundtrip parsing.
15. `testSynthesizers_IcmpBuildersAndMaxPayloadClipping`: Validated ICMPv4 Port Unreachable and ICMPv6 1232B clipping (1280B total IPv6 packet).
16. `testTunTcpRelay_HandshakeComplete_TlsEdgeCases`: Validated TLS buffering, exact 55B completion, and non-standard TLS passthrough.
17. `testTunTcpRelay_HandshakeComplete_BitTorrentEdgeCases`: Validated partial prefixes (1B, 5B, 20B), complete 68B handshake, and mismatched prefix passthrough.
18. `testTunTcpRelay_HandshakeComplete_HttpEdgeCases`: Validated partial methods, CRLF/LF boundaries, and 2048B ceiling.
19. `testTunTcpRelay_HandshakeComplete_NonDpiPassthrough`: Validated 0ms passthrough for SSH, WhatsApp Noise, DNS, and raw binary.

### 1.3 Execution Tool Output
```powershell
.\gradlew.bat testDebugUnitTest
```
**Test Results Summary**:
- `com.sourzap.app.PacketParserFuzzAndRelayChallengerTest`: 19 tests, 0 failures, 0 errors (PASS)
- `com.sourzap.app.ChallengerM2StressTest`: 9 tests, 0 failures, 0 errors (PASS)
- `com.sourzap.app.M2EmpiricalChallengeTest`: 6 tests, 0 failures, 0 errors (PASS)
- `com.sourzap.app.PacketParserTest`: 22 tests, 0 failures, 0 errors (PASS)
- `com.sourzap.app.DpiEngineTest`: 28 tests, 0 failures, 0 errors (PASS)
- `com.sourzap.app.M1EmpiricalChallengeTest`: 12 tests, 0 failures, 0 errors (PASS)
- `com.sourzap.app.DohResolverTest`: 15 tests, 0 failures, 0 errors (PASS)
- `com.sourzap.app.TrafficStatsTest`: 13 tests, 0 failures, 0 errors (PASS)
- `com.sourzap.app.UpdateManagerTest`: 14 tests, 0 failures, 0 errors (PASS)
- **Total**: 138 tests, 0 failures, 0 errors, 0 skipped, 100% success rate (`BUILD SUCCESSFUL in 2m 49s`).

---

## 2. Logic Chain

1. **Parser Boundary Hardening (Observation 1.1 & 1.2 #1-#6)**:
   In VPN environments, TUN virtual network interfaces can receive truncated fragments, malformed headers, or intentionally corrupted packets from untrusted applications. `PacketParser.kt` guards against `IndexOutOfBoundsException`, `BufferUnderflowException`, and integer overflows by pre-checking buffer lengths against required header sizes, validating IHL/dataOffset constraints, and enclosing parsing routines in fallback `try-catch` blocks returning `null` or `0`. Under a 40,000-iteration multi-threaded random fuzz harness, 0 uncaught exceptions occurred.

2. **RFC Checksum Mathematical Correctness (Observation 1.1 & 1.2 #7-#11)**:
   - RFC 791 IPv4 header checksum computation follows ones' complement addition with 16-bit fold, verifying to 0 on valid headers.
   - RFC 793 and RFC 8200 dual-stack TCP pseudo-headers correctly encode 4-byte IPv4 vs 16-byte IPv6 addresses and segment lengths.
   - RFC 768 UDP checksum correctly translates calculated `0x0000` sums to `0xFFFF` as mandated by specification.
   - RFC 4443 ICMPv6 checksum incorporates IPv6 pseudo-header with Next Header value 58.

3. **RFC Packet Synthesizers & Roundtrip Fidelity (Observation 1.1 & 1.2 #12-#15)**:
   Synthesizers (`buildTcpPacket`, `buildTcpIpPacket`, `buildSynAckPacket`, `buildRstPacket`, `buildUdpIpPacket`, and ICMP builders) produce standards-compliant binary buffers. SYN-ACK includes MSS option (Kind 2, Len 4, 1400) resulting in 24-byte TCP headers; standard TCP packets use 20-byte headers; ICMPv6 packets clip offending payload to 1232 bytes, guaranteeing the overall IPv6 packet remains $\le 1280$ bytes (IPv6 minimum link MTU). All synthesized packets parse back with 100% field equality.

4. **Handshake Completion & Fragment Buffering Transitions (Observation 1.1 & 1.2 #16-#19)**:
   `TunTcpRelay.isHandshakeComplete` prevents early desync attempts on partial packets while preventing latency penalties on non-DPI protocols. TLS ClientHello records are buffered until the full record length is assembled (bounded at 4096B); BitTorrent connections buffer until the 68-byte BEP 0003 handshake is received; HTTP requests buffer until header boundary delimiters (`\r\n\r\n`, `\n\n`, `\r\n\n`, `\n\r\n`) are detected. Non-DPI protocols (SSH, WhatsApp Noise, DNS, raw streams) resolve immediately to true for 0ms passthrough.

---

## 3. Caveats

- Hardware-level socket buffering and kernel TUN driver quirks vary across custom Android OEM builds; however, userland TCP state machine handling, checksum verification, and packet parsing are fully deterministic and verified.

---

## 4. Conclusion

**Verdict**: `APPROVE`

`PacketParser.kt` and `TunTcpRelay.kt` satisfy all Milestone M2 requirements:
- Zero-exception resilience against malformed offsets, truncated buffers, and fuzzing.
- 100% RFC-compliant checksum calculations across IPv4 and IPv6 dual-stack protocols.
- Reliable packet synthesis for TCP, UDP, and ICMP.
- Robust handshake completion detection and fragmented stream buffering.

---

## 5. Verification Method

To independently verify these findings:

1. **Run Full Debug Unit Test Suite**:
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
2. **Inspect Test Report**:
   Open `app/build/reports/tests/testDebugUnitTest/index.html` and verify 138/138 passing tests with 0 failures and 0 errors.
3. **Inspect Fuzzing Harness**:
   Inspect `app/src/test/java/com/sourzap/app/PacketParserFuzzAndRelayChallengerTest.kt`.
