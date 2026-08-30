# Compatibility and evidence

Evidence date: 2026-08-28. Herdroid is in active development; this table records
tested combinations rather than promising support for every cross-product.

## Targets

| Target | Bridge build | Physical connection | Terminal |
|---|---:|---:|---:|
| Linux x86_64 | CI | Verified through one jump host | Verified |
| Linux aarch64 | CI | Not run | Not run |
| Windows x86_64 | CI | Verified direct | Verified |

Windows aarch64 is intentionally unsupported because no bridge target exists.

## Android and authentication

| Combination | Evidence |
|---|---|
| API 29 emulator | Automated unit, lint, UI, and terminal tests |
| Physical API 36 device | Direct and jump routes, terminal opening, and persisted fast reconnect |
| Password | Verified on a representative target |
| Hardware-backed ECDSA P-256 key | Verified on a representative direct Windows route |
| One jump host | Verified on a representative Linux target through Windows |

These results do not claim every authentication method against every target.
Physical Linux aarch64, exhaustive accessibility/input combinations, and
release-signed distribution remain unverified.

## Known limits

- Live event streams are capped at eight panes; slower snapshots preserve state beyond that cap.
- A clean terminal release restores geometry; abrupt loss may not.
- Stock default-session discovery cannot find a Herdr session started with a custom `HERDR_SOCKET_PATH`.
- Notification behavior depends on Android version, permission, and service lifetime.

See [CI](../.github/workflows/ci.yml) for the exact automated build matrix.
