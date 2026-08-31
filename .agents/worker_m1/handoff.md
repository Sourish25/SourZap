# Milestone M1 Handoff Report
**VPN Packet Relay & Socket Concurrency Hardening**

## 1. Observation
Across the 6 target files in Milestone M1, the following specific vulnerabilities were directly observed and resolved:

1. **`TunTcpRelay.kt` (`app/src/main/java/com/sourzap/app/service/core/TunTcpRelay.kt`)**:
   - *Unbounded Thread Pool*: `Executors.newCachedThreadPool` created unbounded threads (`SourZap-TunTcpWorker`) under concurrent load.
   - *Unbounded Heap Buffer*: `TcpSession.sendQueue` was configured as `Channel(Channel.UNLIMITED)`, allowing unpooled heap buffers to accumulate under upstream backpressure.
   - *Socket Descriptor Leak*: `session.socket = socket` occurred after blocking `socket.connect()`. If `closeSessionInternal` or cancellation ran while `connect()` was in flight, `session.socket` remained unreferenced and was never closed.
   - *SYN Race Condition*: Insertion used direct indexing `sessions[sessionKey] = session` without atomic deduplication for concurrent SYNs.
   - *Output Monitor Lock*: Redundant `vpnOutput.flush()` calls inside `synchronized(vpnOutput)` created lock contention on packet streaming.

2. **`TunUdpRelay.kt` (`app/src/main/java/com/sourzap/app/service/core/TunUdpRelay.kt`)**:
   - *Blocking Syscall on TUN Loop*: `handleUdpPacket` invoked blocking `socket.send(sendPacket)` directly on the TUN reader thread, halting the entire VPN packet pipeline if UDP socket send buffers backed up.
   - *O(N) Linear Table Scan*: In `runReceiverLoop`, incoming datagram port mismatches fell back to `natTable.entries.firstOrNull { it.key.startsWith(...) }` scanning up to 4096 entries on every packet.
   - *NAT Host Collisions*: `natTable[natKeyHost] = mapping` shared key format across different local client ports for the same remote destination host.
   - *Redundant Flush*: `vpnOutput.flush()` inside `runReceiverLoop` caused lock contention.

3. **`ByteArrayPool.kt` (`app/src/main/java/com/sourzap/app/service/core/ByteArrayPool.kt`)**:
   - In `obtain64k()`, `obtain32k()`, `obtain16k()`, `obtain4k()`, count decrementing used non-bounded `decrementAndGet()`, risking negative counter drift under concurrency.

4. **`LocalDpiProxyServer.kt` (`app/src/main/java/com/sourzap/app/service/core/LocalDpiProxyServer.kt`)**:
   - *Join Deadlock & Permanent Socket Leak*: `pumpBidirectional` launched `clientJob` and `upstreamJob` and called `clientJob.join()` and `upstreamJob.join()`. When the client disconnected, `upstreamIn.read(buf)` blocked indefinitely, causing `upstreamJob.join()` to hang forever and leaking both sockets and coroutines.
   - *Missing Read Timeouts*: Sockets used `soTimeout = 0`, allowing dead half-open connections to persist indefinitely.
   - *Unpooled Memory Allocations*: `ByteArray(8192)` and `ByteArray(1024)` were allocated on the heap for every incoming client connection.

5. **`DohResolver.kt` (`app/src/main/java/com/sourzap/app/service/core/DohResolver.kt`)**:
   - *Parallel DNS Socket Leak*: In `queryUdpDns`, `socket.close()` was executed after `socket.receive()` without a `try-finally` or `.use { ... }` block. When `executeParallelDnsQuery` received the first winning response and called `cancelChildren()`, the remaining losing UDP coroutines were cancelled while blocking in `receive()`, skipping `socket.close()` and leaking up to 7 `DatagramSocket` file descriptors per query.

