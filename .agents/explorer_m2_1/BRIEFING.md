# BRIEFING — 2026-08-31T09:20:50Z

## Mission
Investigate BitTorrent handshake detection, BT_SPLIT DPI evasion strategy with TCP_NODELAY, and fragmented handshake buffering in TunTcpRelay.kt for Milestone M2.

## 🔒 My Identity
- Archetype: explorer
- Roles: investigation, analysis, synthesis
- Working directory: c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m2_1\
- Original parent: 6ba0370e-161e-4bb4-a25d-c070b4d3a742
- Milestone: M2 (BitTorrent & P2P DPI Evasion Resilience)

## 🔒 Key Constraints
- Read-only investigation — do NOT implement in production source code directly
- Adhere strictly to project architecture in PROJECT.md and ORIGINAL_REQUEST.md
- Produce comprehensive handoff report at .agents/explorer_m2_1/handoff.md

## Current Parent
- Conversation ID: 6ba0370e-161e-4bb4-a25d-c070b4d3a742
- Updated: 2026-08-31T09:20:50Z

## Investigation State
- **Explored paths**:
  - `app/src/main/java/com/sourzap/app/service/core/DpiEngine.kt`
  - `app/src/main/java/com/sourzap/app/service/core/TunTcpRelay.kt`
  - `app/src/main/java/com/sourzap/app/service/core/TlsParser.kt`
  - `app/src/main/java/com/sourzap/app/service/core/HttpParser.kt`
  - `app/src/main/java/com/sourzap/app/data/model/BypassStrategy.kt`
  - `app/src/test/java/com/sourzap/app/DpiEngineTest.kt`
  - `app/src/test/java/com/sourzap/app/M1EmpiricalChallengeTest.kt`
- **Key findings**:
  1. `DpiEngine.kt` currently performs a naive 5-byte check for BitTorrent and passes it through directly (`BITTORRENT_PASSTHROUGH`), leaving traffic vulnerable to ISP DPI throttling.
  2. Implementing `BT_SPLIT(1)` (1 byte `[0x13]` followed by 67 bytes) or `BT_SPLIT(2)` (`[0x13, 'B']` followed by 66 bytes) with `socket.tcpNoDelay = true` breaks DPI state machines across packet boundaries while enabling remote peers to cleanly reassemble standard BEP 3 handshakes.
  3. `TunTcpRelay.kt` currently processes chunks individually in `senderJob`, immediately setting `isHandshakeDesynced = true` on the first chunk. If a ClientHello or BitTorrent handshake is fragmented across TCP segments, DPI desynchronization is bypassed on subsequent chunks.
  4. Multi-chunk buffering with `MAX_HANDSHAKE_BUFFER_SIZE = 4096`, `HANDSHAKE_BUFFER_TIMEOUT_MS = 150L`, and protocol completion predicates (`isHandshakeComplete`) solves the fragmentation bypass with zero added latency for non-DPI protocols.
- **Unexplored areas**: None. Investigation complete and fully synthesized.

## Key Decisions Made
- Fully specified `DpiEngine.isBitTorrentHandshake`, `applyBitTorrentDesync`, and `BT_SPLIT(1)` / `BT_SPLIT(2)`.
- Fully specified `TunTcpRelay.isHandshakeComplete` and timeout-bounded sequential buffer aggregation in `senderJob`.
- Outlined exhaustive unit and empirical test cases.

## Artifact Index
- c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m2_1\DISPATCH.md — Initial dispatch message
- c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m2_1\BRIEFING.md — Working memory
- c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m2_1\progress.md — Liveness and task progress
- c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m2_1\handoff.md — Final handoff report
