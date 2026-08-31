# Project: SourZap Refinement

## Architecture
SourZap is an Android rootless DPI bypass and traffic routing utility operating via Android's `VpnService` (`tun0` interface) and a local SOCKS/HTTP DPI proxy server.
- **VPN Packet Engine**: Reads raw IP packets from `tun0`, parses IPv4/IPv6, demuxes TCP, UDP, ICMPv6. Routes TCP sessions through `TunTcpRelay` with DPI evasion, UDP datagrams through `TunUdpRelay` with NAT state table, and DNS through `DohResolver`.
- **Local DPI Proxy Server**: Provides a local SOCKS5 / HTTP CONNECT proxy (`LocalDpiProxyServer`) with HTTP header case desynchronization and TLS SNI splitting.
- **DPI Evasion Engine**: `DpiEngine`, `TlsParser`, `HttpParser`, and `PacketParser` detect protocols (TLS ClientHello, HTTP methods/headers, BitTorrent peer wire, uTP, DHT) and perform TCP segment splitting, out-of-order delivery, TCP window manipulation, and header desynchronization.
- **Buffer & Resource Management**: `ByteArrayPool` manages tiered byte array buffers (4K, 16K, 32K, 64K) to minimize GC churn in the high-throughput packet loop.
- **UI & Telemetry Layer**: Jetpack Compose UI (`DashboardScreen`, `TrafficScreen`, `SpeedTestScreen`, `SettingsScreen`) displaying real-time metrics from `TrafficMonitor`, driven by reactive repositories and `SpeedTestEngine`.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | TunTcpRelay Threading & Dispatching | Replace unbounded cached thread pool with bounded coroutine dispatcher to prevent OS thread exhaustion under high connection count. | M1 | Survey R1 |
| 2 | TunTcpRelay Bounded Backpressure | Replace unbounded `Channel.UNLIMITED` in `TcpSession.sendQueue` with bounded queue and overflow policy to prevent heap exhaustion. | M1 | Survey R1 |
| 3 | TunTcpRelay Socket Teardown Safety | Ensure socket is referenced before connect and closed immediately on early RST or cancellation to prevent fd leaks. | M1 | Survey R1 |
| 4 | TunTcpRelay Lock Optimization | Optimize `vpnOutput` synchronization to eliminate lock contention on high-throughput segment flushing. | M1 | Survey R1 |
| 5 | TunUdpRelay Non-Blocking TUN Dispatch | Move blocking `socket.send` out of the main TUN reader loop into non-blocking workers. | M1 | Survey R1 |
| 6 | TunUdpRelay O(1) NAT Lookup & Routing | Implement collision-free O(1) primary (IP:Port) and secondary (IP) NAT tables replacing O(N) linear scans. | M1 | Survey R1 |
| 7 | ByteArrayPool Atomic Accounting | Ensure atomic counter synchronization and invariant validation in buffer allocation and recycling. | M1 | Survey R1 |
| 8 | LocalDpiProxyServer Pump Deadlock Fix | Implement cooperative coroutine cancellation and timeouts on bidirectional stream pump to eliminate socket leaks on half-close. | M1 | Survey R1 |
| 9 | DohResolver DatagramSocket Leak Fix | Enforce `DatagramSocket().use { ... }` in `queryUdpDns` so cancelled parallel racing sockets are always closed. | M1 | Survey R1 |
| 10 | BitTorrent Wire Protocol Desync | Implement BitTorrent handshake detection and segment splitting at offset 1 or 2 with `TCP_NODELAY` in `DpiEngine`. | M2 | Survey R2 |
| 11 | Fragmented Handshake Buffering | Implement multi-chunk handshake buffering in `TunTcpRelay` so fragmented ClientHello and BitTorrent handshakes are not bypassed. | M2 | Survey R2 |
| 12 | Binary-Safe HTTP Host Desync | Update `HttpParser.desyncHttpPayload` to modify headers in-place without decoding binary body bytes to US-ASCII. | M2 | Survey R2 |
| 13 | LocalDpiProxyServer URI Normalization | Robust URI path and query normalization handling unescaped raw bytes in tracker URLs and IPv6 bracketed hosts. | M2 | Survey R2 |
| 14 | PacketParser Zero-Exception Hardening | Add complete boundary validation to `parseTcpHeader`, `parseUdpHeader`, checksum calculators, and packet builders. | M2 | Survey R2 |
| 15 | SpeedTestEngine Cancellation Fix | Assign `currentJob` in `runSpeedTest()` and enforce cooperative cancellation of background `OkHttpClient` streams in `cancelTest()`. | M3 | Survey R3 |
| 16 | Compose Lifecycle-Aware Collection | Integrate `collectAsStateWithLifecycle` to pause Flow collections when app is in background/stopped state. | M3 | Survey R3 |
| 17 | Persistent Update Download State | Preserve APK download progress across Compose screen navigation. | M3 | Survey R3 |
| 18 | Telemetry & Repository Thread Safety | Ensure thread-safe counters, FIFO log bounds (50 items), and atomic preference mutations in `TrafficMonitor` and Repositories. | M3 | Survey R3 |
| 19 | Comprehensive Unit Test Suite Expansion | Create unit test suites for `TunTcpRelayTest`, `TunUdpRelayTest`, `LocalDpiProxyServerTest`, `SpeedTestEngineTest`, `TrafficMonitorTest`, `RepositoriesTest`. | M4 | Survey R4 |
| 20 | Full Verification & Release Build QA | Achieve 100% passing tests via `./gradlew.bat testDebugUnitTest` and successful `./gradlew.bat assembleRelease`. | M4 | Survey R4 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | M1: VPN Relay & Sockets Hardening | Hardening `TunTcpRelay`, `TunUdpRelay`, `ByteArrayPool`, `LocalDpiProxyServer`, and `DohResolver` for socket leaks, queue bounds, and concurrency. | none | PLANNED |
| 2 | M2: DPI & BitTorrent Resilience | BitTorrent wire desynchronization, fragmented handshake buffering, binary-safe HTTP desynchronization, URI normalization, and PacketParser zero-exception guarantees. | M1 | PLANNED |
| 3 | M3: UI Lifecycle & Memory Leak Elimination | `SpeedTestEngine` coroutine cancellation, Compose `collectAsStateWithLifecycle`, update download persistence, telemetry thread safety. | M2 | PLANNED |
| 4 | M4: Test Suite Expansion & Final QA | Expanding test suites (`TunTcpRelayTest`, `TunUdpRelayTest`, `LocalDpiProxyServerTest`, `SpeedTestEngineTest`, `TrafficMonitorTest`, `RepositoriesTest`), 100% `testDebugUnitTest` pass, and `assembleRelease` verification. | M3 | PLANNED |

