# SESSION_STATE.md — Linuxify Android

Concise handoff for the next agent session. Current as of 2026-10-18 (Task 9 complete — in-app crash reporting + non-fatal error surfaces; v0.1.0-alpha.5).

## Repository & branch

- Canonical repo: https://github.com/superz1402/linuxify — branch `main`
- Latest verified remote commit: see `git ls-remote origin main`
- Releases:
  - v0.1.0-alpha.5 (current) — crash can no longer be silent: global UncaughtExceptionHandler renders a copyable in-app report (plain-Android activity, :crashreport process); TerminalScreen pre-loads libtermux + guards session spawn; all ViewModel coroutines guarded; ready-gate verifies native binaries (stale marker -> auto-repair); assets: linuxify-0.1.0-alpha.5-release.apk + -debug.apk
  - v0.1.0-alpha.4 (superseded; install works on device but Terminal/Projects/Services taps crashed with "app keeps stopping" — no trace available, hence the crash-report layer)
  - v0.1.0-alpha.3 (superseded; missing loader → device exec failures)
  - v0.1.0-alpha.2 (superseded; had the SafeTarExtractor `..`-symlink drop found on device)
  - v0.1.0-alpha.1 (superseded; had the jniLibs DT_NEEDED bug found on device)

## Status of issues

- CLOSED with evidence: #1 #2 #4 #5 #6 #7 #8 #9 #11 (prior sessions)
- OPEN: #3 (on-device verification) — three device drills so far each surfaced one real
  bug (talloc linker mismatch -> alpha.2; `..`-symlink drop -> alpha.3; proot's
  UNBUNDLED loader missing → execve ENOENT/EACCES -> alpha.4). Re-run the device drill
  with alpha.4: install should complete with ZERO errors (loader gate + integrity
  verify + auto-repair + exec-bit sweep + layered exec probes all run automatically),
  then close #3.

## THE loader rule (learned in alpha.4 — do not regress)

- Termux proot is built with PROOT_UNBUNDLE_LOADER: every guest execve is rewritten to
  exec the SEPARATE static loader binary. Linuxify must (a) ship it as
  jniLibs/libproot-loader.so and (b) set PROOT_LOADER in LinuxRuntime.buildEnv(). Both
  are in place and unit-drilled; the install gate fails fast if the loader is missing.
  Details: docs/RUNTIME.md "proot's ELF loader".

## Signing (changed in alpha.3)

- `keystore/linuxify-release.jks` + `keystore/keystore.properties` are COMMITTED ON
  PURPOSE: stable identity across debug and release means updates install over
  previous builds without uninstalling. Cert SHA-256: 8f55e46675c284931e79f3ccc7fde4fad8970f6af4d506f5b75ed3edd7bdcca9.
- NEVER regenerate or move these files between releases; see docs/BUILDING.md.

## targetSdk 28 (changed in alpha.3 — do not raise)

- Android 10+ blocks execve() from app-private storage for targetSdk >= 29; the whole
  proot userspace lives there. targetSdk 28 keeps untrusted_app_27 (exec allowed) —
  same trade-off as Termux/UserLAnd. Lint check ExpiredTargetSdkVersion is disabled
  with rationale in app/build.gradle.kts.

## Build environment (this server)

- JDK: ~/toolchain/jdk-21.0.12.1+1 (Temurin, full JDK — system OpenJDK is JRE-only, no jlink)
- SDK: /home/z/android-sdk (platform-tools, android-35, build-tools 35.0.0)
- Gradle wrapper 8.9 (project-committed); local.properties: sdk.dir=/home/z/android-sdk (ignored)
- Rebuild: `export JAVA_HOME=~/toolchain/jdk-21.0.12.1+1 PATH="$JAVA_HOME/bin:$PATH" ANDROID_HOME=~/android-sdk && ./gradlew assembleDebug testDebugUnitTest`
- RAM note: 4 GB host; assembleDebug fine; assembleRelease previously OOMs Gradle daemon → debug APK remains the shipped alpha artifact.

## Known blockers

1. No Android device/emulator on the build server (no /dev/kvm) → device verification
   stays with the user. Keep honest host-verified vs device-verified labels.

## Next exact actions

1. User installs v0.1.0-alpha.2 on device → Install flow should self-setup with no
   error (preflight may create syslib shims transparently). Run the issue #3 drill:
   shell → pwd/uname → create file → force-stop → relaunch → verify persistence.
2. Close #3 with evidence. Then: OpenCode install profile; foreground service for
   servers; LAN bind option; theme-mode setting (docs/UI_RESEARCH.md spec §5).

## Outstanding risks

- 16 KB page-size devices: CLEARED for currently pinned binaries (PT_LOAD p_align 0x4000
  verified; re-check whenever re-pinning — runtime-binaries.json post_process).
- Release signing identity not provisioned (docs/BUILDING.md).
