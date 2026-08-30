# Contributing to Herdroid

Thanks for helping improve Herdroid.

## Before you start

- Search existing issues before opening a new one.
- Keep changes focused; discuss large behavior or architecture changes first.
- Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
- Never post credentials, private keys, host keys, private addresses, or unredacted logs.

## Development setup

You need JDK 17, Android SDK 37, PowerShell, and Rust. Package the three bridge
targets before building Android:

```powershell
./scripts/package-bridge.ps1 -Target x86_64-unknown-linux-gnu -Binary <path> -Output build/bridge-artifacts
./scripts/package-bridge.ps1 -Target aarch64-unknown-linux-gnu -Binary <path> -Output build/bridge-artifacts
./scripts/package-bridge.ps1 -Target x86_64-pc-windows-msvc -Binary <path> -Output build/bridge-artifacts
./gradlew.bat -PbridgeArtifactDir=build/bridge-artifacts assembleDebug
```

## Before a pull request

Run the checks relevant to your change. The full CI gates are:

```powershell
cargo fmt --manifest-path bridge/Cargo.toml --check
cargo clippy --manifest-path bridge/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path bridge/Cargo.toml --locked
./gradlew.bat --dependency-verification strict -PbridgeArtifactDir=build/bridge-artifacts test lint assembleDebug assembleDebugAndroidTest assembleRelease
./scripts/verify-bridge-apk.ps1 -Apk app/build/outputs/apk/release/app-release-unsigned.apk
```

Describe what changed, how it was verified, and any user-visible or security
impact. Include sanitized screenshots for UI changes.
