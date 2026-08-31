# Requirement R1 Technical Audit & Architectural Survey Report
**VPN Packet Relay & Socket Concurrency Hardening**

## Executive Summary
This audit evaluates the VPN networking layer of SourZap (`TunTcpRelay`, `TunUdpRelay`, `ByteArrayPool`, `LocalDpiProxyServer`, `SourZapVpnService`, and `DohResolver`). Multiple critical vulnerabilities were identified across socket lifecycle management, thread explosion, memory backpressure under saturation, O(N) CPU table bottlenecks, and unclosed file descriptors during DNS races. This document provides exact observations, logical deductions, caveats, conclusions, and verification steps.

---

## 1. Observation

### 1.1 `TunTcpRelay` (`app/src/main/java/com/sourzap/app/service/core/TunTcpRelay.kt`)

1. **Unbounded Cached Thread Pool**:
   - Lines 41–44:
     ```kotlin
     private val tcpExecutor = Executors.newCachedThreadPool { r ->
         Thread(r, "SourZap-TunTcpWorker").apply { isDaemon = true }
     }
     private val tcpDispatcher = tcpExecutor.asCoroutineDispatcher()
     ```
   - Lines 286, 313: For each TCP connection, two coroutines (`streamJob` and child `senderJob`) run on `tcpDispatcher`. `streamJob` blocks in synchronous socket I/O (`input.read(readBuffer)` at line 358).
   - Under concurrent load (e.g., 200 swarm or web connections), `newCachedThreadPool` creates hundreds of OS threads, which risks `java.lang.OutOfMemoryError: pthread_create failed` on Android.

2. **Unbounded Queue Channel Buffer (`Channel.UNLIMITED`)**:
   - Line 78:
     ```kotlin
     val sendQueue: Channel<ByteArray> = Channel(Channel.UNLIMITED)
     ```
   - Lines 264, 281:
     ```kotlin
     val payload = buffer.copyOfRange(payloadOffset, length)
     ...
     session.sendQueue.trySend(payload)
     ```
   - When the client app transmits data faster than the upstream remote network can consume (or while waiting up to 3000ms for `socket.connect`), unpooled byte arrays accumulate indefinitely in the queue without backpressure, risking JVM heap exhaustion.

3. **Socket Descriptor Leak on Early Teardown / RST**:
   - Lines 291–306:
     ```kotlin
     val socket = Socket().apply { ... }
     vpnService.protect(socket)
     socket.connect(InetSocketAddress(session.dstIp, session.dstPort), 3000)
     session.socket = socket
     ```
   - If `closeSessionInternal(session)` or `closeAll()` is invoked while `socket.connect(...)` is blocking, `session.socket` is still `null`. `session.socket?.close()` does nothing. When `connect()` finishes, the connected socket is orphaned and never closed.

4. **Synchronized Lock Contention on `vpnOutput`**:
   - Line 432:
     ```kotlin
     synchronized(vpnOutput) {
         vpnOutput.write(packet)
         vpnOutput.flush()
     }
     ```
   - In `runPacketLoop`, `TunTcpRelay`, and `TunUdpRelay`, every single packet (including ~46 chunks of 1400 bytes per 64KB read) acquires this monitor lock and calls `flush()`, leading to lock contention under multi-megabyte throughput.

5. **SYN De-duplication & Race Condition**:
   - Lines 133–177: Session insertion uses `sessions[sessionKey] = session` rather than `putIfAbsent` or atomic state updates, which could overwrite an active session if duplicate SYN packets arrive concurrently.

---

### 1.2 `TunUdpRelay` (`app/src/main/java/com/sourzap/app/service/core/TunUdpRelay.kt`)

