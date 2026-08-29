# Contributing to SourZap

Thank you for your interest in contributing to SourZap! We are building a high-speed, rootless DPI evasion engine and network telemetry app with Google's Material 3 Expressive design language.

## Code of Conduct

All contributors and maintainers are expected to follow our [Code of Conduct](CODE_OF_CONDUCT.md).

## How Can You Contribute?

- **Suggest Regional DPI Bypass Presets**: ISPs across different countries use varying DPI hardware (e.g. TSPU, Huawei, Sandvine). If you've found an optimal combination of TLS split offset, fake SNI, and TTL for your region, please submit a preset!
- **Performance & Networking Improvements**: Enhancing the zero-allocation buffer pipeline, socket window scaling, or coroutine channel throughput.
- **UI & Material 3 Expressive Polish**: Refining animations, spring physics, or adding custom themes.
- **Bug Reports & Fixes**: Testing on different Android versions and device manufacturers (OneUI, MIUI/HyperOS, OxygenOS, Pixel).

---

## Development Workflow

### Prerequisites
- JDK 17 (Temurin / OpenJDK)
- Android SDK Platform API 34 / 35
- Gradle 8.10+ (included via wrapper ./gradlew)

### Building the Project
`ash
# Clone your fork
git clone https://github.com/<your-username>/SourZap.git
cd SourZap

# Run unit tests
./gradlew testDebugUnitTest

# Assemble debug build
./gradlew assembleDebug
`

### Pull Request Process

1. Create a branch from main:
   `ash
   git checkout -b feat/your-feature-name
   `
2. Commit your changes with clear, descriptive commit messages.
3. Make sure all unit tests pass:
   `ash
   ./gradlew testDebugUnitTest
   `
4. Push to your fork and submit a Pull Request targeting main.
5. Fill out the [Pull Request Template](.github/PULL_REQUEST_TEMPLATE.md).
6. A maintainer will review your code. Once approved and CI checks pass, your PR will be squashed and merged!

---

## Code Style & Architecture Guidelines

- **Kotlin First**: Write clean, idiomatic Kotlin code following the official Kotlin coding conventions.
- **Jetpack Compose**: Keep composable functions stateless where feasible; hoist state into ViewModels or StateFlows.
- **Network Performance**: Avoid allocating buffers inside the steady-state packet loops. Use ByteArrayPool for buffer acquisition.
- **Documentation**: Keep comments meaningful and self-explanatory.