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

### Task 7 — fix second on-device install failure (execve ENOENT) + auto self-setup + stable signing (v0.1.0-alpha.3)
- Status: DONE (build verification + real host runtime drill complete; on-device confirmation pending with user)
- Date: 2026-10-17
- Trigger: user's second real device drill — rootfs install completed but probe failed:
  `proot error: execve("/usr/bin/bash"): No such file or directory` (after the Task 6
  talloc fix had already been confirmed working on device: `native runtime loadable (proot rc=0)`).
- Root cause (REPRODUCED END-TO-END on the build host, not guessed):
  - `SafeTarExtractor` silently dropped EVERY symlink whose target text contained `..`.
    A `..` in a link target is only dangerous when it escapes the rootfs; Ubuntu 24.04's
    usrmerge layout legitimately relies on such links. 66 entries dropped on arm64
    (incl. etc/os-release, usr/bin/ld.so, usr/lib/apt/planners/dump); on amd64 the dropped
    set contained the dynamic loader link itself
    (`usr/lib64/ld-linux-x86-64.so.2 -> ../lib/x86_64-linux-gnu/ld-linux-x86-64.so.2`),
    so bash's PT_INTERP could not resolve and every guest exec returned ENOENT while the
    app's `bin/bash` readiness gate still passed (host-JVM symlinks succeeded; on-device
    a smaller symlink subset was enough to pass the gate but not proot's exec chain).
  - Second latent issue (also fixed): Android 10+ forbids execve() from app-private
    storage for apps targeting SDK >= 29 — targetSdk lowered 35 -> 28 (untrusted_app_27
    domain), the same approach Termux/UserLAnd use. compileSdk stays 35.
  - Third latent issue fixed: proot's own LD_LIBRARY_PATH (syslib shims) leaked into the
    guest, where it could shadow guest libraries after apt installs (e.g. libtalloc2);
    guest commands/PTY shells now unset it.
- Fixes:
  - SafeTarExtractor: `..`-targets normalized segment-wise, links kept when they resolve
    inside the rootfs (targets preserved verbatim); escaping links RECORDED in
    `Result.skipped` — never dropped silently; hardlink targets resolved relative to the
    ARCHIVE ROOT (POSIX) and resolved in a second pass (ubuntu-base stores
    usr/bin/perl5.38.2 before usr/bin/perl).
  - NEW RootfsIntegrity: post-extract verification of every exec-critical path (loader
    chain first) with rootfs-relative resolution; auto-repair re-extracts exactly the
    affected entries from the cached checksum-verified artifact — no network, no user
    action. Upstream-dangling links (locale catalogs) deliberately NOT gated (they exist
    in the upstream tarball and proot/apt never dereference them).
  - RootfsInstaller: integrity gate + auto-repair between install and probe; exec probe
    is now a layered fallback chain (bash -lc -> sh -c -> direct /usr/bin/uname) with
    self-heal retries (linker shim repair / rootfs repair) — install only fails after
    every automatic fix has been attempted, and the technical log shows each step.
  - PtyTerminal/boundedExec: guest env hygiene (unset LD_LIBRARY_PATH).
  - Signing: dedicated stable keystore `keystore/linuxify-release.jks` (+ keystore.properties,
    both committed deliberately so updates never require an uninstall; documented
    trade-off in docs/BUILDING.md). Debug AND release use the same identity
    (cert SHA-256 8f55e46675c284931e79f3ccc7fde4fad8970f6af4d506f5b75ed3edd7bdcca9).
  - Lint: ExpiredTargetSdkVersion disabled with rationale (sideload-only alpha).
- Verification (honest labels):
  - REAL host runtime drill (repo's actual extractor + real proot 5.3.0 + pinned
    artifacts): both ABIs extract with 0 skipped entries and 0 integrity problems;
    real bash executes inside the extracted rootfs: `uname -s` -> `Linux`,
    `LINUXIFY_OK`, perl chain OK, rc=0. The broken extractor previously reproduced the
    EXACT device error on amd64 (same message, same proot hint block) — fix verified
    by differential drill.
  - Unit tests: 32/32 PASS (23 prior + 9 new regression tests incl. `..`-loader links,
    hardlink order, archive-root-relative hardlink resolution, RootfsIntegrity
    verify/repair cycle, absolute-target rootfs-relative resolution).
  - Build: assembleDebug + assembleRelease BUILD SUCCESSFUL (Temurin JDK 21, SDK 35);
    release APK 12,417,925+ B; targetSdkVersion:'28' confirmed via aapt; both APKs
    verified with apksigner (same cert).
  - On-device confirmation of alpha.3 (install with zero errors -> PTY shell ->
    persistence drill) is the user's next step and closes issue #3.
- Artifacts: linuxify-0.1.0-alpha.3-release.apk / linuxify-0.1.0-alpha.3-debug.apk
  (GitHub release v0.1.0-alpha.3 + build outputs).

### Task 8 — fix third on-device install failure (execve EACCES): proot's un-bundled ELF loader (v0.1.0-alpha.4)
- Status: DONE (build verification + REAL host runtime drill complete; on-device confirmation pending with user)
- Date: 2026-10-17
- Trigger: user's third real device drill — all 3 exec probes failed:
  `proot error: execve("/usr/bin/uname"): Permission denied` while
  `integrity clean - failure is at exec level` (rootfs contents verified fine).
