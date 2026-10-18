# Linuxify (Android)

**Linuxify** is a native Android application (Kotlin + Jetpack Compose) that turns an
Android device into a practical, local Linux development workstation — no root, no Docker
daemon, no Termux dependency, no remote server required.

> This repository hosts the **native Android app** (`com.linuxify.app`). The original
> Linuxify TypeScript CLI (Termux-based) lives at [Bilal140202/linuxify](https://github.com/Bilal140202/linuxify)
> and remains the engineering reference for registry, doctor, and compatibility concepts.

## What it does

- Installs a real **Ubuntu Linux userspace** locally using **proot** (unrooted, app-private) —
  staged, resumable, checksum-verified, with **automatic self-setup and self-repair**
  (native-linker shims, exec-bit sweep, integrity re-checks, stale-marker healing).
- Provides a **real PTY-backed terminal** (vendored Termux emulator/view) with Termux-style
  UX: tap-to-keyboard, pinch-to-zoom, extra-keys rows, long-press Copy/Paste on the real
  clipboard, multiple sessions, and a floating button that **shares the whole session log
  to any chat/AI app** in two taps.
- **Runs in the background**: a foreground service (with a silent persistent notification
  and a partial wake lock) keeps the Linux userland alive while you're outside the app —
  sessions and servers survive screen-off and app switches. It releases itself the moment
  the last session/service ends.
- **Notifies on completion**: a "run completed" notification appears when a backgrounded
  shell session or managed service exits (suppressed while you're actually watching).
- **Recovers from flaky internet**: the rootfs download uses HTTP Range resume,
  exponential backoff with jitter (up to 8 attempts), and checksum verification before
  anything is trusted.
- Manages **packages** through the real APT inside the userspace (search / install /
  remove / update).
- Manages **projects** (Git clone, create, open in terminal) and **local servers**
  (loopback by default; LAN only by explicit choice).
- Keeps user files **persistent** across app restarts (until app uninstall / explicit reset).
- Is **honest** about Android limitations: no kernel-level isolation, no privileged ports,
  and no guarantee against force-stop or OEM task-manager kill lists (documented, not promised).

## Status

Early development (v0.1.0-alpha.10). See [worklog.md](worklog.md), [SESSION_STATE.md](SESSION_STATE.md),
[docs/CLI_PARITY.md](docs/CLI_PARITY.md) (full CLI ⇄ app feature audit) and the GitHub
issue tracker for verified progress. What is claimed where is strictly separated into:
**build-verified**, **host-verified**, **device-verified** — anything not labeled is not verified.

## Build

See [docs/RECOVERY.md](docs/RECOVERY.md) for the exact reproducible toolchain setup, or:

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+ (21 tested), Android SDK platform 35, build-tools 35.0.0.

## License

GPL-3.0-or-later. Bundles GPLv3 components (Termux terminal-emulator/terminal-view,
proot) with full attribution in `docs/RUNTIME.md` and `docs/SECURITY.md`.
