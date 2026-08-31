## 2026-08-31T09:18:22Z

You are explorer_m2_2 for SourZap Milestone M2 (BitTorrent & P2P DPI Evasion Resilience).
Your working directory is: c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m2_2\
The project root is: c:\Users\Sourish\Desktop\SourZap
Authoritative User Request: c:\Users\Sourish\Desktop\SourZap\.agents\ORIGINAL_REQUEST.md
Project architecture & features: c:\Users\Sourish\Desktop\SourZap\PROJECT.md

Your Task:
Investigate and produce a detailed, actionable exploration report for:
1. Binary-Safe `HttpParser.desyncHttpPayload`:
   - Inspect `HttpParser.kt` (especially `desyncHttpPayload`, `splitHttpHeader`, etc.).
   - Current code might convert byte arrays to US-ASCII strings, modify headers, and convert back, corrupting binary payloads (e.g. POST request bodies, gzip responses, binary tracker announces).
   - Design an in-place byte-level or header-boundary-aware modification that isolates the header section (`\r\n\r\n` or `\n\n`), modifies header lines/casing in-place or via byte slices, and preserves the exact binary body bytes without string decoding corruption.
2. `LocalDpiProxyServer.kt` URI and IPv6 Normalization:
   - Inspect `LocalDpiProxyServer.kt` request line parsing, CONNECT handling, and HTTP proxy URL handling.
   - Investigate how unescaped raw bytes in tracker URLs (e.g., binary info_hash in `GET /announce?info_hash=%12%34...` or unencoded bytes) and IPv6 bracketed hosts (e.g., `[2001:db8::1]:8080` vs `[2001:db8::1]`) are parsed and normalized without throwing URI parsing / IllegalArgumentException exceptions.

Output:
Write your complete, self-contained report to `c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m2_2\handoff.md` with:
- Observation (code analysis, current implementation details)
- Proposed Implementation Plan with exact function signatures and logic
- Corner cases, edge cases, and failure modes
- Verification method and test strategy
Then send a message back to the orchestrator.