- Root cause (EVIDENCE-BASED, from the pinned proot binary + upstream source, not guessed):
  - Termux builds proot with `PROOT_UNBUNDLE_LOADER=$PREFIX/libexec/proot` — the tracer
    does NOT embed its loader. `src/execve/enter.c get_loader_path()`:
    `getenv("PROOT_LOADER") ?: PROOT_UNBUNDLE_LOADER "/loader"`. proot rewrites EVERY
    guest execve to exec that loader (static helper that maps the guest ELF + interpreter).
  - Linuxify never set PROOT_LOADER and never shipped the loader → proot fell back to the
    hardcoded `/data/data/com.termux/files/usr/libexec/proot/loader`:
    - alpha.2 device run: path ABSENT (no Termux) → kernel execve ENOENT, reported as
      `execve("/usr/bin/bash"): No such file or directory` (guest path in the message is cosmetic);
    - alpha.3 device run: Termux present on the device → path exists but is inside a
      FOREIGN app's 0700 data dir → kernel execve EACCES, reported as
      `execve("/usr/bin/uname"): Permission denied`.
    One root cause explains both runs and the ENOENT→EACCES flip.
  - Proof chain: strings of the packaged libproot.so (PROOT_LOADER, the Termux path,
    `'the loader was not found or doesn't work'` hint) + upstream termux/proot source +
    termux-packages build.sh (`export PROOT_UNBUNDLE_LOADER=...`).
- Fixes:
  - Packaged the loader from the SAME pinned proot debs (sha256-verified) as
    `jniLibs/libproot-loader.so` for both ABIs (arm64 18,136 B
    `cbdef0e6…`, x86_64 18,368 B `a836b056…`; static EXEC ELF, no DT_NEEDED → no patching;
    PT_LOAD align 0x4000). Provenance recorded in runtime-binaries.json.
  - `LinuxRuntime.buildEnv()` now sets `PROOT_LOADER=<nativeLibraryDir>/libproot-loader.so`
    (covers boundedExec, execInRootfs, PTY shell via PtyTerminal).
  - Install gate + preflightProot fail fast when the loader is missing/non-executable —
    BEFORE any rootfs download.
  - Mode-fidelity hardening (defense in depth for the EACCES family):
    SafeTarExtractor now applies FULL tar mode bits to files AND directories
    (RootfsIntegrity.applyTarMode, POSIX rwx; owner rw floor for files, rwx floor for
    dirs); the permission-losing copy paths were fixed (hardlink second pass and symlink
    fallback previously landed 0660 — hardlinked executables like perl5.38.2/uncompress
    would have been unexecuable); RootfsIntegrity.repair deletes read-only leftovers and
    applies full modes.
  - NEW exec-bit self-heal: `RootfsIntegrity.ensureExecutableBits()` guarantees +x on
    exec-critical real files (symlink chains resolved) and every ELF under bin dirs —
    runs BEFORE the first probe and again in the probe failure path (repair → retry).
    Data/config files (resolv.conf, os-release) are deliberately untouched.
  - Honest diagnostics when all probes still fail: mode/size of uname + loader on disk,
    plus a DIRECT host execve discriminator (EACCES → filesystem/SELinux denial
    independent of proot; ENOENT → permissions fine, proot-pipeline specific).
- Verification (honest labels):
  - Unit tests: 37/37 PASS (32 prior + 5 new: non-exec stays non-exec, hardlink copy
    executable, exec-bit sweep fixes ELF in bin / loader behind symlink chain, sweep
    leaves text configs alone).
  - REAL host runtime drill (repo's actual extractor + proot 5.3.0 + pinned amd64
    artifact): 3,416 entries extracted, 0 skipped; mode fidelity confirmed (uname/bash/
    perl-hardlink exec, etc/hosts not); integrity 0 problems; ensureExecutableBits 0 on
    fresh extract (no false positives); bash -lc uname → Linux + LINUXIFY_OK; direct
    argv exec ok; file created; PERSISTENCE_OK across proot exit+relaunch; dash + hardlinked
    perl ok; SELF-HEAL drill: breaking +x on uname + loader real file → sweep repaired
    exactly those 2 → guest exec ok again. Script: scripts/host_drill.sh (server).
  - Build: assembleDebug + assembleRelease BUILD SUCCESSFUL (Temurin JDK 21.0.12.1,
    SDK 35, restored from scratch per RECOVERY). release APK 12,425,634 B
    (sha256 20c6e83f…), debug APK 18,600,676 B (sha256 58c9774b…); apksigner: BOTH use
    the stable alpha.3 identity (cert sha256 8f55e466…cca9) → alpha.4 installs OVER
    alpha.3 WITHOUT uninstall; aapt: versionCode 4, targetSdk 28; in-APK loader digest
    == pinned digest.
  - On-device confirmation of alpha.4 (install with zero errors → PTY shell → persistence
    drill) is the user's next step and closes issue #3.
- Artifacts: linuxify-0.1.0-alpha.4-release.apk / linuxify-0.1.0-alpha.4-debug.apk
  (GitHub release v0.1.0-alpha.4 + build outputs).