1. **Blocking Socket Call on the Main TUN Loop**:
   - Lines 111–113:
     ```kotlin
     val sendPacket = DatagramPacket(payload, payload.size, dstIp, dstPort)
     socket.send(sendPacket)
     ```
   - `handleUdpPacket` is called directly on the TUN reader thread in `SourZapVpnService.runPacketLoop`. `socket.send` is a blocking syscall. If the socket send buffer fills under heavy UDP saturation, the entire TUN packet reading loop freezes, blocking all TCP, UDP, and DNS traffic.

2. **O(N) Linear Scan in NAT Table on Port Mismatch**:
   - Lines 148–150:
     ```kotlin
     val client = natTable[natKeyExact]
         ?: natTable[natKeyHost]
         ?: natTable.entries.firstOrNull { it.key.startsWith("${remoteAddress.hostAddress}:") }?.value
     ```
   - When a remote host responds from a different port (common in STUN, uTP, and DHT), the receiver performs a full linear scan over up to 4,096 entries in `natTable` with string prefix matching on every single received UDP packet.

3. **NAT Host-Key Collision**:
   - Lines 107–108:
     ```kotlin
     natTable[natKeyExact] = mapping
     natTable[natKeyHost] = mapping
     ```
   - Multiple local applications communicating with the same remote host IP on different ports overwrite each other's `natKeyHost` entry on the same socket index.

---

### 1.3 `ByteArrayPool` (`app/src/main/java/com/sourzap/app/service/core/ByteArrayPool.kt`)

1. **Pool Counter Tracking Asynchrony**:
   - Lines 48–53, 58–66:
     ```kotlin
     fun obtain64k(): ByteArray {
         val buf = pool64k.poll()
         if (buf != null) {
             count64k.decrementAndGet()
             return buf
         }
         return ByteArray(BUFFER_64K)
     }
     ```
   - `pool.poll()` and `count.decrementAndGet()` are non-atomic. Under high concurrency, counter drift can occur, especially if `clear()` resets the count while a decrement is in flight.

2. **Tiered Allocations**:
   - Supported tiers: 4 KB (`BUFFER_4K`), 16 KB (`BUFFER_16K`), 32 KB (`BUFFER_32K`), and 64 KB (`BUFFER_64K`).
   - `obtain(size)` accurately routes to tiers, but callers must strictly use sliced copy offsets (`payloadOffset`, `payloadLen`) because pooled buffers retain previous packet bytes.

---

### 1.4 `LocalDpiProxyServer` (`app/src/main/java/com/sourzap/app/service/core/LocalDpiProxyServer.kt`)

1. **Bidirectional Stream Pump Deadlock & Socket Leak**:
   - Lines 311–356:
     ```kotlin
     clientJob = scope.launch(proxyDispatcher) { ... upstreamSocket.shutdownOutput() ... }
     upstreamJob = scope.launch(proxyDispatcher) { ... clientSocket.shutdownOutput() ... }
     clientJob.join()
     upstreamJob.join()
     ```
   - If the client disconnects, `clientJob` exits and shuts down upstream output. If the remote upstream server does not close its connection, `upstreamJob` remains blocked in `upstreamIn.read(buf)` indefinitely.
   - `upstreamJob.join()` hangs forever, preventing `handleClientConnection` from entering its `finally` block to close `clientSocket` and `upstreamSocket`.
   - Both sockets, coroutines, and 128KB of pooled buffers remain permanently leaked.

2. **Per-Connection Heap Buffer Allocations**:
   - Lines 84–86: `val headerBuffer = ByteArray(8192)` and `val tempBuf = ByteArray(1024)` are allocated on the JVM heap for every incoming proxy request instead of using `ByteArrayPool`.

---

### 1.5 `SourZapVpnService` & `DohResolver`

