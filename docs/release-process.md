# Release process

Herdroid publishes signed APKs through GitHub Releases. The first public build
is `v0.1.0`, marked as a prerelease.

## Release contract

- A pushed `v*` tag starts the existing CI workflow.
- The tag must equal `v` followed by the Android `versionName`.
- `versionCode` and `versionName` remain explicit in `app/build.gradle.kts` and
  are updated in the release commit before tagging.
- Every bridge target, Android test, lint check, and release build must pass
  before publication.
- All `v0.*` releases are prereleases and are not marked as the latest stable
  release.

## Signing

GitHub Actions stores the release keystore as
`HERDROID_SIGNING_KEY_BASE64` and its password as
`HERDROID_SIGNING_PASSWORD`. The alias is the non-secret constant
`herdroid-release`.

Tag builds decode the keystore into the disposable runner directory. Gradle
signs the release APK directly. Pull-request and ordinary branch builds never
receive signing secrets and continue to produce unsigned release artifacts.
An always-run cleanup removes the decoded keystore before the runner exits.

The workflow verifies the APK with `apksigner`, checks its certificate SHA-256
fingerprint against the public expected fingerprint, and re-runs the existing
embedded-bridge verification before publishing anything.

## Published artifacts

Each release contains:

- `herdroid-<tag>.apk`
- `herdroid-<tag>.apk.sha256`

The publisher uses GitHub's temporary `GITHUB_TOKEN` with job-scoped
`contents: write`; every other job remains read-only. `gh release create`
verifies that the tag already exists, uploads both assets while the release is
a draft, and publishes it only after upload succeeds.

## Failure behavior

- A malformed or mismatched tag stops before signing.
- Missing secrets, signing failures, unexpected bridge contents, an invalid
  APK signature, a certificate mismatch, or any test failure prevents release
  publication.
- A failed asset upload leaves no public partial release.
- Transient or secret-configuration failures are rerun from the same tag. If
  tagged code must change, the still-unpublished tag is deleted and recreated
  from the fixed commit. Published release tags are never moved.

## Verification

Before the first tag, exercise the signing configuration locally with a
disposable test key. For every release, CI runs the existing Rust and Android
checks, verifies the embedded bridges, verifies the APK signature and signing
certificate, and computes the published checksum.

Download the first private prerelease and smoke-test installation and startup
on an emulator before making the repository public. Do not replace the Pixel's
debug-signed installation because uninstalling it can destroy non-exportable
Android Keystore credentials.

## Public launch

Before changing repository visibility:

1. Run a fresh secret and privacy scan over the committed tree and history.
2. Publish and verify the private `v0.1.0` prerelease.
3. Update the README with agent waiting/finished alerts, the Releases download
   link, prerelease installation guidance, and the signing fingerprint.

After making the repository public:

1. Enable private vulnerability reporting.
2. Protect `main` with pull requests, required CI, and blocked force-pushes and
   deletion.
3. Protect `v*` tags from updates and deletion.
4. Enable Dependabot vulnerability alerts and security updates.
5. Keep GitHub Actions read-only by default.

Play Store publishing, stable-release promotion, SBOMs, provenance
attestations, and signing-key rotation are intentionally deferred until they
are needed.
