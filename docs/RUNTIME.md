# Runtime — how the Linux environment works

## What is installed

| Component | Version | Source | Verification |
|---|---|---|---|
| Ubuntu base rootfs | 24.04.5 LTS (noble) | cdimage.ubuntu.com (HTTPS) | SHA-256 pinned from upstream `SHA256SUMS`; arm64 `a91d5a93…`, amd64 `e77b6f10…` |
| proot | 5.1.107.96 (Termux build, bionic) | packages.termux.dev apt pool | SHA-256 pinned from the dists `Packages` metadata (`8199dca0…` aarch64, `77ea4554…` x86_64) |
| libtalloc | 2.5.0 (Termux build) | same | `556591f4…` / `b8c6d95f…` |
| libandroid-shmem | 0.7 (Termux build) | same | `0da3a24d…` / `ffa9e4c8…` |
| libtermux.so (PTY JNI) | termux-app v0.118.0 release | github.com/termux/termux-app releases | `9f8cb0b4…` arm64, `999e541c…` x86_64 |

Full provenance record: `runtime-binaries.json` in the repository root. Any checksum
mismatch aborts the install and discards the download — verification is never relaxed.

## How it runs without root

`proot` translates filesystem paths and fakes uid 0 in user space, so an unprivileged
app can run an entire Linux userspace. The app launches proot with:

```
libproot.so -0 --link2symlink --kill-on-exit \
  -r <filesDir>/rootfs -w /root \
  -b /dev -b /proc -b /sys \
  [-b <filesDir>/projects:/root/projects] \
  /bin/bash -l
```

Environment: `PATH` (guest), `HOME=/root`, `TERM=xterm-256color`, `LANG=C.UTF-8`,
`TMPDIR=/tmp`, `PROOT_LOADER=<nativeLibraryDir>/libproot-loader.so` (required, see
below), `LD_LIBRARY_PATH=<syslibDir>:<nativeLibraryDir>` (so proot finds its
libraries), `PROOT_TMP_DIR` in app storage.

Android 10+ blocks exec() of files in writable app storage, so every native executable
is packaged inside the APK as a `jniLibs/lib*.so` file; the OS extracts them to the
read-only `nativeLibraryDir` from which execution is allowed.

## proot's ELF loader (required — the alpha.2/alpha.3 root cause)

