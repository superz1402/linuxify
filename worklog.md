# Linuxify — Engineering Worklog

Authoritative chronological engineering log. Updated after every completed task.
Companion files: `SESSION_STATE.md` (handoff), `docs/RECOVERY.md` (fresh-session recovery).

---

## Current project state

- Product: Linuxify (native Android app)
- Package / Application ID: `com.linuxify.app`
- Canonical repository: **https://github.com/superz1402/linuxify** (branch `main`)
- Reference repository (read-only, TypeScript CLI): https://github.com/Bilal140202/linuxify
- Current milestone: M1 — Android build pipeline + runtime proof
- Current working Issue: see GitHub Issues
- Latest verified remote commit: (see SESSION_STATE.md)
- Build environment: Debian 13 x86_64, 2 CPU, 4 GB RAM, ~9.3 GB free disk
- Android SDK / Gradle: not yet installed (task 2)
- Linux runtime integration status: proot + Ubuntu rootfs selected (task 3 verification pending)

## Baseline audit (2026-10-17)

### Repository decision (recorded rationale)

- The provided GitHub token authenticates as `superz1402`.
- `Bilal140202/linuxify` (the existing Linuxify project — a TypeScript CLI targeting
  Termux + proot-distro) grants this token **pull-only** access
  (`permissions.push = false` verified via GitHub API `GET /repos/Bilal140202/linuxify`).
- The master build prompt §2.1 anticipates this: *"If the current repository is primarily a
  TypeScript CLI, do not try to turn the CLI directly into an Android application. Create a
  proper Android project while preserving the CLI's useful functionality and compatibility
  where appropriate."*
- Decision: create `superz1402/linuxify` as the canonical repository for the **native
  Android application** (`com.linuxify.app`). The CLI repo is used as a read-only
  engineering reference (registry concepts, proot fixes, ARM constraints, doctor/self-healing
  patterns). No competing history was created inside the CLI repo; nothing there was modified.

### Baseline facts recorded

- CLI reference repo cloned to local path `linuxify-cli-reference` (outside the Android
  project working tree); baseline commit of reference repo: `8de966b`
  ("fix: v0.1.0-alpha.20 — P1 audit fixes (25 high-priority bugs)").
- Server environment (Debian 13 trixie, x86_64):
  - git 2.47.3 — present.
  - Java: OpenJDK 21.0.12.1 JRE present; `javac` **missing** (JRE-only) → must install
    `openjdk-21-jdk-headless` (or 17) for the Android build.
  - Gradle: absent. Android SDK: absent. ANDROID_HOME unset.
  - Network reachability verified (HTTP codes): github.com 200, dl.google.com 302,
    maven.google.com 301, services.gradle.org 200, repo.maven.apache.org 200.
  - Disk: 9.3 GB free — sized for JDK + Android SDK + Gradle caches + project; no room for
    wasteful duplicates. NDK is NOT planned (prebuilt Android proot binaries will be used
    with pinned versions + SHA-256 verification, provenance documented).
  - No Android emulator and no physical device is attached to this server (no `adb devices`
    possible without SDK; no KVM check yet). On-device runtime verification is therefore
    expected to be BLOCKED; runtime strategy will be verified as far as possible on this
    x86_64 Linux host (proot + rootfs shell + persistence drill), clearly labeled as
    *host verification, not device verification*.
- GitHub auth: token verified via `GET /user` → login `superz1402`. Push permissions on
  newly created repo verified (admin/push/maintain = true).

### Engineering decisions (initial)

1. **Runtime approach**: rootless proot userspace (Ubuntu 24.04 base rootfs) — matches the
   CLI project's proven approach but without the Termux dependency. The app bundles an
   Android-built proot executable and downloads the distro rootfs at runtime with
   SHA-256 verification against pinned metadata.
2. **Terminal**: vendor Termux `terminal-emulator` + `terminal-view` modules (GPLv3) with
   full attribution; PTY creation via Termux JNI `createSubprocess`. Proven at scale,
   correct PTY semantics, ANSI/scrollback/UTF-8 handled.
