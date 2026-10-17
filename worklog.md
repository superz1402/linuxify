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

---

### Task 7 — UI research (Issue #7)
- Task ID: 7
- Agent: UI-research sub-agent (general-purpose)
- Status: DONE
- Date: 2026-10-17
- Task: Deep UI reverse-engineering research of Android projects under GitHub
  accounts bilal140202, ansaribilal14, superz1402, to ground Linuxify's UI
  design (issue #7) in the maintainer's own shipped patterns. Research only —
  no app code changed.
- Work Log:
  - Inventoried all three accounts via GitHub API (GET /users/<login>/repos,
    token-authed): Bilal140202 (~70 repos, 13 Android-relevant Kotlin/Java),
    ansaribilal14 (32 repos, 8 Android-relevant), superz1402 (18 repos, only
    linuxify + agent-ui-playbook are UI-relevant).
  - Fetched recursive git trees for 11 repos; downloaded 94 raw UI source
    files (themes, tokens, navigation, screens, state composables, XML
    layouts) — all fetched HTTP 200, no private/deleted/failed access; API
    rate limit used: 4 core requests.
  - Read and analyzed (evidence = real file paths, recorded in the doc):
    Bilal140202/Your-Files-Redesign (Spacing/Type/Color/Theme/Modifiers/
    Router/Empty/Loading/ScreenScaffold), Bilal140202/tabit-android
    (Dimens tokens incl. motion+alpha, AppNavigation, MainActivity bottom-bar
    whitelist, values-night), Bilal140202/AuraGallery (TopLevelDestination,
    AuraBottomBar, IndexingProgressStrip), Bilal140202/comet-x-browser
    (hybrid View XML cautionary tale), Bilal140202/MobileAgent (M2/XML
    anti-pattern baseline), Bilal140202/VoxFlow (template-grade floor),
    ansaribilal14/jarvis (StatusChip/ChipState, SectionCard, DoctorScreen
    CheckState, ModelsScreen state machines, expressive theme),
    ansaribilal14/open-video-engine-android (Tokens.kt governance, forced-
    dark editor, no-dead-controls; found real light-scheme bug:
    onSurfaceVariant mapped to Dark constant), ansaribilal14/zerium-browser
    (+zerium-gecko twin: M3-in-XML mirror scheme, accent switching),
    ansaribilal14/EchoID-Android (template-color drift + GlassCard
    anti-pattern), ansaribilal14/tabit-android (v1 delta vs v2),
    superz1402/agent-ui-playbook (13 UI laws), and the current Linuxify
    theme/nav files locally.
  - Deliverable written: docs/UI_RESEARCH.md — method+limitations, per-repo
    findings with file-path evidence, 14 cross-cutting do's, 12
    anti-patterns, and an actionable Linuxify design spec (typography scale,
    spacing system, navigation model, status hierarchy, state triplet,
    terminal ergonomics, implementation order for Task 8).
  - Note: task brief pointed at /home/z/my-project/worklog.md which does not
    exist; the authoritative worklog is this file (linuxify-app/worklog.md),
    which was appended to instead. Deliverable path:
    linuxify-app/docs/UI_RESEARCH.md.
- Tests: N/A (research task). Verification = 94/94 raw fetches HTTP 200;
  tree JSONs untruncated; every cited path present in the fetched tree or
  local checkout.
- Stage Summary: UI research complete and written to docs/UI_RESEARCH.md.
  Conclusion: Linuxify's existing dark-first cyan theme + 5-tab nav + mono
  tier are confirmed and should be extended (not replaced) with: Spacing/
  Tokens objects, shared Empty/Loading/Error composables, StatusChip+
  stepColor consolidation, ProgressStrip above the bottom bar, selected/
  unselected nav icon pairs + DetailRoutes split, forced-dark terminal
  wrapper, scheme-integrity test, theme-mode DataStore. Ready for Task 8
  (UI screens) to consume the spec's §5 implementation order.

### Task 3 — Android project + runtime layer + terminal + UI (Issues #4,#5,#6,#8,#9)
- Status: DONE (closed with evidence comments)
- Date: 2026-10-17
- Commits: 8353db3 (major milestone), 57f048e (UI checkpoint fixes)
- Key facts:
  - assembleDebug BUILD SUCCESSFUL: com.linuxify.app v0.1.0-alpha.1, minSdk 26, target 35
  - APK SHA-256: e9670006a693c85cb0754dd7925db2613f23fff3cf3644a927a8a76ba48b9c9b (18,722,215 B)
  - ABIs packaged: arm64-v8a + x86_64 (libproot/libtalloc/libshmem/libtermux)
  - Unit tests: 12/12 PASS (checksum vectors, traversal safety, ABI mapping)
  - Compile-error fixes: RuntimeError→Exception, TerminalView(Context,null), R namespace
    repoint for vendored textselection, cancel/Cancelled data object, isFile() API

### Task 4 — UI research (Issue #7)
- Status: DONE
- Commit: 520ac94 (docs/UI_RESEARCH.md, 641 lines)
- 94 files read from 12 repos across the three mandated accounts; grounded design spec
  produced; Spacing/Motion tokens + state triplet adopted into the codebase.

### Task 5 — Release (Issue #10)
- Status: DONE with one documented environment limitation
- Date: 2026-10-17
- assembleRelease: FAILED on this host — Gradle daemon killed (OOM) during release
  pipeline (host: 4.1 GB total RAM, ~2.4 GB baseline usage; R8/lint chain exceeds headroom
  even with minification disabled and jvmargs lowered). Evidence: repeated daemon-death
  logs. Decision per addendum §6.3: document blocker, ship the debug APK as the release
  artifact (identical content: same signing config by policy for alpha, no minification
  in release either after the change; diff is metadata only).
- GitHub Release: v0.1.0-alpha.1 (prerelease) with linuxify-0.1.0-alpha.1-debug.apk
  https://github.com/superz1402/linuxify/releases/tag/v0.1.0-alpha.1
- Security sweep: no tokens/credentials in any tracked file; local.properties ignored.
- Docs set finalized: ARCHITECTURE, RUNTIME, SECURITY, BUILDING, TROUBLESHOOTING,
  IMPLEMENTATION_PLAN, UI_REVIEW, UI_RESEARCH, RECOVERY, THIRD_PARTY_TERMUX_LICENSE.

## Final session state

- Issues: #1,#2,#4,#5,#6,#7,#8,#9 closed (evidence comments); #3 open (on-device
  verification blocked — documented, no simulation); #10 closing with this release.