---
Task ID: 9
Agent: Z (main agent, continuation session)
Task: Fix on-device crash: after a successful install ("Linuxify ready"), tapping Terminal / Projects / Services crashed the app ("keeps stopping"). No logcat available from the user.

Work Log:
- Synced repo to origin/main @ fff3761 (alpha.4) and verified the RELEASED APKs byte-level: all shipped alphas contain their native libs (alpha.4: 6 arm64 libs incl. libproot-loader.so) — packaging was NOT the problem; the user's install genuinely passed exec verification.
- Read every screen on the three crashing paths + vendored TerminalSession/JNI + managers. Static analysis found no deterministic fault for Projects/Services (0 projects -> no proot call at all), so the exact root cause is only observable on the device.
- Decision (per zero-opaque-errors mandate): make the app self-diagnosing instead of shipping another blind fix. Shipped in alpha.5:
  * CrashReporter (global UncaughtExceptionHandler): renders trace with app/device context, persists to files/runtime/logs/last-crash.txt, opens CrashReportActivity (plain-Android, :crashreport process, Compose-free so it draws even when Compose crashes) with Copy + Restart buttons; exits without the system dialog when reporting succeeded.
  * TerminalScreen: pre-loads libtermux.so in a guarded remember{} (load failure -> honest on-screen error), wraps session spawn in try/catch -> ErrorState + Retry instead of crash.
  * AppViewModel: CoroutineExceptionHandler (bgGuard) on every launch; runCatching on all manager calls (projects/services/apt/home facts) — background errors log nonfatal + surface as UI messages, never kill the process.
  * Ready-gate hardening: MainActivity requires ready marker AND proot+loader binaries; RootfsInstaller clears stale markers and auto-repairs; SetupScreen Ready branch repairs when binaries are missing.
  * HomeScreen: "Runtime binaries" fact row (ok / missing — reinstall required).
- Build env on this host: Android SDK (platform-35, build-tools 35.0.0) + Temurin JDK 21 (system JRE lacks jlink).
- Verification: :app:testDebugUnitTest 37/37 PASS; assembleRelease + assembleDebug SUCCESS; APK content verified (6 arm64 libs incl. libproot + libproot-loader); apksigner cert SHA-256 8f55e466... == alpha.4 cert (overwrite install works, no uninstall).
- Honest limitation: still no device/emulator on this host — the crash's exact stack will be produced by the crash reporter on the user's device on first reproduction.

Stage Summary:
- Deliverable: linuxify v0.1.0-alpha.5 (release + debug APKs, stable signing, versionCode 5).
- Next device drill: install alpha.5 over alpha.4 -> tap Terminal/Projects/Services. If any screen still fails, the app now shows the FULL stack trace in-app with a Copy button — paste it into the chat for a pinpoint fix.

---
Task ID: 11
Agent: Z (main agent, continuation session)
Task: User supplied the first on-device crash report (via the alpha.5 in-app reporter): tapping Terminal NPEs in TerminalView.updateSize -> TerminalViewClient.onEmulatorSet() on a null client. Projects and Services also crashed. Root-cause all three and ship a fix.

Work Log:
- Root cause 1 (Terminal, stack-verified from device): TerminalScreen's AndroidView factory never called setTerminalViewClient(). First layout -> onSizeChanged -> updateSize() -> mClient.onEmulatorSet() -> NPE before the view ever drew.
- Root cause 2 (Services, statically proven): LazyColumn nested inside a verticalScroll Column -> measured with infinite max height -> IllegalStateException. Deterministic since alpha.1; nobody had tapped Services on-device before.
- Root cause 3 (Projects): full static audit found NO deterministic fault (pure-Compose path, guarded VM calls). Report exists on the user's device. Added a Home "Diagnostics" card (persisted last-crash.txt -> one-tap View report / Dismiss) so any remaining crash is relayable without adb.
- Fixes shipped: TerminalScreen now sets a full LinuxifyTerminalViewClient in the factory BEFORE attachSession; CTRL/ALT extra-key toggles are wired into the real key pipeline via readControlKey()/readAltKey() (they previously only changed highlight state); vendored TerminalView.updateSize() null-guards mClient (graceful no-op on call-order regressions); ServicesScreen uses a plain bounded Column (services list is small; correctness > virtualization); PackagesScreen dead LazyColumn import removed.
- K2 quirk handled: `refreshTick++` inside the AndroidView factory's apply{} block resolved inc() ambiguously -> replaced with explicit reassignment + comment.
- Build env rebuilt on this host (Temurin JDK 21.0.12.1 + SDK platform-35/build-tools 35.0.0); 37/37 unit tests; assembleRelease SUCCESS.
- Verified: versionCode 6 / versionName 0.1.0-alpha.6; apksigner cert SHA-256 8f55e46675c2...cca9 == alpha.5 cert -> overwrite install, no uninstall; arm64-v8a present.

Stage Summary:
- Deliverable: linuxify v0.1.0-alpha.6 (release APK; stable signing maintained).
- On-device drill: install alpha.6 over alpha.5 -> tap Terminal (expect prompt), Projects, Services (expect list). If anything still fails: Home -> Diagnostics -> View report -> Copy -> paste in chat.

---
Task ID: 13
Agent: Z (main agent, continuation session)
Task: User report on alpha.7 — long-press terminal toolbar's Copy and Paste items do nothing. Diagnose and ship.