## Interface Contracts
### TunTcpRelay ↔ DpiEngine
- `DpiEngine.desyncAndSend(socket: Socket, outputStream: OutputStream, payload: ByteArray, length: Int, strategy: DpiStrategy, onTechniqueApplied: (String) -> Unit)`
- `TunTcpRelay` manages connection lifecycle, RFC 793 states, and bounded `sendQueue`; delegates initial packet desynchronization to `DpiEngine`.

### TunUdpRelay ↔ PacketParser
- `PacketParser.buildUdpIpPacket(srcIp, dstIp, srcPort, dstPort, payload, payloadOffset, payloadLen)` returns synthesised `ByteArray` with zero-exception guarantee.
- `TunUdpRelay` routes outgoing/incoming UDP datagrams using O(1) bidirectional NAT table.

### UI Screens ↔ TrafficMonitor / SpeedTestEngine
- `TrafficMonitor.stats: StateFlow<TrafficStats>`, `TrafficMonitor.recentLogs: StateFlow<List<ConnectionLog>>`, `TrafficMonitor.isVpnActive: StateFlow<Boolean>`.
- `SpeedTestEngine.state: StateFlow<SpeedTestState>`, `SpeedTestEngine.runSpeedTest()`, `SpeedTestEngine.cancelTest()`.

## Code Layout
- `app/src/main/java/com/sourzap/app/service/core/`: Core VPN & DPI logic (`TunTcpRelay.kt`, `TunUdpRelay.kt`, `ByteArrayPool.kt`, `LocalDpiProxyServer.kt`, `DohResolver.kt`, `DpiEngine.kt`, `PacketParser.kt`, `HttpParser.kt`, `TlsParser.kt`, `SourZapVpnService.kt`).
- `app/src/main/java/com/sourzap/app/speedtest/`: `SpeedTestEngine.kt`, `SpeedTestState.kt`.
- `app/src/main/java/com/sourzap/app/ui/`: Jetpack Compose screens (`DashboardScreen.kt`, `TrafficScreen.kt`, `SpeedTestScreen.kt`, `SettingsScreen.kt`, `MainActivity.kt`).
- `app/src/main/java/com/sourzap/app/data/`: `SettingsRepository.kt`, `StrategyRepository.kt`, `TrafficMonitor.kt`, `UpdateManager.kt`.
- `app/src/test/java/com/sourzap/app/`: Test suites (`DohResolverTest.kt`, `DpiEngineTest.kt`, `PacketParserTest.kt`, `TrafficStatsTest.kt`, `UpdateManagerTest.kt`, new test suites).