Termux builds proot with `PROOT_UNBUNDLE_LOADER` (see termux-packages
`packages/proot/build.sh`): the tracer does **not** embed its loader. For every guest
execve, proot rewrites the syscall to execute a separate static helper — the ELF
loader — which maps the guest binary and its interpreter using host paths proot
prepared (`src/execve/enter.c`, `get_loader_path()`:

```c
return getenv("PROOT_LOADER") ?: PROOT_UNBUNDLE_LOADER "/loader";
```

The Termux build sets `PROOT_UNBUNDLE_LOADER=/data/data/com.termux/files/usr/libexec/proot`,
so without `PROOT_LOADER` set, proot tries to exec **Termux's** loader path — which on
a Linuxify install is either absent (`ENOENT` — the alpha.2 on-device failure
`execve("/usr/bin/bash"): No such file or directory`) or belongs to a foreign app uid
(`EACCES` — the alpha.3 failure `execve("/usr/bin/uname"): Permission denied`; the
guest path in proot's message is cosmetic, the failing execve is the loader).

Linuxify therefore:

1. ships the loader from the **same pinned proot debs** as `jniLibs/libproot-loader.so`
   (arm64: 18,136 B, x86_64: 18,368 B; static EXEC ELF, no `DT_NEEDED`, 16 KB-aligned;
   digests pinned in `runtime-binaries.json`), and
2. sets `PROOT_LOADER=<nativeLibraryDir>/libproot-loader.so` in every proot
   environment (`LinuxRuntime.buildEnv()`), and
3. gates the install (`RootfsInstaller`) and the preflight (`preflightProot()`) on the
   loader being present and executable — a broken packaging fails fast, before any
   rootfs download.

## Native dependency resolution (self-healing)

The bionic linker resolves `DT_NEEDED` entries by **literal file name**. Android's APK
extraction only unpacks files named `lib*.so`, so a binary needing a versioned soname
such as `libtalloc.so.2` can fail on device with:

```
CANNOT LINK EXECUTABLE "…/libproot.so": library "libtalloc.so.2" not found: needed by main executable
```

even though the library ships inside the APK. Linuxify handles this at two layers:

1. **Build time (primary):** `tools/patch_elf_needed.py` rewrites proot's `DT_NEEDED`
   strings in place to the packaged names (`libtalloc.so.2` → `libtalloc.so`,
   `libandroid-shmem.so` → `libshmem.so`). Only strictly shorter names are accepted, so
   the edit is NUL-padded in place and every ELF offset stays valid. Provenance and the
   patched digests are recorded in `runtime-binaries.json`.
2. **Runtime (self-heal):** before any rootfs download, `LinuxRuntime.preflightProot()`
   executes `libproot.so --version`. If the bionic linker reports a missing library,
   `NativeDepRepair` maps it to a packaged candidate (pinned alias or version-stripped
   soname), creates a symlink named exactly like the requested soname in the private
   `runtime/syslib` directory (first entry of `LD_LIBRARY_PATH`), and retries — up to
   three times, fully offline, no user interaction. If a library genuinely cannot be
   satisfied, the install fails fast with a precise, honest error naming the library.

This is deliberately not a runtime download of native code: no executable bytes are
fetched over the network on device, keeping the supply chain pinned to the APK build.

## Post-install integrity verification + auto-repair (v0.1.0-alpha.3)

The installer never hands a partially extracted rootfs to proot. After extraction and
before the first exec, `RootfsIntegrity.verify()` checks every path the guest needs at
exec time — the ELF interpreter chain first among them — using **rootfs-relative**
resolution (absolute symlink targets such as `/lib/...` are resolved against the
rootfs, never against the Android host filesystem). If anything is missing, the
installer **auto-repairs** by re-extracting exactly the affected entries from the
already-downloaded, checksum-verified artifact — no network, no user interaction.

Exec verification itself is layered, so a single fragile path cannot fail the install:

1. `/bin/bash -lc "uname -s && echo LINUXIFY_OK"` (full bash path)
2. `/bin/sh -c "uname -s && echo LINUXIFY_OK"` (dash fallback)
3. `/usr/bin/uname` invoked directly (no shell dependency)

Between attempts the installer re-runs the native shim preflight (linker failures) and
the rootfs integrity check + repair (any other failure). Only if every automatic fix
has been attempted does the install report an error — and the technical log then shows
each self-healing step that was taken.

## Incident record: v0.1.0-alpha.2 on-device install failure

**Symptom (real device, arm64):**

```
proot error: execve("/usr/bin/bash"): No such file or directory
```

**Root cause (reproduced end-to-end on the build host):** `SafeTarExtractor` rejected
every symlink whose target *text* contained `..` and silently dropped the entry. A
`..` inside a link target is only dangerous when it escapes the rootfs; the usrmerge
layout of Ubuntu 24.04 legitimately relies on such links. On amd64 the dropped set
included the dynamic loader itself
(`usr/lib64/ld-linux-x86-64.so.2 -> ../lib/x86_64-linux-gnu/ld-linux-x86-64.so.2`),
so the kernel/proot could not resolve bash's `PT_INTERP` and every guest exec failed
with ENOENT — even though `bin/bash` itself existed and the app's readiness gate
(`File(staging, "bin/bash").isFile`) passed. 66 entries were dropped on arm64
(incl. `etc/os-release`, `usr/bin/ld.so`, `usr/lib/apt/planners/dump`).

**Fixes:**

- `SafeTarExtractor` now normalizes `..` segment-by-segment and keeps every link that
  stays inside the rootfs (targets preserved verbatim on disk). Links that genuinely
  escape are recorded in `Result.skipped` — never dropped silently.
- Hardlink targets are resolved relative to the **archive root** (POSIX), and hardlink
  entries whose source appears later in the archive are resolved in a second pass
  (ubuntu-base stores `usr/bin/perl5.38.2` before `usr/bin/perl`).
- `RootfsIntegrity` verifies the tree post-extraction and auto-repairs from the cached
  artifact (see previous section).
- Guest processes no longer inherit `LD_LIBRARY_PATH` (it exists for proot's own
  bionic linking; leaking it into the guest could shadow guest libraries after
  `apt install` pulls in e.g. `libtalloc2`).
- `targetSdk` lowered 35 → 28 (see `app/build.gradle.kts`): Android 10+ forbids
  executing binaries from app-private storage for apps targeting ≥ 29; the entire
  guest userspace lives in app-private storage. targetSdk 28 keeps the app in the
  `untrusted_app_27` SELinux domain where exec from app data is allowed — the same
  approach Termux and UserLAnd use.

**Host drill (real, not simulated):** the repo's actual extractor + real proot 5.3.0
against the pinned artifacts — both ABIs extract with 0 skipped entries and 0
integrity problems; real bash runs inside the extracted rootfs and returns
`Linux / LINUXIFY_OK` (`PROOT_RC=0`). On-device verification of alpha.3 is pending.

## Terminal

Real pseudo-terminals via the vendored Termux JNI bridge: `JNI.createSubprocess()`
forks a child with a PTY and execs proot. The terminal emulator (ANSI/UTF-8/scrollback)
is the vendored `terminal-emulator`/`terminal-view` code, rendered through a Compose
`AndroidView`. Scrollback is bounded at 2000 lines.

## Licensing

- proot: GPLv3+ (bundled binary, provenance recorded).
- Termux terminal-emulator / terminal-view / libtermux.so: Apache-2.0 origin
  (jackpal/Android-Terminal-Emulator) distributed inside termux-app; notice file at
  `docs/THIRD_PARTY_TERMUX_LICENSE.md`.
- Ubuntu base rootfs: Ubuntu layer of Ubuntu cloud images (CC-BY-SA 3.0 for the
  Ubuntu logo assets; base image contents under their respective licenses).
- This application: GPL-3.0-or-later (compatible with the GPL proot dependency).

## Verification status (honest labels)

- **host-verified** (x86_64 Linux server, proot 5.3.0 + the same rootfs variant): real
  shell launch, `pwd`/`ls`/`id`, file creation, and **file persistence across proot
  process exit + relaunch** — see repository worklog Task 2 and issue #2.
- **build-verified**: the APK compiles, packages the correct ABIs and native binaries,
  and passes its unit tests.
- **device verification: NOT performed.** The build server has no Android device and
  no KVM/emulator capability (`adb devices` empty, `/dev/kvm` absent). Issue #3 tracks
  this. The proot argv and PTY wiring are identical to the host drill except for
  Android-specific linker details, but on-device behavior is *not yet proven*.

## Known limitations

- Performance: proot syscall translation costs CPU (roughly comparable to Termux+proot).
- Some packages fail without kernel features (e.g., anything needing real mounts).
- Android may terminate background processes; servers are not guaranteed 24/7.
- `apt-get` inside the guest runs as fake-root; dpkg works, but system-call-heavy
  postinst scripts occasionally need care.
- 16 KB page-size devices (Android 15+ on some hardware): **cleared for the currently
  pinned binaries** — all bundled ELF files report `PT_LOAD p_align = 0x4000` (16 KB),
  verified during the v0.1.0-alpha.2 fix (see `runtime-binaries.json`
  `post_process.elf_alignment_check`). Re-verify this whenever binaries are re-pinned.