Work Log:
- Re-synced the clone: local HEAD was alpha.5 + a stale uncommitted pre-alpha.7 draft; remote main was the canonical alpha.7. Hard-reset to origin/main (80fa703).
- Traced the full long-press path in vendored Termux code: onLongPress -> startTextSelectionMode -> TextSelectionCursorController ActionMode; Copy -> TerminalSession.onCopyTextToClipboard(), Paste -> TerminalSession.onPasteTextFromClipboard() — both delegate to TerminalSessionClient, where LinuxifySessionClient had EMPTY implementations. Menu rendered; taps were silent no-ops. Exactly matches the user report.
- Restored the disposable build env (Temurin JDK 21.0.12.1+1 at ~/toolchain, SDK platform-35 + build-tools 35.0.0 + cmdline-tools at ~/android-sdk, local.properties).
- Fix: implemented both client callbacks in TerminalScreen.kt — Copy writes ClipData to system ClipboardManager (toast on <13; system overlay on 13+), Paste coerces the primary clip and feeds TerminalEmulator.paste() (bracketed paste + escape stripping).
- Hardened: null-guard for degenerate selections in the copy path (getSelectedText() can return null -> trim() NPE); removed the dead "More…" toolbar item (TerminalView has no context-menu listener — it could never work).
- v0.1.0-alpha.8 (commit c79c1a6, tag pushed, release id 416514583): assembleDebug + assembleRelease + testDebugUnitTest 45/45 on this server; apksigner cert SHA-256 8f55e466…cca9 verified on both APKs (overwrite-install over alpha.7, rootfs kept); release asset linuxify-0.1.0-alpha.8-release.apk (12,441,362 B) uploaded; APKs also delivered to /home/z/my-project/download/.
- First asset-upload attempt targeted no release id and failed silently — re-uploaded against release id 416514583 and verified the asset listing.

Stage Summary:
- Copy/Paste in the terminal now hit the real Android clipboard; empty-selection crash vector closed; dead "More…" removed.
- Runtime-first checkpoint: build + unit + signature verification on server; the on-device clipboard drill (long-press -> Copy -> long-press -> Paste) is with the user's device.

---
Task ID: 14
Agent: Z (main agent, continuation session)
Task: User confirmed alpha.8 Copy/Paste works on device; requested a floating mini icon in the terminal that shares the whole terminal log to any app/AI.