3. **License consequence**: bundling GPLv3 Termux terminal code → the app is GPLv3.
   Documented in LICENSE + docs/RUNTIME.md.
4. **Persistence layout**: app-private internal storage
   (`filesDir/runtime`, `filesDir/rootfs`, `filesDir/home` = Linux home, `filesDir/projects`).
   All data survives app restarts; uninstall removes everything (documented honestly).

## Task history

### Task 1 — Establish durable control (Phase A)
- Status: DONE
- Date: 2026-10-17
- Work: environment audit (above), created `superz1402/linuxify`, initialized local git,
  wrote worklog/SESSION_STATE/RECOVERY, created GitHub Issues for phase tasks, first
  checkpoint pushed and verified.
- Push verification: local HEAD == remote main == `05d77996b193644d5a15647dc9d4ac2cd31b57bb`
  (verified via `git ls-remote origin main`).
- Issues created: #1 toolchain(build), #2 runtime drill(host), #3 on-device verification
  (blocked), #4 android skeleton, #5 runtime layer, #6 terminal, #7 UI research,
  #8 UI screens, #9 tests, #10 release. Board: https://github.com/superz1402/linuxify/issues
- Tests: N/A (infrastructure task). Verification = successful push + remote SHA check.

## Active work

- Immediate next: verify first push, then install JDK + Android SDK (task 2).

## Test and release status

- Android build: NOT RUN
- Unit tests: NOT RUN
- Runtime (host) verification: NOT RUN
- Runtime (device) verification: BLOCKED (no emulator/device on server) — to be confirmed
  with `adb` checks once SDK exists.

## Risks and decisions

- Disk is the tightest resource; SDK/Gradle caches must be pruned of the largest
  avoidable components (no NDK, no emulator images).
- Session may end at any time → every completed task must be pushed immediately.

### Task 2 — Runtime proof (Issue #2) + toolchain (Issue #1, partial)
- Status: runtime drill PASS (host verification); toolchain installed; on-device BLOCKED (Issue #3)
- Date: 2026-10-17

Runtime drill (host = x86_64 Debian 13, proot-me v5.3.0 static + ubuntu-base 24.04.5 amd64):
- rootfs SHA-256 verified against upstream SHA256SUMS: e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86
- STEP1 PASS: real shell in userspace; /etc/os-release = Ubuntu 24.04.5 LTS
- STEP2 PASS: pwd=/root, ls, id — real commands
- STEP3 PASS: file created inside userspace
- STEP5 PASS: **PERSISTENCE_OK** — file survived proot process exit + relaunch
- STEP6 note: base rootfs has no python3 (expected; apt-installable inside userspace)
- Script: scripts/runtime_drill.sh (on server); command pattern recorded in docs/RUNTIME.md

Android runtime binaries staged (app/src/main/jniLibs/):
- arm64-v8a + x86_64: libproot.so, libtalloc.so, libshmem.so
  (Termux apt pool, pinned proot 5.1.107.96 / libtalloc 2.5.0 / libandroid-shmem 0.7;
   every .deb SHA-256 verified against dists/stable Packages metadata)
- arm64-v8a + x86_64: libtermux.so — JNI bridge (createSubprocess/setPtyWindowSize/waitFor/close)
  extracted from termux-app v0.118.0 release APK; symbols verified; provenance in runtime-binaries.json
- Android 10+ exec restriction handled by jniLibs packaging (exec only from nativeLibraryDir)

On-device verification blocker evidence (Issue #3):
- `adb devices` → empty list; /dev/kvm absent → no emulator possible on this host.

Toolchain (Issue #1):
- Temurin JDK 21.0.12.1 (javac 21.0.12.1) at ~/toolchain/jdk-21
- Android SDK: cmdline-tools 12.0 + platform-tools + platforms;android-35 + build-tools;35.0.0
- Gradle 8.9 zip downloaded (wrapper to be generated with project)
