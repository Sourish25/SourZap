# E2E Test Infra: SourZap BitTorrent Client

## Test Philosophy
- Opaque-box & requirement-driven verification derived from ORIGINAL_REQUEST.md.
- Multi-tier methodology: Feature Coverage (Tier 1), Boundary & Corner Cases (Tier 2), Cross-Feature Interactions (Tier 3), Real-World Scenarios (Tier 4), and Adversarial Hardening (Tier 5).

## Feature Inventory & Test Coverage Matrix
| # | Feature | Source | Tier 1 | Tier 2 | Tier 3 | Tier 4 |
|---|---------|--------|:------:|:------:|:------:|:------:|
| F1 | Binary-Safe Bencode Validation | R1 | 5 | 5 | ✓ | ✓ |
| F2 | HTML / Web-Blocker / Corrupt Payload Protection | R1 | 5 | 5 | ✓ | ✓ |
| F3 | Port-443 HTTPS Tracker Injection into TorrentInfo | R1 | 5 | 5 | ✓ | ✓ |
| F4 | Actionable Error Messages in UI & Engine | R1 | 5 | 5 | ✓ | ✓ |
| F5 | Fix Intent Fallback Dummy Payload | R1 | 5 | 5 | ✓ | ✓ |
| F6 | Filtered System File Picker | R2 | 5 | 5 | ✓ | ✓ |
| F7 | In-Dialog Downloads Torrent Scanner | R2 | 5 | 5 | ✓ | ✓ |
| F8 | In-Dialog Quick-Picker UI | R2 | 5 | 5 | ✓ | ✓ |
| F9 | Comprehensive Test Fixtures & Unit Tests | R3 | 5 | 5 | ✓ | ✓ |
| F10 | Scoped Storage & Integration Tests | R3 | 5 | 5 | ✓ | ✓ |
| F11 | 100% Automated Test Suite Pass Rate | Acceptance | ✓ | ✓ | ✓ | ✓ |
| F12 | Clean Signed Release APK Assembly | Acceptance | ✓ | ✓ | ✓ | ✓ |

## Test Architecture
- **Test Runner**: Gradle test runner (`testDebugUnitTest`) executing JVM-based tests with `isReturnDefaultValues = true`.
- **Test Command**: `powershell -Command ".\gradlew.bat testDebugUnitTest"`
- **Build Command**: `powershell -Command ".\gradlew.bat assembleRelease"`
- **Key Test Directories**:
  - `app/src/test/java/com/sourzap/app/torrent/` — Unit tests for torrent engine, validator, tracker injector, scanner.
  - `app/src/test/java/com/sourzap/app/e2e/` — Requirement-driven E2E test suites (Tier 1 through Tier 5).

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | Download multi-file torrent with Cloudflare redirect recovery | F1, F2, F3, F4 | High |
| 2 | Select `.torrent` from in-dialog Downloads quick-picker and initiate download | F6, F7, F8, F1, F3 | High |
| 3 | Handle Scoped Storage file streaming across app directories | F7, F10 | Medium |
| 4 | External Intent handling with BitTorrent MIME type matching | F5, F6, F1 | Medium |
| 5 | Full end-to-end add, pause, resume, prioritize, and tracker injection cycle | F3, F4, F10 | High |

## Coverage Thresholds
- Tier 1: ≥5 per feature
- Tier 2: ≥5 per feature (boundary, corrupted, empty, encoding edge cases)
- Tier 3: Pairwise coverage of major feature interactions
- Tier 4: ≥5 realistic application scenarios
- Tier 5: Adversarial coverage hardening
