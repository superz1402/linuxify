# Implementation Plan — status ledger

Honest ledger of what is implemented, tested, and unverified. Mirrors the GitHub issue
board (source of truth) and the worklog. Updated per checkpoint.

## Verified (evidence recorded)

| Area | Evidence |
|---|---|
| Android build pipeline (JDK 21, SDK 35, Gradle 8.9) | Issue #1 closed; worklog Task 2 |
| Linux userspace drill on host: real shell, commands, persistence across restart | Issue #2 closed; worklog Task 2 (`PERSISTENCE_OK`) |
| Native Compose app builds; package `com.linuxify.app` confirmed via aapt | Issue #4 closed; APK SHA-256 recorded |
| Runtime layer: staged/resumable/checksummed installer; proot argv; bounded exec; typed errors | Issue #5 closed; unit tests |
| Real PTY terminal via vendored Termux stack; extra-keys row; multi-session | Issue #6 closed (build-verified) |
| Unit tests 12/12 PASS (checksum, path safety, ABI mapping) | Issue #9 closed |
| UI research across the three mandated accounts (94 files read) | Issue #7 closed; docs/UI_RESEARCH.md |

## Implemented, on-device verification pending

| Area | Blocker |
|---|---|
| Installer end-to-end on a real device (arm64) | No device/emulator on build server — Issue #3 (open) |
| Terminal interaction on device (keyboard, insets, resize) | same |
| apt/project/service workflows on device | same |

## Implemented but explicitly unproven

- OpenCode integration: NOT implemented yet. No verification exists. Do not assume it
  works. (Next milestone — see below.)
- LAN bind mode for services: the UI defaults to loopback; LAN switch is not yet exposed.
- Foreground service for long-running servers: not implemented (honest: processes die
  with the app under Android pressure).

## Next highest-value work

1. On-device verification when a device/emulator-capable environment exists (Issue #3).
2. OpenCode install profile (real upstream install method, verified at runtime).
3. Foreground service + notification for managed servers.
4. File import/export (SAF), backup/restore of projects.
5. Theme-mode setting + dynamic-color option (per docs/UI_RESEARCH.md spec).
