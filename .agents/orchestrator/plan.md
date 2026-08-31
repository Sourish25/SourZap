# Orchestration Plan — SourZap Milestones M2, M3, M4

## Overview
Execute end-to-end implementation and verification of Milestones M2, M3, and M4 for SourZap according to `ORIGINAL_REQUEST.md` and `PROJECT.md`.

## Milestone Breakdown & Execution Flow

### Milestone M2: BitTorrent & P2P DPI Evasion Resilience
1. **Survey / Exploration**:
   - Spawn 3 parallel Explorers to analyze `DpiEngine.kt`, `TlsParser.kt`, `HttpParser.kt`, `PacketParser.kt`, `TunTcpRelay.kt`, and `LocalDpiProxyServer.kt`.
   - Explorer 1 (`explorer_m2_1`): BitTorrent handshake detection (`\x13BitTorrent protocol`), segment splitting at offset 1 or 2 with `TCP_NODELAY`, fragmented handshake buffering in `TunTcpRelay`.
   - Explorer 2 (`explorer_m2_2`): Binary-safe `HttpParser.desyncHttpPayload` in-place header modification without US-ASCII body corruption, and `LocalDpiProxyServer` URI path/query raw bytes & IPv6 bracketed host normalization.
   - Explorer 3 (`explorer_m2_3`): `PacketParser` zero-exception guarantees, boundary checking in `parseTcpHeader`, `parseUdpHeader`, checksum calculators, and packet builders.
2. **Synthesis & Worker Dispatch**:
   - Synthesize findings into unified execution specification.
   - Dispatch `worker_m2` to implement changes and verify with `./gradlew.bat testDebugUnitTest`.
3. **Verification & Audit Gate**:
   - Dispatch 2 independent Reviewers (`reviewer_m2_1`, `reviewer_m2_2`).
   - Dispatch 2 Challengers (`challenger_m2_1`, `challenger_m2_2`) to fuzz and stress-test DPI routines.
   - Dispatch Forensic Auditor (`auditor_m2`) for integrity verification.
   - Collect gate results.

### Milestone M3: UI State Lifecycle & Memory Leak Elimination
1. **Exploration**:
   - Spawn 3 Explorers (`explorer_m3_1`, `explorer_m3_2`, `explorer_m3_3`) to analyze `SpeedTestEngine.kt`, Compose screens (`DashboardScreen.kt`, `TrafficScreen.kt`, `SpeedTestScreen.kt`, `SettingsScreen.kt`), `UpdateManager.kt`, `TrafficMonitor.kt`, and Repositories.
2. **Worker Dispatch**:
   - Dispatch `worker_m3` to implement coroutine cancellation, lifecycle-aware collection (`collectAsStateWithLifecycle`), update download preservation, FIFO log bounds (50 items), and thread safety.
3. **Verification & Audit Gate**:
   - Dispatch 2 Reviewers, 2 Challengers, and 1 Forensic Auditor.
   - Evaluate gate criteria.

### Milestone M4: Automated Test Suite Expansion & Quality Assurance
1. **Exploration**:
   - Spawn 3 Explorers (`explorer_m4_1`, `explorer_m4_2`, `explorer_m4_3`) to survey test gaps across M1, M2, M3 components.
2. **Worker / Test Writer Dispatch**:
   - Dispatch Worker / Test Writer to implement comprehensive test suites covering all edge cases.
3. **Verification & Final QA**:
   - Dispatch 2 Reviewers, 2 Challengers, and Forensic Auditor.
   - Validate 100% `./gradlew.bat testDebugUnitTest` passing and `./gradlew.bat assembleRelease` BUILD SUCCESSFUL.

### Final Reporting
- Compile full victory summary and evidence report for the Sentinel and independent Victory Auditor.
