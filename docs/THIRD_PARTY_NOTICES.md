# Third-party notices and licence inventory — Linuxify (com.linuxify.app)

This document travels with the application. The canonical copy lives at
`docs/THIRD_PARTY_NOTICES.md` in the source repository; the same content and
the full licence texts listed below are bundled in the APK under
`assets/licenses/` (visible in-app: Settings → Third-party licences).

Linuxify's application code is copyright the Linuxify maintainer and is
currently distributed under **GNU GPL-3.0-or-later** (see the repository
`LICENSE`). This file covers the **third-party** components. It is a licence
notice document, not a privacy policy and not legal advice.

## Components shipped inside the APK

| Component | Version | Origin | Licence | Bundled texts | Notes |
|---|---|---|---|---|---|
| Termux terminal-emulator (Java) | v0.118.0 | github.com/termux/termux-app, `terminal-emulator` module | GPL-3.0-only per upstream `LICENSE.md` | `GPL-3.0.txt` | 17 files byte-identical to upstream; 3 files carry small published patches (see `docs/PROPRIETARY_DISTRIBUTION_AUDIT.md` §6.2) |
| Termux terminal-view + textselection (Java) | v0.118.0 | github.com/termux/termux-app, `terminal-view` module | GPL-3.0-only per upstream `LICENSE.md` | `GPL-3.0.txt` | same |
| `libtermux.so` (PTY JNI library) | v0.118.0 release | Extracted from the official termux-app v0.118.0 GitHub release APK | GPL-3.0-only (part of termux-app) | `GPL-3.0.txt` | digests pinned in `runtime-binaries.json` (`termux_jni`) |
| `libproot.so` + `libproot-loader.so` | 5.1.107.96 (Termux build) | Termux apt repository (packages.termux.dev) proot debs; upstream source github.com/termux/proot tag v5.1.107.96 | GPL-2.0-or-later | `GPL-2.0.txt` | **Modified after build** — see the corresponding-source recipe below |
| `libtalloc.so` | 2.5.0 (Termux build) | Termux apt repository `libtalloc` deb; upstream source samba.org `talloc-2.5.0.tar.gz` (SHA-256 `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007`) | LGPL-3.0 | `LGPL-3.0.txt` | linked by proot only; unmodified |
| `libshmem.so` (libandroid-shmem) | 0.7 (Termux build) | Termux apt repository `shmem` deb; upstream github.com/termux/libandroid-shmem v0.7 | BSD-3-Clause | `BSD-3-Clause.txt` | linked by proot only; unmodified |
| AndroidX / Jetpack Compose / Kotlin / coroutines / commons-compress | see `app/build.gradle.kts` | Google Maven / Maven Central | Apache-2.0 | `Apache-2.0.txt` | unmodified upstream artifacts |

## Components downloaded at runtime (NOT distributed with the APK)

- **Ubuntu base rootfs 24.04.5 (noble)** — downloaded over HTTPS from
  `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/`, verified
  against Ubuntu's published SHA256SUMS, installed into app-private storage.
  Its contents carry their own licences, published by Ubuntu and its package
  sources. Linuxify does not distribute or modify them.
- **Software installed through the in-app catalog** (APT packages from Ubuntu
  archives, npm packages, PyPI packages, and checksum-pinned static binaries
  such as yq/jq) — each is downloaded at runtime from its own upstream
  repository under its own licence.

## Corresponding-source recipe for the GPL/LGPL binaries shipped in the APK

The proot binaries are distributed in **modified** form. The complete
corresponding source that generates the shipped artifacts consists of:

1. **proot source**: github.com/termux/proot, tag `v5.1.107.96`
   (GPL-2.0-or-later; the same tag's `COPYING` is the licence text bundled as
   `GPL-2.0.txt`).
2. **Build recipe**: `termux-packages/packages/proot/build.sh`
   (github.com/termux/termux-packages) — Android/bionic cross-build with
   `PROOT_UNBUNDLE_LOADER=$PREFIX/libexec/proot`; the loader is extracted from
   the same build and shipped as `libproot-loader.so`.
3. **The post-build modification tool**:
   `tools/patch_elf_needed.py` in this repository — rewrites two `DT_NEEDED`
   strings in place (`libtalloc.so.2` → `libtalloc.so`,
   `libandroid-shmem.so` → `libshmem.so`; strictly-shorter NUL-padded names,
   no ELF structural change). This is the only difference between the shipped
   proot binary and the unmodified Termux package binary.
4. **Provenance and digests**: `runtime-binaries.json` in the repository root
   pins the source debs' SHA-256 and the shipped (patched) artifacts'
   SHA-256 for both ABIs.
5. **talloc source**: `https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz`
   (SHA-256 above; LGPL-3.0) — the exact source version from which the Termux
   `libtalloc` deb was built. The library itself is shipped unmodified.
6. **libandroid-shmem source**:
   `https://github.com/termux/libandroid-shmem` tag `v0.7` (BSD-3-Clause) —
   shipped unmodified.
7. **libtermux.so build sources**: github.com/termux/termux-app tag
   `v0.118.0` (`terminal-emulator/src/main/jni/`), built with Android NDK —
   shipped unmodified, as released.

Under the GNU GPL-2.0 these sources must accompany the binaries or be offered
in writing (valid for at least three years). The repository and its public
releases are the current delivery channel; this document identifies the exact
upstream versions so the sources can be reproduced byte-for-byte (digests in
`runtime-binaries.json`).

## Attribution statements

- The terminal emulator and terminal view code originates from
  jackpal/Android-Terminal-Emulator (Apache-2.0) as recorded by termux-app's
  `LICENSE.md`; the files shipped here are the termux-app v0.118.0 versions
  and carry GPL-3.0-only terms as published upstream. The upstream licence
  statement is preserved verbatim at `docs/THIRD_PARTY_TERMUX_LICENSE.md`.
- AndroidX and related Google/JetBrains libraries are used unmodified under
  Apache-2.0; their licences are bundled as `Apache-2.0.txt`.
- libandroid-shmem carries a BSD-3-Clause licence; its copyright notice and
  licence text are bundled as `BSD-3-Clause.txt`.

This notice file alone does not satisfy every licence condition: the GPL
components additionally require the corresponding-source availability
described above, and the LGPL library additionally requires the availability
of its source for modification and relinking. Both are satisfied by the recipe
in this document and the public repository.
