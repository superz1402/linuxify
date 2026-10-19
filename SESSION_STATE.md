# SESSION_STATE.md — Linuxify Android

Concise handoff for the next agent session. Current as of 2026-10-19 (Task 21 —
licensing audit COMPLETE: re-verification, lint fix, APK verification, issue
#21 comment; branch `audit/proprietary-distribution`).

## COMPLETED TASK — issue #21: licensing & distribution audit (verdict B)

- FINAL VERDICT: **B — proprietary closed-source APK FEASIBLE WITH SPECIFIC
  CHANGES**. The ONLY blocker is the in-process GPL terminal stack (20
  vendored `com/termux/**` Java files compiled into classes.dex + GPL
  `libtermux.so` loaded via `System.loadLibrary` into the app process, plus
  direct Linuxify imports/uses of `com.termux.terminal.*` / `com.termux.view.*`).
  That makes the current APK a GPL-3.0 combined work → Kotlin/Compose source
  cannot stay private TODAY. After replacing/removing that stack (or a written
  Termux licence grant), the app's own sources can stay private.
- NOT a blocker: proot (`libproot.so` + loader, GPL-2.0-or-later) is exec'd
  as a SEPARATE process via ProcessBuilder/fork+exec — aggregation, not
  linking; obligations (notices + corresponding-source for the MODIFIED proot
  binaries) are satisfiable without exposing app code. talloc (LGPL-3.0) and
  libandroid-shmem (BSD-3-Clause) are proot-only DT_NEEDED deps — same story.
  Ubuntu rootfs is runtime-downloaded, never conveyed by the developer.
- Re-verification (second session, after another host rollback — JDK/SDK and
  scratch scripts wiped, repo intact):
  - Upstream re-fetched: termux-app v0.118.0 LICENSE.md verbatim GPLv3-only;
    proot COPYING GPL-2.0 + tracee.c "or later" header; libandroid-shmem
    BSD-3-Clause; talloc-2.5.0.tar.gz re-downloaded (SHA-256 matches pin,
    LICENSE = LGPL-3.0 → Termux metadata "GPL-3.0" imprecise, as audited).
  - Vendored diff re-run: 17/20 byte-identical; 3 modified exactly as
    audit §6.2 (TerminalView 8 lines, TextSelectionCursorController 19,
    TextSelectionHandleView 2).
  - All 10 jniLibs digests match `runtime-binaries.json` pins.
  - `testDebugUnitTest`: 124/124 PASS (119 alpha.14 + 5 LicensesAssetsTest).
  - `lintRelease`: initially FAILED — 2 PRE-EXISTING NewApi errors
    (alpha.3/4 commits, not audit regressions): `Path.of` needs API 34 vs
    minSdk 26 in RootfsIntegrity.kt:314 + SafeTarExtractor.kt:213 (latent
    NoSuchMethodError on Android 10–13 during rootfs install). FIXED with
    behavior-identical `Paths.get` (API 26). Now PASSES (22 pre-existing
    warnings remain, none in audit-touched files).
  - `assembleRelease`: SUCCESS — com.linuxify.app 0.1.0-alpha.14
    (versionCode 14), minSdk 26/targetSdk 28; 5 binaries per ABI byte-match
    repo jniLibs; `assets/licenses/` (5 texts + notices) present and
    byte-identical to repo copies; cert SHA-256
    8f55e46675c284931e79f3ccc7fde4fad8970f6af4d506f5b75ed3edd7bdcca9
    (stable identity unchanged); NO secrets/keystore inside APK.
  - Audit doc gained §13 (re-verification record); evidence scripts cited in
    §12 were lost to the rollback and are noted as such.
- Compliance changes already shipped in this branch (commit ad7a350): root
  LICENSE (GPL-3.0), docs/THIRD_PARTY_NOTICES.md + byte-identical in-APK copy
  (assets/licenses/), 5 licence texts, in-app Settings → Third-party licences
  dialog, RUNTIME.md/THIRD_PARTY_TERMUX_LICENSE.md corrections, 5 new tests.
- REMAINING for a proprietary release (NOT done, needs owner decision):
  1. [ENGINEERING BLOCKER] Replace the in-process GPL terminal stack (§8 of
     the audit: jackpal originals / ConnectBot / xterm.js / written Termux
     grant). Substantial effort — it is the app's core surface.
  2. [LEGAL] §11 questions to a licensing lawyer (Termux exception reading,
     APK-container aggregation comfort, LGPL-in-APK, Installation Information,
     store terms).
  3. [AFTER 1] Licence statement change in README/in-app; prior releases stay
     GPL.

## Repository & branch

- Canonical repo: https://github.com/superz1402/linuxify — branch `main`
- Latest verified remote commit: see `git ls-remote origin main`
- Releases:
  - v0.1.0-alpha.14 (current) — release id 416937021, tag on f1efcf9.
    APK SHA-256 f33684a07c73f1df6ecb7d07a85c63d4454ab209e9f9fa0d7cb9c7e65703ccf8
    (published asset verified byte-identical to the local build; cert
    8f55e466…cca9). Content: alpha.13 audit & hardening: 8 issues (#13-#20)
    fixed with tests. Headline: APT search WORKS again (Regex.escape's PCRE
    \Q…\E was never valid glibc ERE, so every ordinary search returned "No
    results" — #13); APT operations (upgrade/repair/search-install) show their
    live log card on the Software screen (#15); catalog probe runs per entry-set
    instead of per keystroke (#16); custom tools are re-validated when loaded
    from disk and the probe script refuses unsafe identifiers (#14); multi-spec
    entries walk every viable path — jq's checksum-pinned static binary is now
    actually tried when APT fails (#17); binary removal is idempotent (#18);
    verify-failure hints report the real exit code (#19); npm scoped
    @scope/pkg custom tools accepted (#20). 119 unit tests. Assets:
    linuxify-0.1.0-alpha.14-release.apk
  - v0.1.0-alpha.13 — Universal Software Management System (see the section
    below): Software catalog (24 curated entries: System/Dev/AI/Network/
    Media/Data/Text) over ecosystem adapters (APT, npm, pip, checksum-pinned static
    binaries — yq 4.44.3 + jq 1.7.1 arm64, sha256 cross-verified against upstream
    manifests); auto prerequisites (Node.js profile, python3-pip); per-tool run
    verification; uninstall; user-defined custom tools (apt/npm/pip, JSON-persisted);
    one-tap Update & upgrade; Packages screen renamed/rebuilt as the Software hub.
    KNOWN DEFECTS (all fixed in alpha.14, release left untouched for
    traceability): APT search never matched ordinary names (#13), APT op card
    missing from the rebuilt screen (#15), probe-per-keystroke (#16), custom
    tools not re-validated on load (#14), static-binary fallback unreachable
    (#17), plus #18/#19/#20. Assets: linuxify-0.1.0-alpha.13-release.apk
  - v0.1.0-alpha.12 — Dependency & Package Recovery Manager: one-shot environment audit, repair ONLY on real dpkg/apt
    inconsistency (never blindly), BASE env (curl + ca-certificates) auto-
    installed after a fresh environment install, optional DEVELOPER profile
    (git, Node.js, npm), install-retry after fix-broken, per-tool verification
    (binary exists AND runs), failure reporting with practical recovery
    commands, IPv4 fallback on every apt network operation. EnvCore pure core
    unit-tested. Assets: linuxify-0.1.0-alpha.12-release.apk
  - v0.1.0-alpha.11 — ROOT-CAUSE fix for the "groups: cannot find
    name for group ID" prompt warnings (two independent layers, host-verified
    against the real ubuntu-base tarball — see the section below); in-shell
    "command finished" notifications (guest PROMPT_COMMAND bell → app
    notification while backgrounded); GuestPatches idempotent in-place upgrade
    applied on app start AND before every shell spawn; wakelock re-acquire
    leak fixed; 61 unit tests. First release carrying the alpha.10 feature set:
    background running (FGS + wake lock), process-scoped sessions, network
    auto-recovery, CLI parity audit. Assets: linuxify-0.1.0-alpha.11-release.apk
  - v0.1.0-alpha.10 — CODE COMMITTED (72af460) BUT NEVER TAGGED/RELEASED (the
    session died before the release step; no GitHub release, no asset). Its
    features ship inside alpha.11.
  - v0.1.0-alpha.9 — floating share-log button (full transcript → share sheet)
  - v0.1.0-alpha.8 — terminal Copy/Paste actually reach the system clipboard
  - v0.1.0-alpha.7 — Termux-true terminal UX (keyboard, font, extra keys,
    group warnings, jank)
  - older (superseded): alpha.1–6 install/crash chain, all documented in
    worklog.md

## Guest prompt warnings — root cause + fix (alpha.11, do not regress)

- Symptom on device: every terminal open printed `groups: cannot find name for
  group ID 3003/9997/20495/50495` before the prompt (the Android app's
  supplementary groups, unmapped inside the guest).
- ROOT CAUSE (host-verified with proot v5.5.0 + real ubuntu-base 24.04.5,
  checksum-verified): Ubuntu's /etc/profile sources /etc/bash.bashrc, whose
  sudo-hint block runs `$(groups)` on EVERY interactive login shell unless
  `$HOME/.sudo_as_admin_successful` (or .hushlogin) exists; coreutils prints
  one warning per supplementary gid missing from the guest /etc/group.
- Why alpha.7's fix missed devices: it appended gids discovered from
  /proc/self/groups alone; on Android 16 that discovery evidently came back
  empty/partial, so only the primary gid was patched and all four
  supplementary gids still warned (silently — the failure was never logged).
- THE FIX (two independent layers, both in GuestPatches.applyAll):
  1. GuestGroups: gid discovery now merges /proc/self/groups +
     /proc/self/status "Groups:" + a static well-known floor
     (3001/3002/3003/3004/9997) — the universal groups are named even if every
     proc read fails;
  2. /root/.sudo_as_admin_successful marker — bash.bashrc then skips the whole
     $(groups) check at the source. App sets HOME=/root (buildEnv), which the
     marker requires — verified on host that a wrong HOME defeats it.
- GuestPatches.applyAll runs in THREE places: RootfsInstaller.configureUserspace
  (fresh installs), AppViewModel.init (every app start, failure now LOGGED via
  CrashReporter — never silent), and PtyTerminal.newShellSession (before every
  spawn; memoized per process). It also rewrites etc/profile.d/linuxify.sh
  (single owner) with the PS1/env AND the bell hook below.
- Host verification artifacts: /home/z/my-project/verify (repro BEFORE fix →
  warning appears; /etc/group entry alone → clean; marker alone (HOME=/root)
  → clean). Kotlin raw-string gotcha: "\$" is NOT an escape in raw strings —
  literal dollars in guest shell files must be written as the template
  ${'$'}{'$'} (PS1's backslash-dollar is the one intentional exception: bash
  needs it verbatim).
- In-shell run-completion notifications: GuestPatches' profile adds
  `PROMPT_COMMAND="${PROMPT_COMMAND:+$PROMPT_COMMAND; }printf '\a'"`
  (interactive shells only); the vendored emulator routes BEL to
  LinuxifySessionClient.onBell, and RunCompletionGate (pure, unit-tested)
  decides: notify only while the app is backgrounded, throttled to one per
  3 s. Foreground shells never buzz.

## Dependency & Package Recovery Manager (alpha.12 — pkg/EnvManager.kt)

- User directive: Linuxify must handle Ubuntu dependencies itself — BASE
  environment (curl + ca-certificates) by default, optional DEVELOPER profile
  (git, Node.js, npm), repair the package manager ONLY when actually
  inconsistent, verify installs, report failures with a practical recovery
  command, and use IPv4 fallback on flaky networks.
- Architecture: `EnvCore` (pure object — profiles, audit parsing, recovery-plan
  building, verification rules; JVM-unit-tested) + `EnvManager(ctx, apt)`
  (runtime executor). EnvManager SHARES the AppViewModel AptManager instance so
  every apt/dpkg write serializes on the SAME mutex — never construct a second
  AptManager for it.
- Audit = ONE combined proot spawn (probe script in EnvManager.PROBE_SCRIPT):
  distro (os-release), arch (dpkg --print-architecture), free MB (df -Pm), DNS
  probe (getent hosts deb.debian.org), interrupted dpkg (dpkg --audit output OR
  /var/lib/dpkg/updates count), broken deps (apt-get check exit), tool presence
  (command -v loop). Parsed by EnvCore.parseProbe — unknown lines ignored,
  unreadable fields stay null/false (honest degradation, no crash).
- Recovery plan (EnvCore.recoveryPlan): healthy audit ⇒ EMPTY plan (the
  "never blind" rule). Interrupted dpkg ⇒ dpkg --configure -a; broken deps ⇒
  apt-get --fix-broken install; both ⇒ in that order. Executed via new
  AptManager.repairDpkg()/fixBroken() (Kind.REPAIR; Packages screen labels it
  "package repair").
- Install flow: audit → recover-if-needed → refresh index only if stale
  (EnvCore.indexIsStale: no InRelease/Release file or older than 24 h) →
  install ONLY the missing profile packages (ForceIPv4 always) → on failure:
  fix-broken + ONE retry → verification: execInRootfs(binary, --version) per
  tool must exit 0 with non-empty stdout → DONE, else FAILED with hint.
- Automatic integration points: AppViewModel.startInstall() auto-installs the
  BASE profile right after a fresh rootfs install (best-effort, offline-tolerant
  — pending state visible on the Packages screen); AppViewModel.installPackages()
  (search-screen installs) runs env.ensureHealthyOnly() first so ANY install
  path repairs the package manager before using it — a probe failure does NOT
  block a plain install attempt.
- UI: Packages screen top card "Environment" — audit summary, health status
  chip, repair button (shown only when unhealthy), BASE/DEVELOPER rows with
  missing-tool lists, verification chips (✓/✗ + version line), per-step results
  + live log box.

## Universal Software Management System (alpha.13 — pkg/Ecosystems.kt, Catalog.kt, CustomTools.kt, SoftwareManager.kt)

- User directive: Linuxify is a GENERAL-PURPOSE Linux environment, not an app
  installer. Architecture: shared CORE (health/recovery/install/verify/remove) +
  ECOSYSTEM ADAPTERS (APT, npm, pip, static binaries) + CATALOG as pure data +
  UNRESTRICTED terminal. OpenCode is one catalog entry among many.
- `InstallSpec` (sealed): Apt(packages) / Npm(pkg, binary) / Pip(pkg, binary) /
  Binary(url, sha256, binaryName, version, archs). `Ecosystems.resolveSpec`
  (== first of `installCandidates`) picks the first spec usable NOW; if none,
  the first with auto-provisionable prerequisites; else null (honest
  unsupported-arch failure). alpha.14 (#17): install() walks ALL candidates —
  every attempted ecosystem is logged, and a later sha256-pinned spec (jq's
  static binary) really is tried when the APT path fails.
- Prereq chains: npm tools auto-install the Node.js toolchain via the DEVELOPER
  profile; pip tools auto-install python3-pip; pip uses --break-system-packages
  (Ubuntu 24.04 PEP 668 — the standard container-like-userland choice).
- Binary adapter: raw single-file downloads ONLY (archives rejected in v1 so
  extraction bugs cannot happen), sha256-pinned, 3 attempts + backoff, 128 MB
  cap, installed host-side into <rootfs>/usr/local/bin with exec bit.
- Catalog entries verify via `binary --version` inside the guest; every entry
  has ≥1 spec, a verify binary, and a known category (unit-enforced).
- CustomTools: user entries (apt/npm/pip incl. npm scoped @scope/pkg, #20)
  validated (slug ids, safe package regexes — no `; | &` etc.), JSON-persisted
  at filesDir/custom_tools.json, merged after builtins (never shadow a builtin
  id), corrupt JSON → empty list. alpha.14 (#14): EVERYTHING read back from
  disk is re-validated and invalid entries are SKIPPED; the installed-state
  probe script additionally refuses to interpolate any identifier failing the
  shared Ecosystems.SAFE_TOKEN_RE charset (defense in depth against a
  hand-edited/corrupt custom_tools.json reaching a guest shell).
- SoftwareManager: one op at a time (own Mutex; apt writes serialize through
  the SHARED AptManager). Op flow: PREPARING (audit+prereqs) → INSTALLING →
  VERIFYING → DONE/FAILED with recovery hints. remove() maps to the same
  adapter. refreshInstalledState = ONE proot spawn probing all visible binaries.
- UI: PackagesScreen REPLACED by SoftwareScreen (route stays "packages"): the
  alpha.12 Environment card (+ new Update & upgrade button), the APT operation
  card (alpha.14 #15 — kind-aware label, state chip, exit code, live log for
  every apt operation incl. upgrades and search-installs), software op card
  with live log, catalog card (search + category chips + per-entry Install/
  Remove + custom-entry delete), Add-custom-tool dialog, and the unrestricted
  full-APT search below. Home button label: Software. alpha.14 (#16): the
  installed-state probe is keyed on the stable entry set — one proot spawn per
  screen entry / custom-tool change / completed operation, NOT per keystroke.
- alpha.14 test/lint hardening: AptCommands (pure) builds the apt-cache ERE
  with backslash-escaped metacharacters (glibc-verified host-side) and
  isInstalled() runs dpkg -s as argv exec + explicit status-line parse (#13).
- ENV ROLLBACK hit AGAIN mid-session (Task 18): HEAD reverted to alpha.5, JDK
  + SDK + download/ APKs wiped — but origin/main held alpha.12 and the
  untracked new files survived. Recovered: git reset --hard origin/main,
  Temurin JDK 21.0.12.1+1 + SDK reinstalled, alpha.12 APK re-downloaded from
  the release. LESSON (recurring): commit+push EARLY and OFTEN, not just at
  session end.

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
  keyboard + font (alpha.7), clipboard Copy/Paste (alpha.8).
- alpha.7's "group warnings fixed" was NEVER truly device-verified — the user
  reported the warnings again on alpha.9 (Task 16 root-caused it; see the
  section above). Lesson: cosmetic guest-side fixes need an on-device check
  too, and silent no-op failures must be logged.
- Host-verified (this session): groups-warning reproduction + both fix layers
  against the real ubuntu-base tarball with proot v5.5.0 (SHA-256-verified);
  PROMPT_COMMAND bell capture (3 BEL chars per session).
- Awaiting user drill for alpha.11: (a) terminal opens with NO group warnings;
  (b) start a long command (e.g. `apt update`), go home → "Command finished"
  notification on completion; (c) background running: leave app minutes, come
  back → same shell with scrollback; (d) share-log icon still works.
- Awaiting user drill for alpha.12: (e) Packages > Check environment → audit
  card fills (distro/arch/storage/network); (f) Install Developer tools →
  live steps, then "Verification: git ✓ (…), node ✓ (…), npm ✓ (…)"; then in
  terminal `node -v` works; (g) search-screen install of any package when the
  package DB is healthy → no repair detour; (h) after a FRESH environment
  install, curl exists without manual action.
- Awaiting user drill for alpha.13: (i) Software > catalog → Install htop →
  APT path, verified chip, "installed" state; (ii) Install OpenCode (AI
  category) → Node.js toolchain auto-installs first, then npm, then
  `opencode --version` verified; (iii) Install yq → static-binary path,
  sha256-verified, `yq --version` works; (iv) Update & upgrade one-tap;
  (v) Add custom tool (e.g. pip yt-dlp) → installs + verifies; (vi) terminal
  still unrestricted — `apt install sl` style manual installs unaffected.
- Awaiting user drill for alpha.14: (vii) Software > All APT packages →
  search "htop" → RESULTS appear (was broken: "No results" for everything);
  install one and see the APT operation card show the live apt log (#13+#15);
  (viii) Update & upgrade → the APT operation card ("Update & upgrade") shows
  progress/log/completion instead of nothing (#15).

## Next exact actions

1. User tests alpha.14 on device: drills (e)-(h) from alpha.12, (i)-(vi) from
   alpha.13, plus (vii)-(viii) above.
2. Candidate next features: full `dpkg -l` installed-packages screen (the
   AptCommands.hasInstalledStatus parser is ready for reuse), custom APT
   repositories, Rust/Cargo adapter, session close button ("×" on ended
   tabs), in-app self-update.
3. Known accepted limitations (documented, not bugs): software op state is
   in-memory only (process death clears the card; installed state re-probes on
   next visit); in-flight installs cannot be cancelled from the UI yet.
