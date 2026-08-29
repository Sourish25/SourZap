<p align="center">
  <img src="docs/assets/sourzap_icon.png" width="96" height="96" alt="SourZap Icon" />
</p>

<h1 align="center">SourZap</h1>

<p align="center">
  Rootless DPI circumvention and network monitor for Android, built with Material 3 Expressive.
</p>

<p align="center">
  <a href="https://github.com/Sourish25/SourZap/releases"><img src="https://img.shields.io/github/v/release/Sourish25/SourZap?style=flat-square" alt="Latest Release" /></a>
  <a href="https://github.com/Sourish25/SourZap/actions"><img src="https://img.shields.io/github/actions/workflow/status/Sourish25/SourZap/ci.yml?branch=main&style=flat-square" alt="Build Status" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0+-green.svg?style=flat-square" alt="Android 8.0+" />
</p>

---

## Overview

SourZap is an Android utility designed to bypass Deep Packet Inspection (DPI) throttling and censorship on mobile networks and Wi-Fi. It brings packet desynchronization techniques inspired by **Zapret** and **ByeDPI** directly to non-rooted Android devices through a local `VpnService` interface.

Many telecom providers and middleboxes inspect initial TLS `ClientHello` packets to identify destination Server Name Indications (SNI) or HTTP `Host` headers, throttling video streams (e.g. YouTube CDN endpoints) or blocking services like Discord voice RTC gateways. SourZap intercepts and fragments these initial handshake frames in user-space, preventing middlebox detection while allowing the remote server to reassemble the stream normally.

---

## How It Works

```
[ App Traffic ] ──> [ Local TUN (10.0.0.2) ] ──> [ SourZap Engine ]
                                                          │
   ┌──────────────────────────────────────────────────────┴──────────────────────────────────────────────────────┐
   │                                                                                                             │
   ▼                                                      ▼                                                      ▼
[ DNS over HTTPS ]                               [ Initial Handshake ]                                   [ Steady-State Flow ]
Resolves via Cloudflare / Google / Quad9         Applies SNI split, fake TTL packets, or TCP disorder     Zero-copy 64KB stream pump
to bypass DNS poisoning                          to desync DPI middlebox trackers                        running at full line speed
```

1. **Local VPN Tunnel**: Directs device traffic through an internal virtual network interface (`10.0.0.2/24`). No external VPN server is required—all processing occurs locally on the device.
2. **Targeted Handshake Desynchronization**: DPI evasion runs only on initial TLS ClientHello or HTTP request packets:
   - **TLS SNI Splitting**: Splits the ClientHello into two or more TCP segments right at the SNI boundary (`TCP_NODELAY`), evading shallow inspection.
   - **Fake SNI / Low-TTL Injection**: Sends an initial dummy ClientHello with a low Time-To-Live (TTL) that expires before reaching the destination server, poisoning the DPI state cache.
   - **TCP Disorder**: Delivers the second segment before the first; standard OS TCP stacks reassemble it seamlessly, while middleboxes fail to reconstruct the payload.
   - **QUIC / UDP 443 Policy**: Blocks UDP QUIC packets to force immediate fallback to TCP, where packet desynchronization is effective.
3. **High-Throughput Fast Path**: Once the handshake completes, traffic switches to a zero-copy data pump using a `ByteArrayPool` and 512KB socket buffers, maintaining full line speed (>500 Mbps) with low CPU usage and minimal GC overhead.

---

## Presets

| Strategy | Description | Best For |
|---|---|---|
| **YouTube Turbo** | Fake SNI (`www.google.com`) + SNI Splitting + TCP Disorder + QUIC Blocking | Restoring unthrottled 1080p/4K playback on ISPs throttling Google Video CDNs |
| **Discord Fix** | TLS Split (Position 2) + TCP Disorder + Cloudflare DoH | Resolving Discord voice RTC connectivity, WebSockets, and API blocks |
| **Universal Bypass** | Multisplit + Low-TTL Fake Packets + HTTP Host Casing Desync | General anti-censorship across filtered websites |
| **Aggressive** | Multi-segment TLS fragmentation + Out-of-Band (OOB) bytes | Heavily restricted networks with strict stateful firewalls |
| **Custom** | Interactive configuration of split offset, fake SNI host, TTL, and DoH provider | Power users and network debugging |

---

## Features

- **Rootless & Standalone**: Operates entirely in user-space without root, Magisk, or Termux.
- **Built-in Speed Test**: Multi-stream broadband benchmark measuring Ping, Jitter, Download, and Upload speeds.
- **Real-Time Traffic Monitor**: Live upload/download throughput meters, animated bandwidth waveform, and connection inspector logging.
- **Encrypted DNS**: Integrated DNS-over-HTTPS (DoH) supporting Cloudflare, Google, Quad9, and AdGuard.
- **Material 3 Expressive Design**: Tactile controls, responsive layouts, dynamic color palettes, and adaptive icons with Android 13+ themed icon support.

---

## Getting Started

### Download
Pre-built APKs are available on the [Releases page](https://github.com/Sourish25/SourZap/releases).

1. Download the latest `SourZap-vX.X.X.apk`.
2. Install it on any Android device running Android 8.0 (API 26) or higher.
3. Open the app, select a preset (e.g. *YouTube Turbo*), and tap the hero connect button.
4. Grant the system VPN connection prompt when prompted.

### Building from Source

```bash
# Clone the repository
git clone https://github.com/Sourish25/SourZap.git
cd SourZap

# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Output APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Contributing

Contributions are welcome. If you find a DPI evasion combination that works well for a specific ISP or region, feel free to submit a preset proposal.

1. Fork the repository and create a feature branch (`git checkout -b feat/my-improvement`).
2. Verify tests pass locally: `./gradlew testDebugUnitTest`.
3. Open a Pull Request against the `main` branch.

Please review [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before opening a PR.

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.