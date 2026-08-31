# BRIEFING — 2026-08-31T09:22:00Z

## Mission
Investigate and design binary-safe HttpParser.desyncHttpPayload and LocalDpiProxyServer URI/IPv6 normalization for SourZap Milestone M2 (BitTorrent & P2P DPI Evasion Resilience).

## 🔒 My Identity
- Archetype: explorer
- Roles: investigator, reporter, architect
- Working directory: c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m2_2
- Original parent: 6ba0370e-161e-4bb4-a25d-c070b4d3a742
- Milestone: M2

## 🔒 Key Constraints
- Read-only investigation — do NOT implement in production source files directly.
- Binary-safe HttpParser desync without decoding binary body to String.
- LocalDpiProxyServer robust URI / tracker unescaped byte parsing & IPv6 host normalization without exceptions.

## Current Parent
- Conversation ID: 6ba0370e-161e-4bb4-a25d-c070b4d3a742
- Updated: 2026-08-31T09:22:00Z

## Investigation State
- **Explored paths**: HttpParser.kt, LocalDpiProxyServer.kt, DohResolver.kt, DpiEngine.kt, DpiEngineTest.kt, M1EmpiricalChallengeTest.kt
- **Key findings**:
  1. `HttpParser.desyncHttpPayload` converted full buffer to `US_ASCII` string, corrupting bytes >= 0x80 in binary bodies. Designed `findHeaderBoundary` + `ISO_8859_1` header slice isolation + raw byte body preservation.
  2. `LocalDpiProxyServer.kt` used `replaceFirst(firstLine, ...)` which caused `PatternSyntaxException` on IPv6 `[` brackets and regex chars in tracker URLs. Also `java.net.URI` threw `URISyntaxException` on unescaped query bytes. Designed deterministic `normalizeUriPath`, `parseHostAndPort`, `isIpLiteral`, and literal string concatenation.
- **Unexplored areas**: None for this assignment scope.

## Key Decisions Made
- Use `Charsets.ISO_8859_1` for lossless 1:1 byte-to-char header parsing.
- Implement header boundary scanner for `\r\n\r\n`, `\n\n`, `\r\n\n`, `\n\r\n`.
- Extract path and query using direct string indexing without `java.net.URI`.
- Support bracketed IPv6, unbracketed IPv6, and IPv4 authority formats.

## Artifact Index
- handoff.md — Complete 5-component handoff report for M2.2
