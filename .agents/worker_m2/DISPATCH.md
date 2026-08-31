## 2026-08-31T09:22:32Z
Worker M2 Dispatch:
Task: Implement M2 (BitTorrent & P2P DPI Evasion Resilience)
Files:
- DpiEngine.kt (BitTorrent handshake detection, BT_SPLIT(1)/BT_SPLIT(2))
- TunTcpRelay.kt (Fragmented handshake buffering, 0ms non-DPI passthrough)
- HttpParser.kt (Binary-Safe HttpParser, ISO_8859_1 header boundary, untouched body byte slice)
- LocalDpiProxyServer.kt (Eliminate regex replaceFirst, IPv6/tracker URI normalization, parseHostAndPort)
- PacketParser.kt (Zero-exception hardening, dual-stack checksums, packet synthesizers)
- Unit tests in app/src/test/java/com/sourzap/app/
