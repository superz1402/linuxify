# SESSION_STATE.md — Linuxify Android

Concise handoff for the next agent session. Current as of 2026-10-17 (Task 6 complete — device-reported startup bug fixed).

## Repository & branch

- Canonical repo: https://github.com/superz1402/linuxify — branch `main`
- Latest verified remote commit: see `git ls-remote origin main`
- Releases:
  - v0.1.0-alpha.2 (current; fixes issue #11) — asset linuxify-0.1.0-alpha.2-debug.apk, SHA-256 1f6821d0ff10aea1…
  - v0.1.0-alpha.1 (superseded; had the jniLibs DT_NEEDED bug found on device)

## Status of issues

- CLOSED with evidence: #1 #2 #4 #5 #6 #7 #8 #9 (prior sessions), #11 (libtalloc.so.2
  linker failure — fixed in v0.1.0-alpha.2: build-time DT_NEEDED patch + runtime
  preflight/self-heal; build-verified, ELF-verified, 23/23 unit tests)
- OPEN: #3 (on-device verification) — the user's first real device drill produced the
  evidence that surfaced #11; device drill must be re-run with v0.1.0-alpha.2 to close #3.

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
