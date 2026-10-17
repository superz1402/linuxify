# Architecture

## Overview

Linuxify is a native Android application (Kotlin + Jetpack Compose) that runs a real
Linux userspace locally on an unrooted device. There is no Docker daemon, no remote
server, no Termux dependency, and no cloud account. The Linux environment is a real
Ubuntu root filesystem executed through `proot`, a user-space syscall translation tool.

```
┌─────────────────────────────────────────────────────┐
│ Presentation (Compose, Material 3)                  │
│  SetupScreen · HomeScreen · TerminalScreen · …      │
│  AppViewModel — single state holder                 │
├─────────────────────────────────────────────────────┤
│ Domain services                                     │
│  RootfsInstaller · LinuxRuntime · AptManager ·      │
│  ProjectsManager · ServiceManager                   │
├─────────────────────────────────────────────────────┤
│ Terminal stack (vendored, Apache-2.0 via Termux)    │
│  com.termux.terminal (emulator + PTY JNI bridge)    │
│  com.termux.view (TerminalView rendering)           │
├─────────────────────────────────────────────────────┤
│ Runtime execution                                   │
│  proot (Android bionic build, jniLibs)              │
│  Ubuntu 24.04.5 base rootfs (app-private storage)   │
└─────────────────────────────────────────────────────┘
```

## Layers

### Presentation
Composables never touch processes or the filesystem directly. `AppViewModel` exposes
StateFlows; screens observe them. All state is real: nothing on screen is synthesized
(no fake progress, no placeholder metrics).

### Domain services
- **RootfsInstaller** — staged, resumable, verifiable installation. State machine:
  `NotInstalled → Checking → Downloading(bytes) → Verifying → Extracting(bytes) →
  Configuring → VerifyingInstall → Ready` or `Failed(RuntimeError)`. Stage markers are
  written atomically to `runtime/install.json`; the ready marker is written only after
  a successful in-userspace `uname` probe through proot. A partial rootfs can therefore
  never be reported as ready.
- **LinuxRuntime** — builds the proot argv and environment; provides bounded
  noninteractive execution (`ProcessBuilder`) and canonical-path-guarded directory
  listing over app-private storage.
- **AptManager** — real APT operations with a shared mutex (never two conflicting apt
  runs), live output streaming, and real exit codes.
- **ProjectsManager** — project directories under app storage, bound into the userspace
  at `/root/projects`; git operations run inside the userspace.
- **ServiceManager** — user-defined server commands. States derive from actual process
  liveness and a real loopback socket probe — never from a Start button press.

### Terminal stack
The terminal is the vendored `terminal-emulator` and `terminal-view` code from
termux-app v0.118.0 (originally jackpal/Android-Terminal-Emulator, Apache-2.0), kept
under the original `com.termux.*` package names so the prebuilt JNI bridge
(`libtermux.so`) resolves `Java_com_termux_terminal_JNI_*` symbols. Sessions are real
PTY processes: `TerminalSession` forks a child with a pseudo-terminal and execs proot.

### Runtime execution
`proot` (Termux bionic build, pinned + SHA-256 verified, see `runtime-binaries.json`)
runs with `-0 --link2symlink --kill-on-exit`, binds `/dev`, `/proc`, `/sys`, and the
projects directory. On Android 10+ direct exec() from writable app storage is blocked
by SELinux, so all native executables are packaged as `jniLibs/lib*.so` and executed
from the read-only `nativeLibraryDir`.

## Storage layout (app-private internal storage)

| Path | Contents | Survives app restart | Survives uninstall |
|---|---|---|---|
| `files/runtime/install.json` | installer stage/ready markers | yes | no |
| `files/rootfs/` | the Linux root filesystem (incl. `/root` home) | yes | no |
| `files/projects/` | user projects (bind-mounted to `/root/projects`) | yes | no |
| `files/downloads/` | rootfs archive staging (.part files) | yes | no |
| `files/runtime/proot-tmp/` | proot temp | yes | no |

`/root` inside the userspace is the persistent home. Uninstalling the app deletes
everything — this is stated honestly in the app's Settings screen.

## Threading model

- Installer and apt operations run on `Dispatchers.IO`.
- Terminal I/O runs on dedicated threads inside the vendored TerminalSession.
- UI state is `StateFlow` observed with lifecycle-aware collectors.
- Cancellation: installer checks a `cancelled` flag and coroutine activity between chunks.

## What Linuxify does NOT do (by design)

- No kernel-level isolation (proot is user-space translation, not a VM/container).
- No privileged ports, no kernel modules, no systemd.
- No guarantee of background process survival — Android may kill the app; this is
  reported honestly rather than hidden.
- No automatic public exposure of any port. Default bind is loopback.