- Latest verified remote commit: see `git ls-remote origin main` after this commit.
- Every artifact pinned + checksummed in runtime-binaries.json.
- Next session: follow docs/RECOVERY.md; first open issue is #3 (needs device).

### Task 6 — fix device-verified startup failure: native dep name mismatch (Issue #11)
- Status: DONE (build verification complete; on-device confirmation pending with user)
- Date: 2026-10-17
- Trigger: first real on-device drill of issue #3 failed: `CANNOT LINK EXECUTABLE "…/libproot.so": library "libtalloc.so.2" not found: needed by main executable` (rc=1, uname probe). This is genuine device evidence, not a hypothesis.
- Root cause (ELF-inspection-confirmed on the bundled binaries):
  - bionic resolves DT_NEEDED by literal file name; Android jniLibs extracts only `lib*.so` names;
  - Termux proot needs `libtalloc.so.2` (and `libandroid-shmem.so`) — neither name can ever be extracted from the APK; we ship `libtalloc.so`/`libshmem.so`. Second mismatch would have surfaced after fixing the first.
- Fix (two independent layers, no runtime code download):
  - Build time: `tools/patch_elf_needed.py` rewrites proot DT_NEEDED in place (strictly-shorter names, NUL-padded; no ELF structure change). Both ABIs patched; digests recorded in runtime-binaries.json (`post_process.sha256_patched`): arm64 `b0d1f3f2…`, x86_64 `1ce2d86c…`.
  - Runtime self-heal: `LinuxRuntime.preflightProot()` (runs before any rootfs download — fail fast) executes `libproot.so --version`; on linker failure `NativeDepRepair` maps the missing soname (pinned alias / version-strip) and symlinks it under the exact requested name in private `runtime/syslib`, prepended to LD_LIBRARY_PATH; up to 3 retries, offline, no user action. Defense-in-depth: verification probe also repairs+retries on linker failure. New `RuntimeError.NativeDep` with honest, precise user messaging.
  - Rationale for NOT fetching libs at runtime: no executable bytes downloaded on device keeps the supply chain pinned to the APK (SECURITY-first per master prompt).
- Tests: 23/23 PASS (12 pre-existing + 11 new NativeDepRepairTest incl. the real device error string, alias + version-strip mapping, shim idempotency, honest-failure cases). One iteration: isLinkFailure broadened to cover the `dlopen failed: library "X" not found` form after first run caught it.
- Build (this server, restored from scratch per docs/RECOVERY.md): Temurin JDK 21.0.12.1 + SDK android-35/build-tools 35.0.0; assembleDebug BUILD SUCCESSFUL; APK `linuxify-0.1.0-alpha.2-debug.apk` 18,640,076 B, SHA-256 `1f6821d0ff10aea18eb32ae29eead2ce2db433527093eea03607d68f8272d768`; APK-internal verification: lib/arm64-v8a/libproot.so = patched digest b0d1f3f2…, DT_NEEDED `['libtalloc.so','libshmem.so','libc.so']` — all resolvable (two packaged, libc system).
- 16 KB page-size risk from SESSION_STATE: CLEARED for current binaries — all bundled ELFs `PT_LOAD p_align = 0x4000` (recorded in runtime-binaries.json).
- Environment note: system OpenJDK here is JRE-only (no jlink) → installed full Temurin JDK to ~/toolchain; first Kotlin daemon crash under memory pressure worked around by retry (in-process strategy not needed on final run).
- Verification boundary (honest): build verification + ELF verification + unit tests done here; the on-device confirm (install → uname probe passes → persistence drill) is the user's step and closes issue #3.