Work Log:
- ENVIRONMENT EVENT: at turn start the local clone had ROLLED BACK to the pre-alpha.8 snapshot (HEAD=alpha.5 + stale draft; reflog had no trace of this session's work; JDK/SDK/download artifacts wiped). Remote GitHub was unaffected — commits c79c1a6/354ee6d, tag v0.1.0-alpha.8 and its release asset were already pushed. Recovery: hard-reset local to origin/main, reinstalled Temurin JDK 21.0.12.1+1 + Android SDK (platform-35/build-tools 35.0.0), re-downloaded the alpha.8 release APK into download/ (byte-identical 12,441,362 B). LESSON: origin/main is the only durable state; commit+push before turn end.
- Verified vendored TerminalBuffer.getTranscriptText() exists (Termux's own transcript API) — scrollback + screen, colors stripped.
- New TerminalLogShare (pure, JVM-testable): one-line factual header (environment + session label) so a bare paste into an AI chat carries context; 500k-char cap keeping the MOST RECENT tail with an explicit truncation notice (crashes live at the tail); at-cap is a no-op.
- TerminalScreen: AndroidView wrapped in a Box; ShareLogButton (44 dp translucent circle, bottom-end, contentDescription set) -> shareTranscript(): captures transcript, builds payload, launches ACTION_SEND chooser; empty-transcript guard toasts; chooser launch failure reported non-fatally via CrashReporter.
- ic_share.xml vector added (no new dependency).
- 3 new unit tests: 48/48 pass. assembleDebug + assembleRelease clean; versionCode 9.
- v0.1.0-alpha.9 (commit 1801d40, tag pushed, release id 416622078): release APK 12,444,530 B, cert SHA-256 8f55e466…cca9 verified; asset uploaded against the release id this time (alpha.8's silent-miss mistake not repeated); APKs in /home/z/my-project/download/.

Stage Summary:
- Shipped: two-tap share of the full terminal log to any chat/AI app via the system share sheet.
- Honest split: payload logic device-verified by unit tests; the on-device share-sheet drill (tap icon -> paste into an app) is with the user.

---
Task ID: 15
Agent: Z (main agent, continuation session)
Task: User asks — (1) docs updated everywhere to current state, (2) app must run in background (top priority), (3) internet fluctuations must auto-recover, (4) notification-bar notice when a run completes, (5) full linuxify-CLI parity check.

Work Log:
- Located the companion CLI (Bilal140202/linuxify, TypeScript on Termux+proot); cloned and enumerated all 29 command files + README command surface for the audit.
- BACKGROUND RUNNING (the priority): built RuntimeForegroundService — FGS with persistent LOW-importance notification ("Linuxify is running"), partial wake lock (Doze freezes network/CPU with screen off otherwise; Termux's model), START_STICKY, 2-arg startForeground (targetSdk 28 needs no type), auto-release when the last session/service ends. Manifest: FOREGROUND_SERVICE + POST_NOTIFICATIONS + WAKE_LOCK + <service>.
- Fixed the orphan-session architecture that made backgrounding leak PTYs: sessions moved from composable remember{} into process-scoped TerminalSessionRegistry (CopyOnWriteArrayList) with ONE shared LinuxifySessionClient (boundView routing + onUiInvalidated hook); TerminalScreen now reattaches to the same shell (scrollback intact) instead of respawning; the private client class was deleted from TerminalScreen.kt.
- RUN COMPLETED NOTIFICATIONS: RuntimeNotifier with channels linuxify_runtime (persistent) / linuxify_events (completions); events only when LinuxifyApp.isInForeground == false (ActivityLifecycleCallbacks counter on Application); service exit-watcher thread posts real exit codes; user-initiated stops suppressed via userStopped flag; ic_stat_notify ">_" glyph added.
- NETWORK AUTO-RECOVERY: downloadResumable hardened from 5 linear retries to 8 attempts with exponential backoff (2s→30s) + ±20% jitter + connection disconnect on error; Range resume + checksum verification unchanged.
- CLI PARITY: wrote docs/CLI_PARITY.md — honest matrix of all 29 CLI commands (COVERED/PARTIAL/DEFERRED/N-A with reasons). Verdict: day-to-day phone workflow fully covered; gaps: curated tool registry UX, one-tap apt upgrade, installed-packages list, snapshot/gc, self-update, multi-distro.
- DOCS: README "What it does"/"Status" rewritten for alpha.10 (background, notifications, network recovery, share-log, CLI_PARITY link); SESSION_STATE.md fully rewritten (release ledger, background architecture notes, rollback standing rule, on-device verification ledger, next actions).
- v0.1.0-alpha.10: 48/48 unit tests; assembleDebug + assembleRelease clean; cert 8f55e466…cca9 verified; manifest permissions verified in the compiled APK; new classes confirmed in Kotlin output.

Stage Summary:
- Shipped: real background running (the user's top priority), completion notifications, hardened download recovery, full CLI parity audit, all docs current.
- Honest split: everything above is build-verified + unit-tested; the on-device drills (notification appears, shell survives backgrounding, wake lock keeps servers alive) are the user's next step.

---
Task ID: 16
Agent: Z (main agent, continuation session)
Task: User reports every terminal open still starts with "groups: cannot find name for group ID 3003/9997/20495/50495" before the prompt — despite alpha.7's claimed fix.

Work Log:
- ENVIRONMENT EVENT (recurring): local clone rolled back again (HEAD=alpha.5; JDK/SDK wiped). Recovered: hard reset to origin/main (which already had Task 15's alpha.10 commit 72af460 — CODE ONLY: no tag, no GitHub release, no asset; the previous session died before the release step) + reinstalled JDK 21.0.12.1+1 and Android SDK.
- ROOT-CAUSED the warnings with a real reproduction: downloaded proot v5.5.0 static (GitHub API, SHA-256-verified against SHA256SUMS) + the REAL ubuntu-base-24.04.5 amd64 tarball; ran the app's exact proot flags + login bash under `script` for a PTY. REPRODUCED the exact symptom. Mechanism: /etc/profile sources /etc/bash.bashrc, whose sudo-hint block runs $(groups) on EVERY interactive login shell unless $HOME/.sudo_as_admin_successful exists; coreutils warns once per supplementary gid missing from the guest /etc/group. proot passes the kernel supplementary list through (user namespaces would strip it — that's why an unshare-based repro shows nothing).
- Explained the alpha.7 miss: it patched gids from /proc/self/groups alone; the empty/partial read on the Android 16 device left all four supplementary gids unmapped, and the failure was silent. Lesson recorded: silent no-ops must be logged; guest-side fixes need an explicit on-device check.
- Verified BOTH fix layers on the host, independently: (1) appending linuxify-g<gid>:x:<gid>: to the guest /etc/group → clean prompt; (2) /root/.sudo_as_admin_successful marker + HOME=/root → clean prompt even with the gid unmapped. Also proved a wrong HOME defeats the marker (control run) — the app always sets HOME=/root.
- Implemented GuestPatches (new object, idempotent, memoized per process): applies group patch + marker + rewrites etc/profile.d/linuxify.sh (single owner of that file). GuestGroups gid discovery hardened: /proc/self/groups + /proc/self/status "Groups:" fallback + static well-known floor (3001/3002/3003/3004/9997). applyAll now runs at THREE points: configureUserspace (fresh install), AppViewModel.init (every start; failure now CrashReporter-logged, never silent), PtyTerminal.newShellSession (before every spawn, memoized).
- IN-SHELL RUN-COMPLETED NOTIFICATIONS (completes Task 15's feature, which only covered session/service exit): GuestPatches' profile installs PROMPT_COMMAND printf '\a' (interactive shells only); vendored emulator routes BEL → TerminalSession.onBell → LinuxifySessionClient.onBell; RunCompletionGate (pure, unit-tested) notifies only while backgrounded, throttled to 1/3 s. Host-verified the hook emits BEL per prompt (cat -A shows ^G).
- RuntimeForegroundService: fixed a wakelock leak — onStartCommand re-acquired without releasing on START_STICKY restart.
- Kotlin gotcha recorded: "\$" is NOT an escape inside raw strings (it leaks a backslash into generated shell files and "${" still opens a template) — literal dollars written via ${'$'}.
- 61/61 unit tests (13 new: GuestPatches behavior + idempotency + memo + legacy-profile upgrade, gid parsers, runtimeGids floor, RunCompletionGate rules). assembleRelease clean; versionCode 11; cert 8f55e466…cca9 verified via apksigner.

Stage Summary:
- Shipped: clean terminal prompt (two independent, host-verified fix layers), in-shell "command finished" notifications, wakelock leak fix; alpha.11 carries the previously unreleased alpha.10 feature set.
- Honest split: reproduction + both fixes verified on the HOST against the real ubuntu-base; the on-device drill (clean prompt on the moto g85, notification on backgrounded command completion) is with the user.

---
Task ID: 17
Agent: Z (main agent, continuation session)
Task: User directive — build a Dependency & Package Recovery Manager: BASE env (curl, ca-certificates) by default; optional DEVELOPER profile (git, nodejs, npm); package repair ONLY when actually inconsistent (never blind dpkg --configure -a); IPv4 fallback on downloads; per-tool install verification; failure reporting with practical recovery commands.

Work Log:
- Designed and implemented pkg/EnvManager.kt: EnvCore (pure, JVM-tested: profiles, probe parsing, recovery-plan builder, staleness rule, verification rules) + EnvManager(ctx, apt) (runtime executor). EnvManager SHARES the AppViewModel AptManager instance so all apt/dpkg writes serialize on one mutex.
- Audit = ONE combined proot spawn: distro/arch/free-mb/DNS/dpkg --audit + updates dir/apt-get check/command -v tool loop. Honest degradation: unreadable fields stay null/false, unknown lines ignored.
- Recovery plan: healthy audit yields an EMPTY plan (the "never blind" rule); interrupted dpkg -> dpkg --configure -a; broken deps -> apt-get --fix-broken install; both -> that order.
- AptManager: added Kind.REPAIR + repairDpkg()/fixBroken(); refreshIndex/install now carry -o Acquire::ForceIPv4=true (user-endorsed IPv4 fallback on all apt network ops).
- Install flow: audit -> recover-if-needed -> index refresh only if stale (no InRelease/Release or >24h) -> install ONLY missing profile packages -> on failure fix-broken + ONE retry -> verify each tool via execInRootfs(binary, --version) exit 0 + non-empty stdout -> DONE else FAILED with hint. Every step failure reports title + practical recovery command.
- Auto integration: startInstall() auto-installs BASE right after fresh rootfs install (offline-tolerant, retry via Packages screen); search-screen installPackages() runs ensureHealthyOnly() first (probe failure never blocks a plain install).
- UI: Packages screen "Environment" card — audit summary, health chip, repair button (only when unhealthy), BASE/DEVELOPER rows with missing lists, verification chips, step list + live log.
- 13 new unit tests (74 total) — all pass. assembleRelease clean (one Gradle daemon crash mid-build; rebuild succeeded); versionCode 12; cert 8f55e466…cca9 verified; APK in download/.

Stage Summary:
- Shipped: the Dependency & Package Recovery Manager exactly per the user's table — automatic deps where they belong, recovery only on evidence, IPv4 fallback, verification, honest failure reporting.
- Honest split: all logic build-verified + unit-tested on server; the on-device drills (Check environment card, Developer tools install with verification chips, auto-curl after fresh install) are with the user.

---
Task ID: 18
Agent: Z (main agent, continuation session)
Task: User directive — Universal Software Management System, NOT an OpenCode installer: general-purpose core + ecosystem adapters (APT/npm/pip/static binaries) + catalog as data + unrestricted terminal. OpenCode = one catalog entry among many.

Work Log:
- ENVIRONMENT EVENT (3rd time, MID-SESSION this time): HEAD rolled back to alpha.5 mid-turn; JDK/SDK wiped; download/ reduced to alpha.2/alpha.5. origin/main held alpha.12; the turn's untracked new files survived. Recovered: git fetch + reset --hard origin/main; Temurin JDK 21.0.12.1+1 + Android SDK (cmdline-tools, platform-35, build-tools 35.0.0) reinstalled; alpha.12 APK re-downloaded from the GitHub release. LESSON recorded: commit+push EARLY, not just at session end.
- NEW ARCHITECTURE: pkg/Ecosystems.kt (InstallSpec sealed: Apt/Npm/Pip/Binary; resolveSpec = first usable now, else first auto-provisionable, else honest null; command builders; installed-state probe parser). pkg/Catalog.kt (24 curated entries, categories System/Dev/AI/Network/Media/Data/Text, every entry unit-enforced to have specs+verify+category). pkg/CustomTools.kt (validated user entries, slug ids, safe package regexes, JSON persistence, merge never shadows builtins, corrupt JSON -> empty).
- pkg/SoftwareManager.kt: single-lock orchestrator sharing the AppViewModel AptManager mutex. Install flow: audit (via new EnvManager.rawAudit) -> resolve spec -> prereqs (Node.js profile for npm tools; python3-pip for pip, with --break-system-packages per PEP 668) -> adapter install -> verify (binary --version in guest) -> DONE/FAILED with recovery hints. Binary adapter: raw files only (archives rejected in v1), sha256-pinned, 3 attempts + backoff, 128 MB cap, host-side install into <rootfs>/usr/local/bin. BinaryDownloader with real sha256 verification.
- CHECKSUM PINNING: yq 4.44.3 + jq 1.7.1 arm64 downloaded over TLS and cross-verified against upstream checksum manifests (yq checksums file, jq sha256sum.txt) before being pinned in Catalog.kt.
- One-tap Update & upgrade: AptManager Kind.UPGRADE + fullUpgrade() (noninteractive, IPv4 fallback), AppViewModel.upgradePackages() = health check -> update -> upgrade.
- UI: PackagesScreen REPLACED by SoftwareScreen (route "packages" unchanged, Home button relabeled "Software"): Environment card + Update & upgrade, software op card with live log, catalog (search + category chips + Install/Remove per entry + custom delete), Add-custom-tool dialog (apt/npm/pip chips), unrestricted full-APT search section below.
- 27 new unit tests (101 total, 0 failures) — includes catalog data-integrity rules and custom-tool safety validation. testImplementation org.json:json:20240303 added for JVM JSON tests (android.jar stub throws not-mocked).
- Compose/Kotlin fixes: boundedExec trailing lambda must be named onOutput (last param is shell); continue not allowed inside repeat{} lambda (use for-loop); Column takes verticalArrangement, not horizontalArrangement.
- assembleRelease clean after one transient mid-build failure (re-run resumed); versionCode 13; aapt confirms versionName 0.1.0-alpha.13; cert 8f55e466…cca9 verified; APK in download/.

Stage Summary:
- Shipped: the Universal Software Management System — shared core, four ecosystem adapters, searchable catalog, custom tools, one-tap upgrade, unrestricted terminal. Build+unit verified on server; the six on-device drills (catalog install, npm auto-prereq via OpenCode, sha256 binary path, upgrade, custom tool, manual terminal installs) are with the user.

---
Task ID: 19
Agent: Z (main agent, continuation session)
Task: Alpha.13 continuation, git audit & release verification — establish real repo state, audit the Universal Software Management System implementation against the actual code, use GitHub issues as the task ledger, fix + verify incrementally, verify release artifacts.

Work Log:
- PHASE 1 (git audit): branch main; HEAD 7e5a553e == origin/main (in sync after fetch); 0 local-only / 0 remote-only commits; working tree clean (no staged/unstaged/untracked); stash@{0} "stale-worktree-backup-pre-alpha4" preserved untouched. All 13 releases + 13 tags verified via API (alpha.13 release id 416902540, asset linuxify-0.1.0-alpha.13-release.apk 12,514,586 bytes, uploaded). No incomplete prior-session work found.
- PHASE 2 (code audit): read SoftwareManager/Ecosystems/Catalog/CustomTools/SoftwareScreen/AppViewModel/AptManager/EnvManager + LinuxRuntime exec layer + all 101 tests. Baseline: testDebugUnitTest 101/101 PASS. Checklist result: PASS for shared orchestration, 4 adapters, sha256 pinning, arch gating, PEP 668, root assumptions, non-false-positive success paths, terminal preservation. 8 defects found and filed (below).
- PHASE 3 (issue ledger): created issues #13-#20 (no duplicates existed; #3 remains open/blocked as the standing device-verification issue).
- #13 FIXED (d7c5acd): AptManager.search built its apt-cache ERE with Regex.escape -> PCRE \Q…\E, which glibc ERE reads as literal Q…E; EVERY ordinary search returned "No results". Host-verified against the same regex engine (grep -E '^htop|htop' matches; '^g\+\+|g\+\+' matches). New pure AptCommands.ereQuote/safeSearchTerm/searchCommand + hasInstalledStatus; isInstalled() rebuilt as argv exec (execInRootfs dpkg -s) — the old form was always-false AND a latent injection hazard. +10 tests.
- #20 FIXED (629b3bc): npm scoped packages @scope/pkg accepted for npm custom tools only; apt/pip still reject leading '@'; charset stays injection-safe. +1 test.
- #14 FIXED (cf86fe9): CustomTools.fromJson re-validates everything read from custom_tools.json (ecosystem whitelist + package/binary charset) and SKIPS invalid entries; Ecosystems.SAFE_TOKEN_RE is the single shared identifier rule; installedProbeScript refuses to interpolate unsafe names (defense in depth). Hostile-JSON tests with ; $( ) ` variants. +3 tests.
- #18 FIXED (ec188b5): binary removal idempotent — absent file is success (old delete()&&short-circuit reported false failure).
- #19 FIXED (73f82e9): verify-failure hint shows the REAL exit code (VerifyResult.exitCode added, set in both managers); version line reported separately as "Last output:". Note: the SoftwareManager-side of #19 rode in commit ec188b5 (both edits were in the same working file when staged); EnvManager-side in 73f82e9. Both commits pushed; no history rewritten.
- #17 FIXED (5e983ee): Ecosystems.installCandidates() = all specs in preference order; resolveSpec() is its first element (identical selection semantics, one implementation). SoftwareManager.install() walks ALL candidates with honest per-attempt logging and phase reset between attempts — jq's sha256-pinned static-binary fallback is now reachable when APT fails. resolveSpec≡first-candidate property test over the full catalog. +4 tests. Stale "Packages >" hints updated to the Software screen.
- #15+#16 FIXED (559bef0): SoftwareScreen now renders the APT operation card (kind-aware label, state chip, exit code, live log) — upgrade/search-install/repair feedback visible; installViaApt's "APT operation card above" hint is truthful. Catalog probe keyed on the STABLE allEntries set instead of filtered ids — no more one proot spawn per search keystroke.
- DOCS + VERSION: README software bullet updated (fallback, scoped npm, APT log card) — verified behavior only; SESSION_STATE.md rewritten for alpha.14; versionCode 14 / versionName 0.1.0-alpha.14.
- FINAL TEST/BUILD: testDebugUnitTest 119/119 PASS (111 after #13, +1 #20, +3 #14, +4 #17). assembleRelease PASS (see SESSION_STATE for APK checksum + cert verification).

Stage Summary:
- All 8 audit issues (#13-#20) closed with verified fixes; suite grew 101 -> 119, 0 failures; release-blocking defect = the always-broken APT search.
- alpha.13's published release is left untouched (traceability rule); the fixes ship as a NEW v0.1.0-alpha.14 release.
- Still with the user (device drills): alpha.12 (e)-(h), alpha.13 (i)-(vi) plus alpha.14 additions — search actually returns results now, APT card shows upgrade feedback.

---
Task ID: 22
Agent: Z (main agent, continuation session)
Task: Fix the Projects screen (issue #22) — Projects tab landing on Terminal (nav bug), open-in-project plumbing dropped, dead/buggy gitStatus, 2N probe spawns. Plus: check how Services/Projects menus work (user questions, answered from code).

Work Log:
- User report: after using "Open terminal" on a project, tapping the Projects tab showed the TERMINAL screen with a session. Root cause: bottom-nav clicks used popUpTo(home){saveState=true}+restoreState=true; "Open terminal" pushed `terminal` on top of `projects`, and the next Projects tap RESTORED that whole saved stack (terminal back on top). This is the documented Navigation-Compose saveState/restoreState gotcha.
- Fix (MainActivity): shared openTab() = navigate(route){popUpTo("home"){inclusive=false}; launchSingleTop=true} for bottom bar, nav rail, and Home quick-jumps. No saveState/restoreState — tabs ALWAYS show their own root screen. "files" stays a stacked drill-down (Back works); a tab tap still wins over it. Projects→Terminal is a tab switch now, not a stack push.
- Fix (open-in-project): the project name was passed by ProjectsScreen but DROPPED by MainActivity; TerminalScreen.spawnSession() never passed workdir (PtyTerminal already supported it via proot -w). New: AppViewModel.requestTerminalInProject(name) sets a pending guest dir (/root/projects/<name>), TerminalScreen consumes it once on entry and spawns a NEW session in that dir even when sessions exist (explicit user action); plain tab visits keep the reattach behavior. requestFilesInProject(name) + FilesScreen consume-once start dir = "Browse files" opens IN the project (was hardcoded /root).
- Fix (ProjectsManager): removed dead+buggy gitStatus() (zero callers; ran git status WITHOUT -C — would have reported on /root). Batched git probing: ONE proot spawn per refresh (was 2 per project) running a guest shell loop emitting name\tbranch|-\tdirty|- TSV; pure parseGitProbe() parser + 11 new unit tests (ProjectsGitProbeTest) covering repos, clean repos, non-repos, slashes in branches, detached HEAD, malformed/blank lines, CRLF, non-numeric counts, empty output. Probe failure degrades to "no git info" (same display as the old per-dir failure path). Script logic verified host-side (tab output, dirty counts, HEAD for uncommitted repos).
- Also carried the API-34 Path.of→Paths.get lint fix onto main (pre-existing latent NoSuchMethodError on Android 10-13 in RootfsIntegrity/SafeTarExtractor; same change as 88813ad on audit/proprietary-distribution — cherry-picked so lintRelease passes on main).
- Note: audit-branch-only files (LICENSE, assets/licenses, LicensesAssetsTest) were deliberately NOT carried — they live on audit/proprietary-distribution pending merge.
- Verification: testDebugUnitTest 130/130 PASS (119 + 11 new); lintRelease PASS; assembleRelease PASS (com.linuxify.app 0.1.0-alpha.14); APK badging re-checked.
- Git: branch fix/projects-screen pushed (verified 459505c), fast-forward merged into main, main pushed and verified (459505c = origin/main). Issue #22 auto-closes via "Fixes #22" in the merge commit; evidence comment posted.

Stage Summary:
- User-facing: Projects tab is ALWAYS the project list; "Open terminal" starts the shell INSIDE the project (proot -w); "Browse files" opens Files in the project; listing costs 1 proot spawn instead of 2N. Awaiting user device drill: (a) Projects → Open terminal → shell prompt is in /root/projects/<name>; (b) tap Projects tab → project list shows immediately, never the terminal; (c) Browse files → Files starts in the project dir; (d) services/menu behavior unchanged.
- origin/main = 459505c9be62c8baea4576802e3b5b7cd5f79e6d. audit/proprietary-distribution still holds the licensing audit deliverables (b997868, 955879f, ad7a350, 88813ad) pending merge.
