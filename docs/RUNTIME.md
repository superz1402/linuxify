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
`TMPDIR=/tmp`, `LD_LIBRARY_PATH=<syslibDir>:<nativeLibraryDir>` (so proot finds its
libraries), `PROOT_TMP_DIR` in app storage.

Android 10+ blocks exec() of files in writable app storage, so every native executable
is packaged inside the APK as a `jniLibs/lib*.so` file; the OS extracts them to the
read-only `nativeLibraryDir` from which execution is allowed.

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