1. **`DohResolver.queryUdpDns` Socket Descriptor Leak**:
   - `app/src/main/java/com/sourzap/app/service/core/DohResolver.kt` (lines 271–291):
     ```kotlin
     private fun queryUdpDns(queryBytes: ByteArray, serverIp: String): ByteArray? {
         try {
             val socket = DatagramSocket()
             vpnServiceRef?.protect(socket)
             socket.soTimeout = 1500
             val sendPacket = DatagramPacket(...)
             socket.send(sendPacket)
             val buf = ByteArray(4096)
             val recvPacket = DatagramPacket(buf, buf.size)
             socket.receive(recvPacket)
             val len = recvPacket.length
             socket.close() // <-- SKIPPED ON TIMEOUT OR CANCELLATION
             ...
         } catch (_: Exception) {}
         return null
     }
     ```
   - `socket.close()` is placed after `socket.receive(recvPacket)` without a `finally` block or `socket.use { ... }`.
   - In `executeParallelDnsQuery`, 8 UDP queries are dispatched concurrently. The moment 1 query wins, `cancelChildren()` cancels the remaining 7 coroutines.
   - Every cancelled or timed-out UDP query fails to reach `socket.close()`, leaking up to 7 `DatagramSocket` file descriptors per DNS resolution.

2. **Unbounded DNS Query Coroutine Dispatching**:
   - `SourZapVpnService.processPacket` (lines 285–315):
     ```kotlin
     if (dstPort == 53 && udpPayloadLen > 0) {
         val queryBytes = buffer.copyOfRange(udpPayloadOffset, udpPayloadOffset + udpPayloadLen)
         serviceScope.launch {
             val responseWire = DohResolver.resolveWireQuery(queryBytes, strategy.dohProvider)
             ...
         }
     }
     ```
   - Every DNS query creates a detached coroutine on `serviceScope`. A burst of DNS queries (e.g. from BitTorrent DHT or tracker lookups) can launch hundreds of simultaneous coroutines without queue bounds.

---

## 2. Logic Chain

```
[Observation 1.1.1: CachedThreadPool on TunTcpRelay] + [Observation 1.4.1: CachedThreadPool on LocalDpiProxyServer]
  └──> Step 1: Thread count scales linearly (2x-3x) with active connections.
  └──> Step 2: High connection counts (BitTorrent swarms / video streaming) create hundreds of blocked OS threads.
  └──> Inference: Risk of pthread_create failure and kernel thread starvation on low-memory Android devices.

[Observation 1.1.2: Channel.UNLIMITED in TcpSession] + [Observation 1.2.1: Blocking socket.send on TUN loop]
  └──> Step 1: Client data buffers enqueue indefinitely without backpressure when upstream is slow or connecting.
  └──> Step 2: TUN packet loop blocks synchronously on DatagramSocket.send if kernel UDP buffer is full.
  └──> Inference: Heap exhaustion under upload saturation, and packet processing stalls for all protocols.

[Observation 1.1.3: session.socket assigned AFTER connect()] + [Observation 1.5.1: queryUdpDns missing finally/use] + [Observation 1.4.1: pumpBidirectional deadlock on join()]
  └──> Step 1: Early RST or cancel leaves connecting TCP socket unreferenced and open.
  └──> Step 2: Parallel DNS queries cancel losing UDP tasks, skipping socket.close().
  └──> Step 3: Half-closed proxy stream blocks on upstreamIn.read(), preventing cleanup.
  └──> Inference: Severe file descriptor leaks and orphaned sockets accumulating over time.

[Observation 1.2.2: O(N) NAT scan] + [Observation 1.1.4: synchronized(vpnOutput) + flush per segment]
  └──> Step 1: Every mismatched incoming UDP packet linearly iterates 4096 NAT entries.
  └──> Step 2: Every 1400-byte chunk acquires monitor lock and triggers kernel flush.
  └──> Inference: Severe CPU throttling, lock contention, and dropped packets under high throughput.
```

---

