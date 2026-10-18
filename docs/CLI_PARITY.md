# CLI ⇄ Android App Parity

Companion CLI reference: [Bilal140202/linuxify](https://github.com/Bilal140202/linuxify)
(TypeScript CLI on Termux + proot, `src/cli/commands/*`, audited 2026-10-18 against
`main`). The CLI has 29 command files; its user-facing command list is in its README.

This document is the honest per-command comparison of what the CLI offers versus
what the Android app (`com.linuxify.app`, v0.1.0-alpha.10) implements. Where the
app is deliberately different, the reason is given. Nothing is claimed as "done"
that is not actually in the shipped app.

Legend: **COVERED** — app does the equivalent · **PARTIAL** — subset works, gap
listed · **DEFERRED** — planned, not built · **N/A** — CLI infrastructure that has
no meaning on the app's architecture.

## User-facing commands (CLI README)

| CLI command | What the CLI does | App equivalent | Status |
|---|---|---|---|
| `linuxify` (launch) | bootstrap if needed, open shell | Terminal screen: guarded auto-spawn of a PTY login shell; Setup screen runs the staged installer first if the rootfs is missing. Real proot, real PTY. | **COVERED** |
| `doctor` | health check | Home screen: Runtime binaries row (proot + ELF loader verified), runtime diagnostics (uname/stat/exec probes), storage, ready-gate with stale-marker self-repair. Runs automatically on start, not on demand. | **COVERED** (always-on rather than on-demand) |
| `doctor --explain` | explain why each check matters | Error/diagnostic surfaces carry plain-language explanations (RootfsInstaller userMessageFor, NativeDepRepair user messaging, Home diagnostics rows). | **COVERED** (inline, not as a separate listing) |
| `repair` | apt/brew-style repair plan | Built-in self-repair pipeline: 3 verification probes × native-dep shim repair × exec-bit sweep × rootfs integrity re-check, all automatic (RootfsInstaller.verifyUserspace, NativeDepRepair, RootfsIntegrity). | **COVERED** (automatic; no manual plan view) |
| `fix` | AI-assisted diagnosis | Not AI-assisted, but the same outcome: deterministic diagnosis + auto-fix for the known failure classes (linker, permissions, missing loader, stale markers). Crash reports + share-log feature let the user hand anything unknown to an AI. | **PARTIAL** (deterministic only — by design, no network AI dependency) |
| `report` | bug-report fingerprint | Global crash reporter (in-app copyable report, dedicated crash screen) + one-tap "share whole terminal log" floating button — the report can be pasted to any AI/app in two taps. | **COVERED** |
| `discover` | scan for existing environments | No existing proot environments to discover inside the app sandbox; the app manages exactly its own. | **N/A** (single-sandbox architecture) |
| `adopt ubuntu` | adopt existing Ubuntu without reinstall | Same reasoning as `discover`; the app ships and owns its own pinned rootfs. | **N/A** |
| `add <package>` | install a curated tool from registry (12: Cline, Codex, Aider, Goose, Gemini CLI, Wrangler, Docker CLI, kubectl, Terraform, PM2, Playwright, Git) | Packages screen installs ANY Debian/Ubuntu package via real APT; the CLI's curated tools are themselves installable in the terminal (`apt install git`, `npm i -g @openai/codex`, etc.). What is missing is the curated one-tap registry UX. | **PARTIAL** (real apt covers the substance; curated registry UX not yet built) |
| `search <query>` | search registry | Packages screen: `apt-cache search` with exact-name matching. | **COVERED** (apt registry instead of CLI registry) |
| `list` | list installed packages | Home shows installed package count; `dpkg -l`-style listing via terminal; package states from real APT operations. | **PARTIAL** (no dedicated visual "installed list" screen — count + terminal) |
| `--help` | command help | Every screen has explanatory copy; docs/ set in-repo. | **COVERED** (GUI idiom) |

## Non-README commands (present in src/cli/commands/)

| CLI command | App equivalent | Status |
|---|---|---|
| `install` | Setup screen: staged installer (preflight → download → verify → extract → configure → verify), resumable, checksum-pinned, zero-tap self-setup. Device-proven. | **COVERED** |
| `init` | Implicit in Setup (idempotent, safe to re-run). | **COVERED** |
| `env` | GUEST_ENV_PRELUDE + profile.d (PS1, TMPDIR, LANG, resolv.conf 8.8.8.8/1.1.1.1) written by the installer. | **COVERED** |
| `shell` | Terminal screen — Termux-true PTY UI: tap keyboard, pinch zoom, extra keys, long-press copy/paste, multi-session tabs, share-log button. | **COVERED** (arguably exceeds CLI) |
| `run` | Services screen: user-defined managed commands run inside the userland, honest STARTING/RUNNING/FAILED states, port-reachability proof. | **COVERED** |
| `update` / `upgrade` | Packages screen: `apt-get update -y`; upgrade via APT in terminal. | **PARTIAL** (no one-tap `apt upgrade` button yet) |
| `remove` | Packages screen: `apt-get remove -y`. | **COVERED** |
| `info` | Home screen facts card (distro, version, ABI, rootfs size, free bytes, device). | **COVERED** |
| `config` | Settings screen (theme mode etc.). | **PARTIAL** (app exposes fewer knobs than CLI config file) |
| `snapshot` / `gc` | Not implemented — app relies on Android-native persistence (rootfs in app-private storage survives until uninstall). | **DEFERRED** |
| `self-update` | APK updates via GitHub Releases (stable signing cert, overwrite-install). In-app updater not built. | **DEFERRED** |
| `telemetry` | Not implemented. The app ships zero telemetry — privacy default. | **N/A** (deliberate) |
| `completions` | Shell completions — meaningless without a host shell CLI. | **N/A** |
| `plugins` / `patch` | CLI plugin system; proot ELF patching. The app does equivalent patching at BUILD time (patched proot + loader pinned, digests in runtime-binaries.json). | **N/A** (build-time equivalent) |
| `distros` / `runtimes` / `use` | Multi-distro support. App is Ubuntu 24.04 (pinned, device-proven). | **DEFERRED** (deliberate single-distro focus) |

## Beyond the CLI (app-only capabilities)

- Termux-grade terminal UI (vendored Termux emulator/view): pinch zoom, extra keys,
  clipboard, floating share-log icon.
- Foreground-service background running: sessions/services keep running with the
  screen off (partial wake lock), persistent notification, auto-release when idle.
- "Run completed" notifications for background sessions/services.
- Flaky-network auto-recovery: HTTP Range resume + exponential backoff + jitter +
  checksum-verified retry on the rootfs download.
- Honest state reporting (build-verified vs device-verified), global crash reporting.

## Verdict

Everything the CLI uses day-to-day on a phone (launch, install, shell, add/search/
list packages, run servers, doctor/repair/fix, report) is covered by the app —
most of it in a more integrated way. Remaining honest gaps, in priority order:
1. Curated tool registry UX (`add` with 12 presets) — PARTIAL
2. One-tap `apt upgrade` — PARTIAL
3. Visual installed-packages list — PARTIAL
4. Snapshot/gc — DEFERRED
5. In-app self-update — DEFERRED
6. Multi-distro (`use`) — DEFERRED
