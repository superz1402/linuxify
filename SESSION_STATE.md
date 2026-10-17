# SESSION_STATE.md — Linuxify Android

Concise handoff for the next agent session. Current as of 2026-10-17 (Task 5 complete).

## Repository & branch

- Canonical repo: https://github.com/superz1402/linuxify — branch `main`
- Latest verified remote commit: recorded in worklog "Final session state"
- Release: https://github.com/superz1402/linuxify/releases/tag/v0.1.0-alpha.1
  (asset: linuxify-0.1.0-alpha.1-debug.apk, SHA-256 e9670006a693c85c…)

## Status of issues

- CLOSED with evidence: #1 (toolchain), #2 (host runtime drill PASS), #4 (skeleton),
  #5 (runtime layer), #6 (terminal), #7 (UI research), #8 (UI screens+checkpoint),
  #9 (tests 12/12)
- OPEN: #3 (on-device verification — blocked: no device/emulator/KVM on server),
  #10 (close after final push)

## Build environment (this server)

- JDK: ~/toolchain/jdk-21 (Temurin 21.0.12.1, javac OK)
- SDK: ~/android-sdk (platform-tools, android-35, build-tools 35.0.0)
- Gradle wrapper 8.9 (project-committed)
- local.properties: sdk.dir=/home/z/android-sdk (machine-local, ignored)
- Rebuild: `export JAVA_HOME=~/toolchain/jdk-21 PATH="$JAVA_HOME/bin:$PATH" ANDROID_HOME=~/android-sdk && ./gradlew assembleDebug testDebugUnitTest`

## Known blockers

1. No Android device/emulator (no /dev/kvm, adb list empty) → on-device verification
   pending; keep honest labels (host-verified vs device-verified) everywhere.
2. assembleRelease OOMs the daemon on this 4 GB host → debug APK is the shipped
   artifact for alpha; minification disabled for release until a beefier CI exists.

## Next exact actions

1. On a device-capable environment: install the release APK, run the on-device drill
   (install runtime → shell → pwd/uname → create file → force-stop → relaunch → verify
   persistence) and close issue #3 with evidence.
2. Then: OpenCode install profile; foreground service for servers; LAN bind option;
   theme-mode setting (per docs/UI_RESEARCH.md spec §5).

## Outstanding risks

- 16 KB page-size devices (proot build predates them) — see docs/RUNTIME.md.
- Release signing identity not provisioned (docs/BUILDING.md documents the steps).
