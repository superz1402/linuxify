# SESSION_STATE.md — Linuxify Android

Concise handoff for the next agent session. Current as of 2026-10-21 (Task
smart-session-close-1 — smart close: idle shells close instantly, busy shells
ask once with a process count, whole-tree kill; v0.1.0-alpha.19, main = 4ad0d4b).

## COMPLETED TASK — smart session close (alpha.19)

- USER FEEDBACK on alpha.18: "Even with a normal shell click in terminal it
  asks to close the shell — do it smartly."
- ROOT CAUSES (both in alpha.18's TerminalScreen): (1) requestClose confirmed
  for EVERY running shell — an idle prompt IS running, so a normal shell never
  closed without the nag; (2) the ✕ was nested INSIDE the tab TextButton, so
  tab-switch taps could land on it.
- FIX — smart policy (new terminal/ProcTree.kt, /proc pid→ppid scan): "work" =
  descendants of root's DIRECT children; the proot→bash spine is never counted.
  Ended tab → silent; idle shell → INSTANT close, no prompt; shell with live
  jobs → one confirmation naming the process count. Scan runs off-main
  (Dispatchers.IO via rememberCoroutineScope); shell exiting mid-scan → silent
  close (re-checked after IO); double-tap guarded (closeCheckInFlight).
- FIX — whole-tree kill: closeNow SIGKILLs the session's entire tree (jobs
  first, tracer last, Os.kill) instead of finishIfRunning() alone — SIGKILL to
  the tracer can orphan the guest shell + background jobs. Dialog promise is
  now literally true. Registry.remove ordering safe (forget→finalize is
  idempotent).
- FIX — tab UX: ✕ is a SIBLING of the tab button (Row: [tab][spacer][✕]);
  normal tab clicks can never hit it; pad 5→7dp; "Close sh N" a11y label.
- STANDING RULE (violated twice across sessions, now documented twice): FETCH
  + diff against origin/main BEFORE implementing. This session re-hit the
  stale-clone trap (built against alpha.5-era b00fb75, opened PR #24, closed
  it after pull revealed alpha.18); ProcTree was salvaged and re-landed on
  real main.
- VERIFY: 165/165 tests (9 new ProcTreeTest); release cert 8f55e466…
  unchanged; versionCode 19. Device drill: ✕ an idle shell → instant close;
  run `top` in another → ✕ → dialog names 1+ processes → Close anyway.

## COMPLETED TASK — terminal close/clear + dead-code sweep (alpha.18)

- USER REQUEST: "work on dead codes if any in the main apk codebase. Also I
  want an option to close and clear the terminal session when multiple
  sessions are running."
- IMPORTANT process lesson: this session first implemented against a stale
  alpha.5-era clone; push was rejected because parallel sessions had advanced
  origin/main 21 releases (alpha.6..17). The stale work is preserved on branch
  `backup/alpha5-based-work`; the real work was REDONE on origin/main @ 3c7f418.
  RULE: always `git fetch` + inspect origin/main before implementing here.
