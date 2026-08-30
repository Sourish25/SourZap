# SourZap

Rootless Deep Packet Inspection (DPI) Circumvention, DNS-over-HTTPS & Network Monitor for Android, built with Material 3 Expressive.

[![Latest Release](https://img.shields.io/github/v/release/Sourish25/SourZap?style=flat-square)](https://github.com/Sourish25/SourZap/releases)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg?style=flat-square)](https://github.com/Sourish25/SourZap/releases)
[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0+-green.svg?style=flat-square)](https://developer.android.com)

---

## Overview

SourZap is an advanced Android utility engineered to bypass Deep Packet Inspection (DPI) censorship, streaming throttling, and network filtering on mobile (LTE/5G) and Wi-Fi networks. It brings packet desynchronization techniques inspired by **Zapret** and **ByeDPI** directly to non-rooted Android devices through a local `VpnService` interface.

Many telecom providers and middleboxes inspect initial TLS `ClientHello` packets to identify destination Server Name Indications (SNI) or HTTP `Host` headers, throttling video streams (YouTube 4K/1080p, Twitch, Instagram Reels) or blocking services like Discord voice RTC gateways. SourZap intercepts and fragments these initial handshake frames in user-space, preventing middlebox detection while allowing remote servers to reassemble the stream normally.

---

## How It Works

```
[ App Traffic ] ──> [ Local TUN Interface (10.0.0.2 / fd00::1) ] ──> [ SourZap Core Engine ]
                                                                               │
   ┌───────────────────────────────────────────────────────────────────────────┴───────────────────────────────────────────────────────────────────────────┐
   │                                                                           │                                                                           │
   ▼                                                                           ▼                                                                           ▼
[ 0ms LRU DNS Cache + Multi-DoH ]                           [ Automated Handshake Desync ]                                      [ Steady-State Data Pump ]
Thread-safe LRU Cache + Singleflight                        Applies TLS Split2, HTTP Host Casing,                               Zero-allocation 4-tier buffer pool
Coalesced lookup across Cloudflare / Google / Quad9         TCP FIN/RST state synthesis, and ICMP QUIC reject                   8x UDP relay sockets for BitTorrent swarms
```

1. **Local VPN Tunnel**: Directs device traffic through an internal virtual network interface (`10.0.0.2/24`). No external VPN server is required—all processing occurs locally on the device with zero latency overhead.
2. **Universal Automated DPI Evasion**: Zero-configuration engine tuned automatically:
   - **TLS Record Header Split2**: Splits the ClientHello into two TCP segments right at the SNI boundary (`TCP_NODELAY`), evading shallow DPI inspection.
   - **HTTP Host Desynchronization**: Modifies HTTP Host header casing (`hOst: `) to fool legacy HTTP filters.
   - **Instant QUIC ICMP Rejection**: Synthesizes immediate ICMP Port Unreachable packets for UDP 443 QUIC attempts, accelerating fallback to unthrottled TCP in 0ms.
   - **IPv6 ICMPv6 Leak Prevention**: Synthesizes RFC 4443 ICMPv6 Destination Unreachable packets, triggering instant Happy Eyeballs fallback to IPv4 with zero DNS leaks.
3. **High-Throughput Layer-3 P2P BitTorrent Engine**:
   - Zero-allocation direct packet slicing in `TunTcpRelay` and `TunUdpRelay`.
   - 8-socket protected `DatagramSocket` pool with 2MB kernel buffers for simultaneous DHT peers and multi-tracker announces (`udp://...:1337`, `6969`).
   - Native BitTorrent handshake detection (`\x13BitTorrent protocol`) with raw passthrough.
4. **Dynamic Network Handover**:
   - Seamless Wi-Fi ↔ 5G roaming via `ConnectivityManager.NetworkCallback` with `setUnderlyingNetworks` to prevent connection drops.

---

## Features

- **Rootless & Standalone**: Operates entirely in user-space without root, Magisk, or Termux.
- **Material You Expressive UI**: Organic sinusoidal wavy progress lines, dynamic Monet palette extraction, edge-to-edge window insets, and 100% SVG vector iconography (zero emojis).
- **Comprehensive Speed Test**: Multi-stream broadband diagnostic benchmark measuring Ping, Jitter, Download Speed, Upload Speed, and Connection Stability with a dynamic Monet gradient speedometer gauge.
- **Enhanced Traffic Inspector**: Real-time throughput meters, animated protocol filter tabs (All Flows, HTTPS/TLS, DNS, BitTorrent, UDP), search/filtering, and diagnostic export.
- **0ms DNS-over-HTTPS (DoH)**: Thread-safe in-memory LRU DNS cache with 5-minute TTL, singleflight deduplication, and multi-bootstrap failover (Cloudflare, Google, Quad9, AdGuard).
- **In-App Direct Updater**: Automatic GitHub release updater with static CDN IP fallback to prevent ISP DNS blocking during updates.

---

## Getting Started

### Download Pre-Built APK
Download the latest signed release from the [GitHub Releases](https://github.com/Sourish25/SourZap/releases).

### Building from Source

```bash
# Clone the repository
git clone https://github.com/Sourish25/SourZap.git
cd SourZap

# Run complete test suite (83+ unit & adversarial tests)
./gradlew testDebugUnitTest

# Build signed release APK
./gradlew assembleRelease

# Output APK located at: app/build/outputs/apk/release/app-release.apk
```

---

## License

SourZap is licensed under the [MIT License](LICENSE).