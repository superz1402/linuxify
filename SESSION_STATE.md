# SESSION_STATE.md — Linuxify Android

Concise handoff for the next agent session. Current as of 2026-10-18 (Task 15 —
background running, completion notifications, network auto-recovery, CLI parity
audit, docs refresh; v0.1.0-alpha.10).

## Repository & branch

- Canonical repo: https://github.com/superz1402/linuxify — branch `main`
- Latest verified remote commit: see `git ls-remote origin main`
- Releases:
  - v0.1.0-alpha.10 (current) — background running via RuntimeForegroundService
    (persistent notification + partial wake lock, auto-release when idle);
    process-scoped TerminalSessionRegistry (sessions survive tab switch, app
    switch, backgrounding — no more orphaned PTYs, same shell on return);
    "run completed" notifications for backgrounded session/service exits;
    rootfs download hardening (8 attempts, exponential backoff + jitter,
    Range resume — was 5 linear); docs/CLI_PARITY.md full audit; README
    rewritten. Assets: linuxify-0.1.0-alpha.10-release.apk
  - v0.1.0-alpha.9 — floating share-log button (full transcript → share sheet)
  - v0.1.0-alpha.8 — terminal Copy/Paste actually reach the system clipboard
  - v0.1.0-alpha.7 — Termux-true terminal UX (keyboard, font, extra keys,
    group warnings, jank)
  - older (superseded): alpha.1–6 install/crash chain, all documented in
    worklog.md

## THE loader rule (learned in alpha.4 — do not regress)

- Termux proot is built with PROOT_UNBUNDLE_LOADER: every guest execve is
  rewritten to exec the SEPARATE static loader binary. Linuxify ships it as
  jniLibs/libproot-loader.so and sets PROOT_LOADER in LinuxRuntime.buildEnv().
  Install gate fails fast if missing. Details: docs/RUNTIME.md.

## Background running architecture (alpha.10)

- RuntimeForegroundService (runtime/): FGS, START_STICKY, persistent LOW-importance
  notification, PARTIAL_WAKE_LOCK (6 h cap, re-armed by user action). start() is
  called by TerminalSessionRegistry.add() and ServiceManager.start(); stopIfIdle()
  releases it only when no live sessions AND ServiceManager.activeCount == 0.
- TerminalSessionRegistry (terminal/): process-scoped session list + the SINGLE
  shared LinuxifySessionClient (boundView + onUiInvalidated hooks). TerminalScreen
  attaches to existing sessions instead of respawning. The old private client
  class in TerminalScreen.kt was REMOVED — do not resurrect it.
- RuntimeNotifier: channels "linuxify_runtime" (persistent, LOW) and
  "linuxify_events" (run completions, DEFAULT). Events suppressed while
  LinuxifyApp.isInForeground (ActivityLifecycleCallbacks counter).
- ServiceManager: activeCount companion counter; exit-watcher thread is the single
  decrementer; userStopped flag suppresses the notification for user-initiated stops.
- Honest limits: force-stop, OEM kill lists (MIUI etc.), extreme memory pressure
  can still kill it — documented in README, not promised away.
- targetSdk stays 28 (exec-in-app-storage rule). FGS needs no type at this
  targetSdk; 2-arg startForeground (Termux-identical).

## Signing (unchanged since alpha.3 — do not touch)

- `keystore/linuxify-release.jks` + `keystore/keystore.properties` are COMMITTED ON
  PURPOSE: stable identity means updates install over previous builds.
  Cert SHA-256: 8f55e46675c284931e79f3ccc7fde4fad8970f6af4d506f5b75ed3edd7bdcca9.

## Build environment (this server)

- JDK: ~/toolchain/jdk-21.0.12.1+1 (Temurin — system OpenJDK is JRE-only)
- SDK: /home/z/android-sdk (platform-tools, android-35, build-tools 35.0.0,
  cmdline-tools latest)
- Rebuild: `export JAVA_HOME=~/toolchain/jdk-21.0.12.1+1 PATH="$JAVA_HOME/bin:$PATH"
  ANDROID_HOME=~/android-sdk && ./gradlew assembleDebug testDebugUnitTest assembleRelease`
- local.properties: sdk.dir=/home/z/android-sdk (ignored)
- Both debug and release APKs build fine on the 4 GB host.

## ENVIRONMENT ROLLBACK NOTICE (learned Task 14 — treat as standing rule)

- The local clone has reverted to an older snapshot between sessions at least once
  (HEAD, uncommitted work, JDK/SDK, download/ all rolled back; reflog showed no
  trace). The GitHub remote was unaffected.
- RULE: origin/main is the ONLY durable state. Commit + tag + push before the
  session ends; if the env is missing, reinstall per docs/RECOVERY.md and this file.

## CLI parity

- Full per-command audit: docs/CLI_PARITY.md (reference CLI: Bilal140202/linuxify).
- Honest remaining gaps: curated tool registry UX, one-tap `apt upgrade`, visual
  installed-packages list, snapshot/gc, in-app self-update, multi-distro.

## On-device verification ledger

- Device-verified by the user: install drill (alpha.4+), terminal prompt +
  keyboard + font + group-warning fix (alpha.7), clipboard Copy/Paste (alpha.8).
- Awaiting user drill for alpha.9 (share-log icon) and alpha.10 (background
  running, completion notifications).

## Next exact actions

1. User tests alpha.10 on device: (a) open terminal → home-screen out → confirm
   persistent notification + shell alive after minutes; (b) start a service
   (python http.server) → background → kill the process → expect "Run completed"
   notification; (c) flaky-network install retry if re-installing.
2. Candidate next features: curated tool registry UX, one-tap apt upgrade,
   installed-packages screen, session close button ("×" on ended tabs).
