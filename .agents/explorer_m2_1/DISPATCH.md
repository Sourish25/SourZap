## 2026-08-31T09:18:22Z
You are explorer_m2_1 for SourZap Milestone M2 (BitTorrent & P2P DPI Evasion Resilience).
Your working directory is: c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m2_1\
The project root is: c:\Users\Sourish\Desktop\SourZap
Authoritative User Request: c:\Users\Sourish\Desktop\SourZap\.agents\ORIGINAL_REQUEST.md
Project architecture & features: c:\Users\Sourish\Desktop\SourZap\PROJECT.md

Your Task:
Investigate and produce a detailed, actionable exploration report for:
1. BitTorrent handshake detection:
   - Detect standard BitTorrent handshake (`\x13BitTorrent protocol...`, 68 bytes minimum: pstrlen 19, pstr "BitTorrent protocol", 8 reserved bytes, 20 info_hash, 20 peer_id).
   - In `DpiEngine.kt`, implement BitTorrent handshake detection and segment splitting strategy (`BT_SPLIT(1)` or `BT_SPLIT(2)`) with `TCP_NODELAY` enabled on the socket so the first 1 or 2 bytes are sent immediately before the remaining handshake bytes to desynchronize DPI state machines.
2. Fragmented handshake buffering in `TunTcpRelay.kt`:
   - Inspect how `TunTcpRelay.kt` currently handles incoming TCP payload segments for newly connected sessions.
   - When a client sends a TLS ClientHello or BitTorrent handshake across multiple small TCP segments (fragmented), how should `TunTcpRelay` buffer initial chunks until enough bytes are available to determine the protocol or apply DPI desync, rather than bypassing DPI desync on subsequent chunks?
   - Define exact buffering threshold, timeout/flushing policy, and memory limits.

Output:
Write your complete, self-contained report to `c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m2_1\handoff.md` with:
- Observation (code analysis, current implementation details)
- Proposed Implementation Plan with exact function signatures and logic
- Corner cases, edge cases, and failure modes
- Verification method and test strategy
Then send a message back to the orchestrator.
