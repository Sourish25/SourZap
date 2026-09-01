# E2E Test Infra: SourZap

## Test Philosophy
- Requirement-driven, opaque-box testing. Derived strictly from `ORIGINAL_REQUEST.md`.
- Methodology: 4-Tier hierarchy (Category-Partition, Boundary Value Analysis, Pairwise Combinatorial, Real-World Application Scenarios).

## Feature Inventory & Test Mapping
| # | Feature | Source (Requirement) | Tier 1 (Feature) | Tier 2 (Boundary) | Tier 3 (Pairwise) | Tier 4 (Scenario) |
|---|---------|----------------------|:----------------:|:-----------------:|:-----------------:|:-----------------:|
| F1 | Magnet Parsing & Normalization | ORIGINAL_REQUEST §R1 | ≥5 | ≥5 | ✓ | ✓ |
| F2 | Scoped Storage Safe Directory | ORIGINAL_REQUEST §R1 | ≥5 | ≥5 | ✓ | ✓ |
| F3 | Torrent Session Auto-Start | ORIGINAL_REQUEST §R1, §R3 | ≥5 | ≥5 | ✓ | ✓ |
| F4 | Engine State & Sequential Fixes | ORIGINAL_REQUEST §R3 | ≥5 | ≥5 | ✓ | ✓ |
| F5 | System Intent Filters Registration | ORIGINAL_REQUEST §R2 | ≥5 | ≥5 | ✓ | ✓ |
| F6 | External Intent Handling & Deep Linking | ORIGINAL_REQUEST §R2 | ≥5 | ≥5 | ✓ | ✓ |
| F7 | Auto-Open Confirmation Dialog | ORIGINAL_REQUEST §R2 | ≥5 | ≥5 | ✓ | ✓ |
| F8 | SAF File Name Resolution | ORIGINAL_REQUEST §R1 | ≥5 | ≥5 | ✓ | ✓ |
| F9 | App Update Progress Notification | ORIGINAL_REQUEST §R4 | ≥5 | ≥5 | ✓ | ✓ |
| F10 | Active Torrent Progress & Dismiss Notification | ORIGINAL_REQUEST §R4 | ≥5 | ≥5 | ✓ | ✓ |
| F11 | Android 13+ POST_NOTIFICATIONS Permission | ORIGINAL_REQUEST §R4 | ≥5 | ≥5 | ✓ | ✓ |
| F12 | Full Test & Release Build Integrity | ORIGINAL_REQUEST §Acceptance | ≥5 | ≥5 | ✓ | ✓ |

## Test Architecture
- Test Runner: Gradle unit test runner via `./gradlew.bat testDebugUnitTest`
- Release Build: `./gradlew.bat assembleRelease`
- Automated Verification: Android test suites under `app/src/test/java/com/sourzap/app/`

## Coverage Thresholds
- Tier 1: ≥5 tests per feature (≥60 tests total)
- Tier 2: ≥5 boundary/corner tests per feature (≥60 tests total)
- Tier 3: Pairwise feature combination tests (≥12 tests total)
- Tier 4: Realistic multi-feature application workflow scenarios (≥6 scenarios total)
- Total Target: ≥138 automated test cases covering 100% of requirements