6. **`SourZapVpnService.kt` (`app/src/main/java/com/sourzap/app/service/SourZapVpnService.kt`)**:
   - *Unbounded DNS Coroutine Dispatch*: Port 53 UDP DNS queries spawned detached coroutines on `serviceScope` without bounded concurrency or backpressure.
   - *Redundant Packet Flushes*: IPv6 ICMPv6 and QUIC ICMP rejection blocks executed redundant `vpnOutput.flush()` calls inside `synchronized(vpnOutput)`.

---

## 2. Logic Chain

```
[Observation 1: TunTcpRelay unbounded pool & UNLIMITED channel]
  └──> Step 1: High concurrent connection bursts (e.g. BitTorrent swarms) instantiate hundreds of OS threads and unbounded byte arrays in memory.
  └──> Resolution 1: Replaced cached pool with bounded Dispatchers.IO.limitedParallelism(64) and bounded sendQueue Channel(64, BufferOverflow.DROP_OLDEST).

[Observation 2: TunTcpRelay socket assigned post-connect]
  └──> Step 2: Connection teardown during connect() leaves socket unclosed.
  └──> Resolution 2: Assigned localSocket and session.socket immediately before connect(), added pre- and post-connect cancellation checks, and closed localSocket in catch blocks. Added atomic sessions.putIfAbsent to eliminate SYN races.

[Observation 3: TunUdpRelay blocking send on TUN thread & O(N) NAT scan]
  └──> Step 3: Kernel UDP send buffer backpressure blocks TUN loop; linear prefix search burns CPU cycles on every mismatched response.
  └──> Resolution 3: Moved outgoing UDP datagrams to a non-blocking bounded sendChannel (capacity 1024, DROP_OLDEST) with dedicated coroutine workers. Separated NAT table into exactNatTable (RemoteIP:RemotePort#SocketIndex) and hostNatTable (RemoteIP#SocketIndex) achieving O(1) lookup.

[Observation 4: LocalDpiProxyServer join() deadlock on stream half-close]
  └──> Step 4: When one stream terminates, peer stream blocks in read(), hanging join() and preventing socket closure in finally.
  └──> Resolution 4: Added cooperative cancellation in pumpBidirectional (each stream cancels peer job and closes peer socket on exit). Configured soTimeout = 15000 (15s) and integrated ByteArrayPool.obtain16k()/obtain4k() for header buffers.

[Observation 5: DohResolver DatagramSocket leak in cancelled racing tasks]
  └──> Step 5: executeParallelDnsQuery cancels losing tasks via cancelChildren(), skipping un-guarded socket.close().
  └──> Resolution 5: Refactored queryUdpDns to use DatagramSocket().use { socket -> ... }, guaranteeing immediate socket closure on cancellation or timeout.

[Observation 6: SourZapVpnService unbounded DNS coroutine bursts]
  └──> Step 6: Burst of DNS queries spawned unlimited coroutines.
  └──> Resolution 6: Added bounded dnsChannel (capacity 256, DROP_OLDEST) with a fixed 16-worker pool and removed redundant vpnOutput.flush() calls.
```

---

## 3. Caveats
- No caveats. All 6 files have been hardened according to their interface contracts and validated against JVM unit tests and release compilation.

---

## 4. Conclusion
Milestone M1 (VPN Packet Relay & Socket Concurrency Hardening) is completely implemented:
- Thread safety and bounded concurrency enforced across TCP, UDP, DNS, and Proxy modules.
- Socket file descriptor leaks during connect, parallel DNS racing, and proxy half-close eliminated.
- O(1) dual NAT table replaces O(N) scans.
- Zero-allocation tiered buffer pooling with atomic accounting enforced.

---

## 5. Verification Method
1. **Automated Unit Tests**:
   Command:
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
   Result: `BUILD SUCCESSFUL` (100% tests passed).

2. **Release APK Compilation**:
   Command:
   ```powershell
   .\gradlew.bat assembleRelease
   ```
   Result: `BUILD SUCCESSFUL`.