## 3. Caveats
- **Platform Emulation vs Real Device**: In JVM unit tests (`testDebugUnitTest`), file descriptor limits (`ulimit -n` or Android's 1024 limit) are not strictly enforced by the OS. Sockets leaked in tests will not fail unit tests immediately unless explicitly asserted or under extreme stress.
- **VpnService `protect()` in Unit Tests**: `VpnService.protect()` is an Android framework method that cannot bind to real sockets in pure local JVM tests without Robolectric or mocking.
- **NIO vs Blocking Sockets**: Android's `VpnService` TUN interface operates as a Linux `ParcelFileDescriptor` character device (tun0), which responds well to blocking streams or dedicated worker threads if coroutine dispatching and channel buffers are strictly bounded.

---

## 4. Conclusion & Recommended Action Plan

### 4.1 Required Fixes for R1 Hardening

1. **`TunTcpRelay` Hardening**:
   - Replace `newCachedThreadPool` with a bounded dispatcher (e.g. `Dispatchers.IO.limitedParallelism(64)` or fixed-size IO worker pool).
   - Change `sendQueue` from `Channel(Channel.UNLIMITED)` to a bounded buffer: `Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)` or implement backpressure window management.
   - Assign `session.socket = socket` immediately before `socket.connect()` inside a `try-finally` block so that teardown during connection immediately closes the socket.
   - Separate half-close read draining from abrupt connection resets.
   - Use `sessions.putIfAbsent` to prevent SYN race overwrites.

2. **`TunUdpRelay` Hardening**:
   - Move `socket.send(sendPacket)` out of the TUN packet loop thread into a non-blocking bounded channel/worker loop.
   - Replace the O(N) linear scan in `runReceiverLoop` with an O(1) secondary lookup table indexed by host IP.
   - Avoid host-key collisions by scoping fallback mappings per remote host/socket.

3. **`ByteArrayPool` Hardening**:
   - Ensure pool acquisition and recycling maintain atomic invariants.
   - Integrate `ByteArrayPool` into `LocalDpiProxyServer` request header buffering.

4. **`LocalDpiProxyServer` Hardening**:
   - Fix `pumpBidirectional` by adding cooperative cancellation: if either `clientJob` or `upstreamJob` completes or throws an exception, immediately cancel the peer job and close both sockets.
   - Add a read timeout (`soTimeout = 15000`) on client and upstream sockets to prevent zombie connections.
   - Pool header buffers using `ByteArrayPool.obtain16k()`.

5. **`DohResolver` & `SourZapVpnService` Hardening**:
   - Refactor `DohResolver.queryUdpDns` to use `DatagramSocket().use { socket -> ... }` to ensure all 7 losing DNS racer sockets are closed immediately upon cancellation.
   - Add a bounded channel/queue for UDP port 53 DNS packet handling in `SourZapVpnService`.
   - Remove redundant `vpnOutput.flush()` calls inside `synchronized(vpnOutput)`.

---

## 5. Verification Method

1. **Automated Unit Tests**:
   - Run:
     ```powershell
     .\gradlew.bat testDebugUnitTest
     ```
   - Target test classes:
     - `com.sourzap.app.DohResolverTest`
     - `com.sourzap.app.PacketParserTest`
     - `com.sourzap.app.DpiEngineTest`
     - `com.sourzap.app.TrafficStatsTest`

2. **New Test Coverage to Add for R1**:
   - `TunTcpRelayTest`: Test rapid SYN burst de-duplication, bounded queue saturation without OOM, and early RST socket teardown.
   - `TunUdpRelayTest`: Test NAT table O(1) lookups under 4096 entries, port mismatch routing, and non-blocking packet forwarding.
   - `DohResolverLeakTest`: Test 100 concurrent parallel DNS races and verify zero unclosed `DatagramSocket` leaks.
   - `LocalDpiProxyServerPumpTest`: Test half-close and abrupt upstream disconnect in bidirectional pump.

3. **Release Compilation Check**:
   - Run:
     ```powershell
     .\gradlew.bat assembleRelease
     ```
   - Invalidation Condition: Any unhandled exception, leaking socket file descriptor, thread overflow, or failed test execution.
