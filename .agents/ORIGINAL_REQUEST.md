# Original User Request

## 2026-08-31T09:16:43Z

Comprehensive codebase audit, bug fixing, test expansion, and deep refinement for SourZap (Android rootless DPI bypass & traffic routing utility), resuming from verified Milestone M1.

Working directory: c:\Users\Sourish\Desktop\SourZap
Integrity mode: development

## Context & Current State
- Milestone M1 (VPN Packet Relay & Socket Concurrency Hardening) is already implemented and verified clean (all 95 unit tests passing, release build successful).
- Uncommitted changes in working directory: `TunTcpRelay.kt`, `TunUdpRelay.kt`, `ByteArrayPool.kt`, `LocalDpiProxyServer.kt`, `DohResolver.kt`, `SourZapVpnService.kt`, and `M1EmpiricalChallengeTest.kt`.
- Detailed architectural survey and feature inventory are preserved in `PROJECT.md`.

## Requirements

### R1. BitTorrent & P2P DPI Evasion Resilience (Milestone M2)
- Harden `DpiEngine`, `TlsParser`, `HttpParser`, and `PacketParser` against ISP deep packet inspection and non-standard tracker responses.
- Implement BitTorrent handshake detection and segment splitting at offset 1 or 2 with `TCP_NODELAY` in `DpiEngine`.
- Implement multi-chunk / fragmented handshake buffering in `TunTcpRelay` so fragmented ClientHello and BitTorrent handshakes are not bypassed.
- Refactor `HttpParser.desyncHttpPayload` to modify headers in-place without corrupting binary body bytes (ASCII decoding safety).
- Ensure `LocalDpiProxyServer` robustly normalizes URI paths and queries containing unescaped raw bytes in tracker URLs and IPv6 bracketed hosts.
- Add zero-exception guarantees and complete boundary validation across `PacketParser` (TCP/UDP header parsers, checksum calculators, packet synthesizers).

### R2. UI State Lifecycle & Memory Leak Elimination (Milestone M3)
- Fix coroutine cancellation in `SpeedTestEngine` by properly tracking active jobs and cancelling background `OkHttpClient` streams.
- Audit Jetpack Compose screens (`DashboardScreen`, `TrafficScreen`, `SpeedTestScreen`, `SettingsScreen`) for coroutine scope leaks, state recomposition efficiency, and proper unregistering of live metrics collectors on disposal.
- Integrate `collectAsStateWithLifecycle` where appropriate to pause telemetry Flow collections when the app is backgrounded.
- Preserve update download state across screen navigation in `UpdateManager`.
- Ensure thread-safe counters, FIFO log bounds (50 items), and atomic preference mutations in `TrafficMonitor` and repositories.

### R3. Automated Test Suite Expansion & Quality Assurance (Milestone M4)
- Expand unit and integration test suites (`app/src/test/...`) to cover newly hardened packet relay edge cases, BitTorrent DPI evasion routines, HTTP binary safety, Compose state models, and speed test cancellation.
- Maintain 100% passing test execution without flaky failures.

## Acceptance Criteria

### Reliability & Correctness
- [ ] No unclosed file descriptors, memory leaks, or unhandled exceptions in VPN relay, DPI routines, or proxy servers.
- [ ] DPI evasion rules and packet splitting handle malformed and fragmented packets gracefully without dropping connections.
- [ ] Jetpack Compose screens operate smoothly with zero memory leaks and clean state collection.
- [ ] BitTorrent handshake splitting (`BT_SPLIT(1)` / `BT_SPLIT(2)`) and binary-safe HTTP header modification verified.

### Automated Verification
- [ ] `./gradlew.bat testDebugUnitTest` runs with 100% passing test suite across all existing and new test classes.
- [ ] `./gradlew.bat assembleRelease` finishes with `BUILD SUCCESSFUL`.
