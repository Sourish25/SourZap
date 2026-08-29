---
name: Regional DPI Preset Proposal
about: Propose a new DPI circumvention preset for your ISP / Region
title: '[PRESET] '
labels: preset, dpi
assignees: ''
---

**Target Region & ISP:**
- Country: [e.g. Russia, Iran, Turkey, etc.]
- ISP / Carrier: [e.g. Rostelecom, MTS, Beeline, etc.]
- Blocked / Throttled Service: [e.g. YouTube 4K, Discord RTC, Twitch]

**Tested Strategy Parameters:**
- TLS Split Offset: [e.g. SNI Start, Pos 2]
- Fake SNI Host: [e.g. www.google.com, cloudflare.com]
- Fake TTL: [e.g. 3, 4]
- TCP Disorder: [Yes / No]
- DoH Provider: [Cloudflare / Google / Quad9]
- Block QUIC: [Yes / No]

**Verification Results:**
Describe how this preset improves latency, resolves throttling, or unblocks the target service.