- FEATURE (TerminalScreen/SessionTabs): every live tab has a ✕; tab row has
  CLR (left of "+"). Running shell → confirm dialog → finishIfRunning() +
  TerminalSessionRegistry.remove() (previously an unreachable registry path —
  now wired). Ended tabs close silently. Selection math handles the history
  region shifting after a live close; dialog state holds the session object.
  CLR injects ESC[H ESC[2J ESC[3J into the emulator (screen + scrollback,
  stdin untouched — safe under TUI apps) and pokes boundView.onScreenUpdated().
- WIRED (was dead because unfinished): TerminalPersistence.clearHistory() →
  Settings > Storage "Clear terminal history…" + confirm dialog (finished
  sessions only; live sessions untouched). Settings footer version now uses
  BuildConfig.VERSION_NAME (was hardcoded "0.1.0-alpha.1").
- REMOVED (verified zero refs): Tokens.kt (whole file), Color.kt
  TerminalBackground/TerminalForeground, RuntimeMetadata DISTRO_ID +
  ROOTFS_VERSION_TAG, LinuxRuntime.relativePathOf, ServiceManager
  saveDefinitions, AptManager.isInstalled + AptCommands.hasInstalledStatus
  (+2 tests), AppViewModel.removePackages + refreshPackageIndex,
  TerminalPersistence.recordOf + replaceFromRestore, SoftwareOp.entryId,
  gradle.properties org.gradle.java.home (host-pinned path; use JAVA_HOME).
- KEPT + FLAGGED: AppViewModel.installEnvProfile() → EnvManager.installProfile()
  — complete backend, no UI yet (see Next exact actions).
- TESTS: 156/156 (-2 = removed hasInstalledStatus tests); assembleDebug +
  assembleRelease PASS (0.1.0-alpha.18, versionCode 18); cert SHA-256
  8f55e466… unchanged → overwrite install; feature strings verified in dex.
- BUILD ENV NOTE: 4 GB host needs reduced heaps for release builds:
  `./gradlew assembleDebug assembleRelease --no-daemon
  -Dorg.gradle.jvmargs="-Xmx1152m -XX:MaxMetaspaceSize=384m"
  -Pkotlin.daemon.jvmargs="-Xmx768m"` (split the two variants if it OOMs).

## COMPLETED TASK — battery audit (fix/battery-saving @ 25ab729, alpha.17)

- USER REQUEST: "Check on battery saving without missing anything" — full
  battery-usage audit of every loop/timer/service/wakelock/polling path.
- AUDIT RESULT (45 Kotlin files swept): the one critical finding was
  RuntimeForegroundService holding an UNCONDITIONAL PARTIAL_WAKE_LOCK for up
  to 6 h whenever ANY shell session existed — an idle bash kept the CPU out of
  deep sleep for hours. NOT Termux's model (Termux's wake lock is opt-in).
  Also: 10s transcript sweep + 30s storage check ran while backgrounded;
  Projects tab landing re-spawned proot+git probe every time; installer
  emitted a StateFlow update per 64 KB download chunk. Verified OK: terminal
  I/O threads block (no busy-wait), cursor blinker off, no alarms/polling,
  memoized guest patches.
- FIXES (runtime/PowerPolicy.kt is the single decision point, unit-tested):
  wake lock ONLY while a managed service runs OR a long op is in flight
  (rootfs install, apt install/remove/upgrade/update, env profile, catalog
  install/remove, project clone, backup/restore — all wrapped in
  PowerPolicy.opBegin/opEnd try/finally in AppViewModel) OR the user opts in
  via NEW Settings > Battery toggle ("Keep CPU awake while shells run",
  persisted in power_policy prefs) with a live session. Service no longer
  owns a lock; reevaluates at onStartCommand/stopIfIdle/onDestroy. Idle shell
  + toggle off = CPU may sleep (process still survives via FGS).
  Background: transcript sweep 10s->60s (ON_STOP flush covers data safety);
  storage check paused when backgrounded (LinuxifyApp.isInForeground).
  ProjectsManager.list(force) caches the batched git probe for 30 s TTL,
  clock-skew safe (negative age = stale, caught by test); mutations force.
  RootfsInstaller download progress throttled to 100 ms.
- TESTS: 158/158 (+14: PowerPolicyTest x10, ProjectsProbeTtlTest x4);
  lintRelease PASS; assembleRelease PASS (0.1.0-alpha.17, versionCode 17,
  APK bd344d5ebc1f456408fca0b94cea3954582c414fd5f7001b935c83266ec8d8ce in
  download/linuxify-0.1.0-alpha.17.apk).
- HISTORY NOTE: the original battery implementation (alpha.16) was lost to a
  second environment rollback BEFORE it was pushed (only the APK + worklog
  survived); alpha.17 is a faithful re-implementation verified against the
  documented design and the alpha.16 APK checksum. LESSON APPLIED: code is
  pushed immediately after verification, docs follow.
- Device drill pending (user): Settings > Battery toggle on/off; background
  an idle shell with screen off and confirm no abnormal battery drain in
  system stats; run a server and confirm it stays alive.

## COMPLETED TASK — issue #23: persistence, backup/restore, cache management (on main)

- USER REQUEST: (1) keep projects + terminals + everything on local storage so
  nothing is wiped until the user does it; (2) a backup & restore system;
  (3) cache clearing per project and for the whole system.
- PERSISTENCE: projects/rootfs/services were ALREADY durable in filesDir
  (survive restart/reboot; wiped only by uninstall / system clear-data /
  in-app Reset) — unchanged. The gap was TERMINALS: sessions were in-memory
  only. New terminal/TerminalPersistence.kt writes every session's transcript
  (scrollback+screen, via TerminalBuffer.getTranscriptText) to
  filesDir/runtime/terminal/<id>.log plus a sessions.json index (id, start
  dir, started/ended, exit). Write schedule: markDirty on onTextChanged,
  snapshots throttled to 3s/session (skipped when transcript unchanged), 10s
  sweep loop in AppViewModel, flushAll on app ON_STOP (MainActivity observer),
  final write on session finish. Restore is read-only history tabs (honest
  maximum: a pty cannot reattach across process death) with per-entry delete
  + "New shell in this directory". NOTHING is auto-deleted.
- BACKUP/RESTORE: backup/BackupManager.kt writes manifest-first .tar.gz
  (commons-compress; entry paths relative to filesDir; POSIX modes 0755/0644
  +exec; symlinks preserved by pure BackupTar). Manifest-first means SAF
  inspection reads entry #1 only (no full decompression). Scopes: projects +
  terminal history always, rootfs optional (large/slow). Restore = one
  SafeTarExtractor pass into filesDir with a NEW optional skipEntry filter
  (installer path untouched); projects skip-existing or overwrite (names from
  manifest); rootfs scope stops services+sessions, deletes rootfs, extracts,
  restores/synthesizes runtime/install.json ready marker. UI in Settings with
  CreateDocument/OpenDocument launchers (no storage permissions needed).
- CACHE: maintenance/CacheTargets.kt (pure, tested) finds only recognized
  cache dirs per project (__pycache__, .pytest_cache, .mypy_cache,
  .ruff_cache, .ipynb_checkpoints, .cache, .gradle, node_modules/.cache,
  .next/cache) — .git never descended, symlinks never followed/deleted,
  depth cap 8; build/dist/venv/vendor untouched. maintenance/
  MaintenanceManager.kt: per-project report+clear and system report+clear
  (APT archives, /root/.cache, npm _cacache, app cacheDir, proot temp
  blocked while sessions live, downloads/staging blocked while installer
  busy). Both flows REPORT exact dirs + bytes first, delete only on explicit
  confirm, contents-only deletion. Settings storage card shows sizes
  (rootfs/projects/terminal-history/app-cache/free).
- VERIFICATION: 144/144 unit tests (+14: CacheTargetsTest 7,
  BackupRoundTripTest 4 incl. write->SafeTarExtractor round-trip with mode +
  symlink fidelity, TerminalRecordTest 3); lintRelease PASS; assembleRelease
  PASS (0.1.0-alpha.15, versionCode 15, APK
  bb40ef63c643436f0527cf7e36ac9bbabe2813803b65b9f6dd1d03beb34e4177).
- COMMITS: feature/persistence-backup-cache d0b3a73 pushed, fast-forward
  merged to main, pushed; ls-remote verified both at d0b3a73.
- Device drill pending: run terminal, background, force-stop, relaunch ->
  history tab shows transcript; create backup (with and without rootfs),
  uninstall, reinstall, restore; per-project and system cache clears report
  expected bytes.

## COMPLETED TASK — issue #22: Projects screen fixes (on main)

- USER-REPORTED BUG, root-caused: tapping the Projects tab could show the
  TERMINAL screen — the bottom-nav saveState/restoreState pattern restored the
  whole per-tab saved stack, and "Open terminal" had stacked `terminal` on top
  of `projects`.
- FIXES (all on main @ 459505c, branch fix/projects-screen):
  1. Tabs ALWAYS land on their own root screen: shared openTab()
     (popUpTo("home"), no saveState/restoreState) for bar/rail/Home jumps.
     "files" stays a stacked drill-down; a tab tap wins over it.
  2. "Open terminal" on a project now REALLY starts the shell in the project:
     AppViewModel.requestTerminalInProject(name) -> pending dir
     (/root/projects/<name>) -> TerminalScreen consumes once ->
     PtyTerminal.newShellSession(workdir=...) (proot -w). Explicit request
     spawns a NEW session even when sessions exist; plain tab visits reattach.
  3. "Browse files" on a project opens Files IN /root/projects/<name>
     (consume-once pending start dir); plain visits start at /root.
  4. ProjectsManager: dead+buggy gitStatus() removed (ran git status WITHOUT
     -C, zero callers); git probing batched to ONE proot spawn per refresh
     (was 2 per project) with pure parseGitProbe() + 11 unit tests
     (ProjectsGitProbeTest). Probe failure = "no git info" (honest).
  5. Carried Path.of->Paths.get (API 34->26) lint fix onto main — pre-existing
     latent NoSuchMethodError on Android 10-13 during rootfs install.
- VERIFICATION: 130/130 unit tests PASS; lintRelease PASS; assembleRelease
  PASS. Device drill pending: open-terminal-in-project lands in
  /root/projects/<name>; Projects tab always the list; Browse files in project.
- Note: audit/proprietary-distribution still holds the licensing-audit
  deliverables (LICENSE, assets/licenses + in-app notices, audit report §13,
  LicensesAssetsTest) pending merge into main.

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

1. User installs alpha.19 on device (overwrites alpha.18, same cert). Drill:
   open two shells (+) → ✕ one IDLE shell (must close INSTANTLY, no dialog) →
   in the other run `top` → ✕ → confirm dialog must name the running process
   count → Close anyway → tab gone, transcript in history. Tab-switch taps
   must never trigger a close. Then Settings > Storage > "Clear terminal
   history…". Plus the outstanding device drills (e)-(h) from alpha.12,
   (i)-(vi) from alpha.13, (vii)-(viii) from alpha.14.
2. Candidate next features: wire the EnvManager profile installer into
   SoftwareScreen (backend `installEnvProfile()`/`installProfile()` is
   complete and waiting — Base/Developer profiles), custom APT repositories,
   Rust/Cargo adapter, in-app self-update. (Session close/clear is DONE and
   now SMART as of alpha.19.)
3. Known accepted limitations (documented, not bugs): software op state is
   in-memory only (process death clears the card; installed state re-probes on
   next visit); in-flight installs cannot be cancelled from the UI yet.

## COMPLETED TASK — landing page & release mirror (2026-10-20, separate repo)

- Standalone site repo: **Bilal140202/linuxify-app** — live at
  https://bilal140202.github.io/linuxify-app/ (GitHub Pages, deployed by
  Actions from the site repo's main branch). Design: terminal-native editorial
  (Clash Display/Satoshi/JetBrains Mono, single accent, hairline rules), GSAP +
  ScrollTrigger + Lenis with reduced-motion fallbacks, no build step, no
  trackers.
- Pages: index (hero/capabilities/showcase/how-it-works/principles/download/FAQ),
  about, privacy, terms, license & attributions, changelog (all 15 alphas),
  404. SEO: sitemap, robots, JSON-LD, OG image.
- The site repo carries a MIRROR release: v0.1.0-alpha.15, byte-identical APK
  (12,568,978 B; SHA-256 bb40ef63c643436f0527cf7e36ac9bbabe2813803b65b9f6dd1d03beb34e4177),
  verbatim notes + provenance preamble. Production release cycle stays HERE.
- Main README now links the site ("Website & downloads" section) and the status
  line reflects alpha.15; this repo's homepage field points to the live site.
- To mirror a future release: fetch APK from this repo's release, verify its
  SHA-256, publish the same tag in Bilal140202/linuxify-app with the verbatim
  notes, then bump version strings per that repo's AGENTS.md.
