## 2026-08-31T07:42:00Z
Task: Explore and map out the R2 requirement: BitTorrent & P2P DPI Evasion Resilience.
Specifically investigate:
1. `DpiEngine`, `PacketParser`, DPI evasion rules, protocol parsers (HTTP, TLS, BitTorrent, DHT, etc.).
2. Resilience against ISP deep packet inspection and non-standard tracker responses.
3. Graceful fallback on fragmented handshakes, UDP DHT bursts, and multi-peer TCP stream splitting.
4. Edge cases in packet parsing (malformed lengths, truncated packets, boundary offsets, out-of-order chunks), evasion logic correctness, and zero-exception handling.
