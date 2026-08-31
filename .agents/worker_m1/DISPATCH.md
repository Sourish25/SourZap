## 2026-08-31T07:46:21Z

You are a Worker subagent for Milestone M1 (VPN Packet Relay & Socket Concurrency Hardening) in the SourZap refinement project.

Your working directory: c:\Users\Sourish\Desktop\SourZap\.agents\worker_m1
Project root: c:\Users\Sourish\Desktop\SourZap
Authoritative User Request: c:\Users\Sourish\Desktop\SourZap\.agents\ORIGINAL_REQUEST.md
Survey Analysis & Recommendations: c:\Users\Sourish\Desktop\SourZap\.agents\explorer_survey_r1\handoff.md
Project Plan: c:\Users\Sourish\Desktop\SourZap\PROJECT.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

EXCLUSIVE WRITE OWNERSHIP:
- app/src/main/java/com/sourzap/app/service/core/TunTcpRelay.kt
- app/src/main/java/com/sourzap/app/service/core/TunUdpRelay.kt
- app/src/main/java/com/sourzap/app/service/core/ByteArrayPool.kt
- app/src/main/java/com/sourzap/app/service/core/LocalDpiProxyServer.kt
- app/src/main/java/com/sourzap/app/service/core/DohResolver.kt
- app/src/main/java/com/sourzap/app/service/core/SourZapVpnService.kt

TASKS FOR MILESTONE M1:
1. `TunTcpRelay.kt`:
   - Replace unbounded `Executors.newCachedThreadPool` with a bounded coroutine dispatcher (e.g. `Dispatchers.IO.limitedParallelism(64)` or bounded thread pool) to prevent OS thread explosion under high connection counts.
   - Replace `sendQueue: Channel<ByteArray> = Channel(Channel.UNLIMITED)` with a bounded channel (`Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)` or bounded backpressure) to prevent JVM heap exhaustion under saturation.
   - Fix socket descriptor leak on early teardown / RST during connect: ensure socket reference is tracked safely before blocking connect and closed immediately if session is torn down or aborted.
   - Optimize `vpnOutput` synchronization: avoid redundant `vpnOutput.flush()` calls inside loops.
   - Use `sessions.putIfAbsent` or atomic updates to prevent duplicate SYN races.
2. `TunUdpRelay.kt`:
   - Move blocking `socket.send` out of the main TUN reader loop thread into a non-blocking queue/coroutine worker so that UDP send buffer backpressure never blocks the TUN reader loop.
   - Replace the O(N) linear scan in `natTable` with collision-free O(1) table lookup: separate primary exact match table (`RemoteIP:RemotePort#SocketIndex`) and secondary host table (`RemoteIP#SocketIndex`) without linear scans and without host key collisions across ports.
   - Ensure NAT table capacity and LRU pruning are robust and thread-safe.
3. `ByteArrayPool.kt`:
   - Ensure atomic count tracking and zero-allocation safety.
4. `LocalDpiProxyServer.kt`:
   - Fix `pumpBidirectional` deadlock: implement cooperative coroutine cancellation such that if either `clientJob` or `upstreamJob` completes or throws an exception, immediately cancel the peer job and close both sockets in `finally`.
   - Add `soTimeout = 15000` (15s) on client and upstream sockets to prevent zombie connections.
   - Use `ByteArrayPool` for header buffers instead of unpooled allocations.
5. `DohResolver.kt`:
   - Refactor `queryUdpDns` with `DatagramSocket().use { socket -> ... }` or `try-finally` to ensure cancelled or timed-out UDP queries in parallel DNS races always close their sockets immediately.
   - Add bounded queue/concurrency for port 53 DNS queries.
