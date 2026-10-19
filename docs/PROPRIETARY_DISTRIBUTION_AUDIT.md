# Proprietary Distribution Audit — Can Linuxify Ship as a Closed-Source APK?

**Verdict: B — PROPRIETARY APK FEASIBLE WITH SPECIFIC CHANGES.**

Audit date: 2026-10-19 · Audited tree: `main` @ `c0ec30d` (branch
`audit/proprietary-distribution`) · Tracked in GitHub issue #21.

Every claim below is tied to a repository path or a primary upstream source
(licence files, tagged releases, package metadata) that was actually inspected
during this audit. Where a legal interpretation is genuinely uncertain, the
uncertainty is stated instead of papered over.

---

## 1. Executive summary (plain English)

Linuxify is a three-layer product. The outer layer is the Android application
itself (Kotlin + Jetpack Compose). The middle layer is the terminal — a copy of
Termux's terminal emulator and view code, plus a small prebuilt JNI library
(`libtermux.so`) that creates pseudo-terminals. The inner layer is the Linux
userspace — the `proot` executable and two of its libraries (shipped inside the
APK) and the Ubuntu root filesystem (downloaded at runtime, never bundled).

The licence question splits cleanly along those layers:

- The **inner layer is legally separate** from the app. `proot` is launched as
  an independent executable (fork/exec) and communicates with the app at
  arm's length (command-line arguments, environment variables, file
  descriptors). Under the GPL's own rules that is aggregation of two separate
  programs, not incorporation of GPL code into the app. Shipping `proot` in the
  APK does **not** make Linuxify's Kotlin code GPL — it only creates
  GPL obligations **for the proot component itself**, which are fully
  satisfiable (notices + corresponding-source materials, see §7).
- The **middle layer is the problem**. The vendored
  `com.termux.terminal`/`com.termux.view` Java sources are compiled into the
  app's `classes.dex`, and `libtermux.so` is loaded **into the app's own
  process** via `System.loadLibrary("termux")`. The upstream project that
  these files come from — `termux/termux-app` v0.118.0 — publishes them under
  **GPLv3-only** (its `LICENSE.md` says exactly that; the famous "Apache-2.0
  exception" bullet only records the jackpal *origin* of the terminal code and
  is not a licence regrant — see §4.2). A file with no licence header is
  governed by the project-level licence statement; these files have no headers.
- Consequently **the APK, as currently built, is a GPL-3.0 combined work**.
  GPL-3.0 §2(c) requires the *entire work, as a whole* to be licensed under
  GPL-3.0 to everyone who receives a copy, and §6 requires conveying the
  *complete corresponding source of the whole APK* — which includes Linuxify's
  own Kotlin/Compose sources, not just the Termux parts.
- **Today this is not a violation**: the repository already declares the app
  `GPL-3.0-or-later` (README), so the current releases are compliantly
  GPL-distributed. But it does mean **a proprietary, closed-source APK cannot
  be produced from the current source tree**.
- It **can** be produced after one well-defined change: replace the in-process
  GPL terminal stack (the 20 vendored Termux Java files + `libtermux.so`) with
  a permissively-licensed equivalent, or obtain a written licence change from
  the Termux copyright holders. Nothing else needs to move: `proot`, talloc,
  libandroid-shmem, and the runtime-downloaded Ubuntu rootfs are all compatible
  with a proprietary app.

Direct answer to the headline question:

> **Can Linuxify's original Kotlin/Compose source remain private while the APK
> is distributed publicly?**
> **Not as currently designed — yes after specific changes.** Today, no:
> distributing the APK triggers GPL-3.0 §2(c) and §6, which require licensing
> the entire work under GPL-3.0 and delivering the complete corresponding
> source, which necessarily includes the original Kotlin/Compose sources,
> because they are compiled into the same APK and directly call GPL classes.
> After replacing the in-process GPL terminal stack (the only combined-work
> element) and completing the notice/corresponding-source materials described
> in §7–§8, the Kotlin/Compose sources can lawfully remain private.

---

## 2. Method and evidence standard

Facts were established from:

1. **The actual repository tree** (`main` @ `c0ec30d`): every vendored file was
   byte-compared against its upstream counterpart (17 of 20 identical, 3 with
   small local patches — §4.1).
2. **The exact upstream release** the code came from
   (`termux/termux-app` tag `v0.118.0`): `LICENSE.md`, `termux-shared/LICENSE.md`,
   README, and the raw files themselves were fetched and inspected.
3. **The exact binary provenance** recorded in `runtime-binaries.json`, verified
   against the pinned upstream artifacts: the talloc 2.5.0 tarball downloaded
   from samba.org (SHA-256 `912afa23…` — matches the Termux build input), the
   Termux `proot` package build recipe (`termux-packages/packages/proot/build.sh`),
   and the proot source headers at the pinned tag `termux/proot@v5.1.107.96`.
4. **Primary licence texts**: GPL-3.0, GPL-2.0, LGPL-3.0, Apache-2.0, and the
   BSD-3-Clause grant, plus the GNU GPL FAQ entries on aggregation, linking,
   and incorporation of GPL code into proprietary systems
   (gnu.org/licenses/gpl-faq.en.html — anchors `MereAggregation`,
   `GPLContainers`, `GPLInProprietarySystem`, `GPLStaticVsDynamic`,
   `IfLibraryIsGPL`).

Two previous statements in this repository's documentation were found to be
inaccurate and are corrected by this audit (see §10): `docs/RUNTIME.md` called
proot "GPLv3+" (it is GPL-2.0-or-later), and the framing around
`docs/THIRD_PARTY_TERMUX_LICENSE.md` invited reading an origin note as a
licence grant.

---

## 3. What ships in the APK vs. what is downloaded at runtime

| # | Component | Shipped in APK? | Origin (exact) | Licence (exact) |
|---|-----------|-----------------|----------------|-----------------|
| 1 | Linuxify Kotlin/Compose app | Yes (classes.dex) | This repository | Original work — licence chosen by maintainer (currently declared GPL-3.0-or-later) |
| 2 | `com.termux.terminal.*` (13 files) | Yes (classes.dex) | termux-app v0.118.0, `terminal-emulator` module | GPLv3-only per upstream `LICENSE.md` (Apache-2.0 origin note only) |
| 3 | `com.termux.view.*` + `textselection/*` (7 files) | Yes (classes.dex) | termux-app v0.118.0, `terminal-view` module | Same as #2 |
| 4 | `libtermux.so` (PTY JNI) | Yes (jniLibs) | Extracted from the official termux-app v0.118.0 release APK | GPLv3-only (part of termux-app) |
| 5 | `libproot.so` (+ `libproot-loader.so`) | Yes (jniLibs) | Termux apt repo `proot` debs; upstream `termux/proot@v5.1.107.96`; **modified post-build** (DT_NEEDED rewrite) | GPL-2.0-or-later |
| 6 | `libtalloc.so` | Yes (jniLibs) | Termux apt repo `libtalloc` deb (talloc 2.5.0, samba.org tarball) | LGPL-3.0 |
| 7 | `libshmem.so` (`libandroid-shmem` 0.7) | Yes (jniLibs) | Termux apt repo `shmem` deb; upstream `termux/libandroid-shmem` v0.7 | BSD-3-Clause |
| 8 | Ubuntu base rootfs 24.04.5 | **No** — downloaded at runtime from cdimage.ubuntu.com, SHA-256-pinned | Ubuntu cloud images | Many licences (Ubuntu's own distribution); never conveyed by the app developer |
| 9 | apt/npm/pip packages, static binaries (yq, jq) | **No** — downloaded at runtime into the guest | Ubuntu archives / npm / PyPI / GitHub releases | Their own licences; never conveyed by the app developer |
| 10 | AndroidX, Compose, Kotlin stdlib, coroutines, commons-compress | Yes (classes.dex) | Google Maven / Maven Central | Apache-2.0 |
| 11 | junit, org.json | No (test-only) | Maven Central | EPL / JSON licence — not shipped, no obligations |

Provenance for #4–#7 is recorded, with pinned SHA-256 digests, in
`runtime-binaries.json`; the rootfs URLs and digests for #8 are in
`RuntimeMetadata.kt` and the same JSON.

---

## 4. The combined-work analysis (Phase 2 of the audit)

### 4.1 What the Kotlin code actually does with each component

**Termux terminal stack — combined, unambiguously.**

- `app/src/main/java/com/termux/terminal/JNI.java` (vendored) contains
  `static { System.loadLibrary("termux"); }` — the GPL JNI library is loaded
  into the **app's own process** at class-load time.
- `app/src/main/java/com/linuxify/app/terminal/PtyTerminal.kt` (lines 7–8)
  imports `com.termux.terminal.TerminalSession` and `TerminalSessionClient`,
  constructs the proot argv (lines 39–58), and instantiates
  `TerminalSession(...)` (lines 61–68) — Linuxify's own code directly extends
  and calls GPL classes inside the app process.
- `app/src/main/java/com/linuxify/app/terminal/TerminalSessionRegistry.kt`
  (lines 8–10) implements `TerminalSessionClient` and holds
  `com.termux.view.TerminalView`.
- `app/src/main/java/com/linuxify/app/ui/screens/TerminalScreen.kt` (lines
  58–60) embeds the GPL `TerminalView` in the Compose UI.

Under GPL-3.0 these files form **one combined work**: they are compiled into
one `classes.dex`, run in one process, and the app code is written against the
GPL classes' own APIs. The GNU GPL FAQ states that statically or dynamically
linking GPL-covered code into a program makes the whole combination a work
based on the GPL program (`GPLStaticVsDynamic`, `IfLibraryIsGPL`), and that
packaging code in one or more containers has no bearing on whether the pieces
form a single work (`GPLContainers` — relevant because an APK bundles dex,
resources, and jniLibs into one signed artifact).

**proot — separate program, not combined.**

- `LinuxRuntime.kt` launches `libproot.so` via `ProcessBuilder` (line ~167) —
  a fork/exec of an **independent executable** from `nativeLibraryDir`.
- Communication is exactly the "arm's length" kind the GPL FAQ names as
  separating programs: command-line arguments (`buildProotArgs()` — `-0
  --link2symlink --kill-on-exit -r <rootfs> -b /dev -b /proc -b /sys …`),
  environment variables (`buildEnv()` — `PROOT_LOADER`, `LD_LIBRARY_PATH`,
  …), and inherited file descriptors (the PTY). No GPL code is linked into the
  app's address space by proot; the app never imports proot symbols.
- `docs/SECURITY.md` corroborates the design: "The only executables launched
  from the host side are the pinned proot binary."

This is the GPL-3.0 §2 "mere aggregation" situation (and the FAQ's
`MereAggregation` position): a separate program shipped on the same medium.
The proot obligations attach **to proot**, not to Linuxify's application code.

**Ubuntu rootfs and guest software — not even shipped by the developer.**

The rootfs tarball is fetched over HTTPS from `cdimage.ubuntu.com` by the
user's device (`RootfsInstaller.kt`, `RuntimeMetadata.BASE_URL`), checksummed
against Ubuntu's published SHA256SUMS, and extracted locally. apt/npm/pip
installs similarly download from Ubuntu mirrors / npm / PyPI at runtime. The
developer **never conveys** any of it — the app is an orchestrator/downloader,
like a browser. What this changes: nothing for the app's own code. What it
does **not** change: (a) the guest software itself is still GPL etc. and end
users receive it under its own licences from the upstream distribution
channels; (b) if a future build ever **bundled** a rootfs, kernel, or GPL
binary into the APK (or into app assets distributed by the developer), the
analysis would change and obligations would attach at that point; (c) the
guest never receives, and never runs, app code — no GPL code flows between
guest and app process beyond the arm's-length channels already described.

### 4.2 The Termux licence question, settled from evidence

The vendored files carry **no licence headers** (verified: upstream v0.118.0
files start at `package com.termux.terminal;`). For files without headers, the
project-level licence statement governs. The upstream project-level statement
is `LICENSE.md` at the repo root, whose full text (copied verbatim into this
repo as `docs/THIRD_PARTY_TERMUX_LICENSE.md`) is:

> The `termux/termux-app` repository is released under [GPLv3 only](…) license.
>
> ### Exceptions
>
> - [Terminal Emulator for Android](…) code is used which is released under
>   [Apache License 2.0](…). Check [`terminal-view`](…) and
>   [`terminal-emulator`](…) libraries.
> - Check [`termux-shared/LICENSE.md`](…) for `termux-shared` library related
>   exceptions.

Reading this accurately matters more than anything else in this audit:

1. **The first sentence is the grant**: the repository is released under
   GPLv3 **only**. GitHub's automated licence detection consequently reports
   `NOASSERTION` (a custom statement), not "Apache-2.0".
2. **The first exception bullet records origin, not a regrant.** Its text —
   "code **is used** which is released under Apache License 2.0" — is the
   attribution that Apache-2.0 §4 obliges derivative users to carry. It says
   Termux *used* jackpal's Apache-2.0 code; it does not say "the terminal-view
   and terminal-emulator libraries are released under Apache-2.0". A regrant
   would also require Apache-2.0 copyright/licence notices in the files
   themselves; there are none.
3. **The terminal code has since been massively modified by Termux authors**
   (the 122 KB `TerminalEmulator.java` bears little resemblance to jackpal's
   originals). Those modifications were published in a repo declared
   "GPLv3 only", without any permissive headers. The evidence-based position
   is therefore: whatever Apache-2.0 content remains from jackpal's original
   lines stays Apache-2.0 (Apache grants are irrevocable), but the files **as
   they exist today contain GPLv3-only content** and cannot be extracted as
   Apache-2.0.
4. The practical consequence is symmetric and worth stating: **Termux itself
   could not ship these files in a proprietary app**, and neither can any
   third party, without permission from the Termux copyright holders.

An app that needs a permissively-licensed terminal emulator must therefore use
genuinely permissive code (jackpal's *originals*, ConnectBot's
Apache-2.0 terminal, an MIT emulator such as xterm.js, or a fresh
implementation) — not the Termux-modified versions of jackpal's code.

### 4.3 Whose source must be disclosed, and why

Because the APK is a combined work containing GPLv3-only code:

- **GPL-3.0 §2(c)**: "You must license the entire work, as a whole, under this
  License to anyone who comes into possession of a copy. This License will
  therefore apply … to the whole of the work, and all its parts, regardless of
  who wrote it."
- **GPL-3.0 §0 ("Corresponding Source")** and **§6**: conveying the APK in
  object-code form requires conveying the machine-readable Corresponding
  Source of **the whole covered work** under GPL-3.0.

"Whose source" is therefore: (a) the vendored Termux files (already public
upstream — the Linuxify patches to 3 of them are in this public repo), (b)
`libtermux.so`'s build sources (public upstream at the v0.118.0 tag), and
(c) **all of Linuxify's own Kotlin/Compose sources** — because they are part
of the single covered work. The obligation does not stop at the GPL files'
boundaries; that is precisely the effect of §2(c), and it is why the current
tree cannot be the basis of a closed-source APK.

By contrast, if the in-process GPL stack is removed/replaced: proot's GPL
obligations stay confined to proot (its Corresponding Source is deliverable
without exposing anything else — see §7), talloc's LGPL obligations stay
confined to talloc, and Linuxify's own code is no longer part of any GPL
covered work. The app's own code was **not** derived from GPL source (it is
original work that *links to* GPL interfaces) — so once the GPL classes are
gone, the app sources are not a derivative of anything GPL.

---

## 5. Component-by-component assessment

### 5.1 Linuxify's own Kotlin/Compose application

- **Copyright**: original work of the maintainer; no GPL source was copied
  into it (verified: the only `com.termux.*` references in `com/linuxify/`
  are the interface imports/uses listed in §4.1).
- **Licence today**: declared `GPL-3.0-or-later` in README (a maintainer
  choice, freely changeable **for future distributions** because the
  maintainer owns the copyright — prior recipients keep their GPL rights to
  the versions they already received; see §9).
- **Proprietary compatibility**: blocked **only** by the combined work with
  the Termux stack (§4). After the terminal replacement: fully compatible
  with proprietary distribution. Effort note: the terminal emulator is core
  functionality, so this is a significant engineering project, not a
  cosmetic change (see §8 for the realistic options).

### 5.2 Termux-derived Java code + `libtermux.so`

- **Version/provenance**: termux-app v0.118.0 (both the Java files —
  17/20 byte-identical, 3 with the small Linuxify patches listed in §6.2 —
  and the `libtermux.so` JNI library, taken from the official v0.118.0
  release APK; SHA-256s pinned in `runtime-binaries.json`).
- **Licence**: GPLv3-only (§4.2). The jackpal Apache-2.0 lineage does **not**
  make today's files Apache-2.0.
- **Modifications**: by Termux vs jackpal (massive, upstream, GPL) and by
  Linuxify vs v0.118.0 (3 files, small, listed in §6.2, published in this
  repo — compliant under the current GPL distribution).
- **Distribution**: shipped in the APK (dex + jniLibs) — this is the sole
  element that makes the APK a GPL combined work.
- **Proprietary compatibility**: **incompatible**. Must be replaced,
  removed, or relicensed in writing by the copyright holders (see §8).

### 5.3 Native binaries

| Binary | Licence | How used | Obligations when the APK is distributed | Proprietary-compatible? |
|--------|---------|----------|------------------------------------------|--------------------------|
| `libproot.so` | GPL-2.0-or-later (source headers at `termux/proot@v5.1.107.96`: "version 2 … or (at your option) any later version"; Termux package metadata `GPL-2.0`; upstream `COPYING` embeds GPL-2.0) | Executed as separate program; never linked into the app | Notices + Corresponding Source **for proot** — including for the post-build **modification** (DT_NEEDED rewrite) | **Yes**, with the §7 materials |
| `libproot-loader.so` | GPL-2.0-or-later (same source, static loader) | Executed by proot | Same as proot | Yes |
| `libtalloc.so` | LGPL-3.0 (talloc 2.5.0 tarball `LICENSE`; note: Termux's `libtalloc/build.sh` metadata field says "GPL-3.0", which is imprecise — the tarball is the authority; either way the obligations are satisfiable by providing the library source) | `DT_NEEDED` of proot only; never linked by the app | Provide the library's source (upstream tarball) + licence text; note the FSF position on LGPL inside indivisible APK containers as a legal-review item (§11) | **Yes**, with source provision |
| `libshmem.so` | BSD-3-Clause (`termux/libandroid-shmem` v0.7) | `DT_NEEDED` of proot only | Keep copyright + licence text in the distribution notices | **Yes** |

The proot modification deserves emphasis because it is the one place where a
distributed binary **differs from upstream**: `tools/patch_elf_needed.py`
rewrites two `DT_NEEDED` strings in place (`libtalloc.so.2` → `libtalloc.so`,
`libandroid-shmem.so` → `libshmem.so`; NUL-padded, shorter strings only). GPL
distribution of a modified binary requires offering the Corresponding Source
that generates **that** binary. The repository already contains all the
ingredients (upstream tag + provenance JSON + the patch tool); §7 packages
them into a delivery recipe.

### 5.4 Ubuntu root filesystem and guest-installed software

- **What it is**: ubuntu-base 24.04.5 (noble) tarball + whatever the user
  installs later via apt/npm/pip/static-binary downloads.
- **Where it comes from**: `https://cdimage.ubuntu.com/ubuntu-base/releases/
  24.04/release/…` (HTTPS, SHA-256-pinned against Ubuntu's published
  SHA256SUMS) and the guests' own upstream repositories.
- **Licence**: the rootfs contents are under thousands of individual
  licences maintained by Ubuntu; the app developer is **not** their
  distributor (see §4.1).
- **Proprietary compatibility**: fully compatible with a proprietary app.
  The runtime-download design is not a loophole being exploited — it is the
  factual architecture, and it is also what Termux/UserLAnd-class products
  do. The app should describe in its documentation what software it
  downloads (it does: `docs/RUNTIME.md`, `docs/SECURITY.md`,
  `runtime-binaries.json`).

### 5.5 Gradle dependencies

All shipped dependencies are Apache-2.0 (androidx.* incl. Compose BOM 2024.12.01,
activity-compose 1.9.3, navigation-compose 2.8.5, lifecycle 2.8.7, core-ktx
1.15.0; org.jetbrains.kotlinx:kotlinx-coroutines-android 1.9.0;
org.apache.commons:commons-compress 1.26.2; the Kotlin stdlib and Compose
compiler ship inside those artifacts). Apache-2.0 is proprietary-compatible;
its conditions that matter here are §4 (include the licence text, retain
copyright/NOTICE content, state significant changes to modified files — all
satisfied by the third-party notices document) and §3 (patent grant intact).
`junit` (EPL) and `org.json` are test-only and are not shipped in the APK —
no distribution obligations arise.

---

## 6. Modification records (exact)

### 6.1 Modified GPL binaries (shipped)

1. `libproot.so` — both ABIs: `DT_NEEDED` strings rewritten post-build by
   `tools/patch_elf_needed.py` (no ELF structural change; SHA-256 of the
   patched artifacts pinned in `runtime-binaries.json` under
   `post_process.sha256_patched`). Rationale: Android's jniLibs extraction
   only unpacks `lib*.so` names.
2. `libproot-loader.so` — no patching (static EXEC ELF), but its extraction
   from the same debs is part of the build recipe.
3. `libtalloc.so`, `libshmem.so` — no patching; only the extraction/packaging
   under different names.

### 6.2 Modified vendored Java files (vs termux-app v0.118.0)

| File | Change | Commit |
|------|--------|--------|
| `com/termux/view/TerminalView.java` | +8 lines: defensive `mClient != null` guard around `mClient.onEmulatorSet()` (alpha.6 on-device NPE fix, with explanatory comment) | `afb12ab` |
| `com/termux/view/textselection/TextSelectionCursorController.java` | 19 lines: same-family null-guard/context fixes | `afb12ab` |
| `com/termux/view/textselection/TextSelectionHandleView.java` | 2 lines: `import com.termux.view.R` → `import com.linuxify.app.R` (resource namespace) | `8353db3` |

All other 17 vendored files are byte-identical to upstream v0.118.0. Under the
current GPL distribution model these modifications are published in this
public GPL repository — compliant. Under a future proprietary model these
files are removed/replaced entirely (§8), so no separate handling is needed.

---

## 7. Notices and corresponding-source obligations, per distribution model

### 7.1 Current model (app distributed under GPL-3.0-or-later) — what is required

1. Include the licence text with the work (GPL-3.0 §4): the repo previously
   had **no LICENSE file** — **added by this audit** (root `LICENSE`).
2. Complete Corresponding Source of the whole APK must be obtainable: the
   public GitHub repository is the source; releases link to it. Satisfied
   (the repo is public); keep release pages linking the exact commit/tag.
3. Third-party notices: Termux GPL attribution + Apache/BSD/LGPL components
   must be stated — **added by this audit**: `docs/THIRD_PARTY_NOTICES.md`
   (comprehensive inventory + source table) and an in-app "Third-party
   licences" screen reading the bundled notices (Settings screen), so the
   notices travel with the APK itself.
4. Modified-GPL-binary source materials (proot DT_NEEDED patch): packaged as
   the "Corresponding source recipe" in `docs/THIRD_PARTY_NOTICES.md` §Source
   (upstream tag URL, build recipe pointer, patch tool, digests).

### 7.2 Future proprietary model — what would be required

1. **Notices** (unchanged in kind): BSD-3-Clause copyright/licence text for
   libandroid-shmem; Apache-2.0 licence/NOTICE preservation for the AndroidX
   stack; LGPL-3.0 licence text for talloc; GPL-2.0 licence text + source
   offer for proot.
2. **Corresponding source for proot** (GPL-2.0 §3): with the APK distribution
   the source can accompany it or be offered in writing ("valid for at least
   three years", GPLv2 §3(b)). The delivery recipe (upstream tag v5.1.107.96
   of `termux/proot`, the termux-packages `build.sh` build recipe, the
   DT_NEEDED patch tool, and the pinned digests) is documented in
   `docs/THIRD_PARTY_NOTICES.md`. The talloc 2.5.0 source tarball (samba.org,
   SHA-256 `912afa23…`) likewise. **None of this exposes Linuxify's own
   source.**
3. **The app's own code**: private. No GPL-covered code remains in-process
   after the §8 change, so no licence reaches it.

---

## 8. The smallest concrete changes for the proprietary model

Ordered by size:

1. **[BLOCKER] Replace the in-process GPL terminal stack** — remove
   `app/src/main/java/com/termux/**` (20 files) and both `libtermux.so`
   binaries, and obtain PTY + terminal emulation from a permissive source:
   - *Option 1 (closest ancestor)*: jackpal/Android-Terminal-Emulator
     (Apache-2.0) — the project Termux originally derived from; unmaintained;
     port effort moderate-high (Termux extensions: extra keys, styling,
     selection UX are not in it).
   - *Option 2*: ConnectBot's Apache-2.0 terminal (vt320-based) — mature but
     SSH-oriented; adaptation effort high.
   - *Option 3*: WebView + xterm.js (MIT) bridged to the PTY over a local
     socket — moderate effort, different rendering stack, JS asset bundled
     (MIT is proprietary-compatible with notices).
   - *Option 4*: ask the Termux copyright holders for a written dual-licence
     (Apache-2.0) grant for `terminal-emulator`/`terminal-view` — cheapest if
     granted; uncertain (§4.2 suggests the current statement is GPLv3-only on
     purpose). A qualified licensing lawyer should draft/review any such
     agreement.
   - Technical effort: **substantial** — this is the app's core surface.
     Everything else in this section is documentation hygiene.
2. **[DONE by this audit] Licence hygiene that any model needs**: LICENSE
   file, `docs/THIRD_PARTY_NOTICES.md`, corrections to `docs/RUNTIME.md` and
   `docs/THIRD_PARTY_TERMUX_LICENSE.md`, in-app notices surface (§7, §10).
3. **[After step 1] Change the app's licence statement** from
   GPL-3.0-or-later to the chosen proprietary terms in README and in-app, and
   stop distributing GPL (prior releases stay GPL — see §9).
4. **[Keep as-is]** proot/talloc/shmem packaging, runtime rootfs download —
   no architecture change is required or recommended for licensing reasons.

### Release blockers for a proprietary build

- Vendored GPL terminal stack still in-process (blocker #1 above). Nothing
  else blocks.

### Release blockers for the current GPL model (found by this audit)

- Missing LICENSE file — fixed here.
- Inaccurate licence statements in docs — fixed here (§10).
- Notices not travelling with the APK — fixed here (in-app notices).

---

## 9. Effect on already-released versions (alpha.1–alpha.14)

They were distributed under GPL-3.0-or-later (README declaration), which is
the correct and compliant classification for the current tree. Recipients'
GPL rights in those releases are irrevocable. The maintainer may change the
licence **of future versions** of his own original code at will (he holds the
copyright), but any future version that still contains the Termux-derived
files must remain GPL-3.0(-only, as those files' terms demand) or otherwise
GPL-compatible — another reason the §8 terminal replacement must physically
remove them rather than "relicense" them locally.

---

## 10. Corrections applied to repository documentation (approved compliance changes)

1. `docs/RUNTIME.md` — "proot: GPLv3+" corrected to **GPL-2.0-or-later** with
   evidence pointers; the Termux entry clarified (GPLv3-only files, Apache-2.0
   origin note); talloc recorded as LGPL-3.0; libandroid-shmem as BSD-3-Clause;
   pointer added to the new notices document.
2. `docs/THIRD_PARTY_TERMUX_LICENSE.md` — kept as the verbatim upstream
   `LICENSE.md` copy, with an interpretive preamble added so the "Exceptions"
   section can no longer be misread as a permissive regrant.
3. Root `LICENSE` added (GPL-3.0 text; README declares `GPL-3.0-or-later`).
4. `docs/THIRD_PARTY_NOTICES.md` added — the distribution-facing third-party
   inventory + corresponding-source recipe (§7).
5. In-app "Third-party licences" surface added (Settings), reading the notices
   bundled with the APK.

Not done (and why): no relicensing of the app away from GPL (that is the
maintainer's decision to make only after the §8 terminal decision); no GPL
component removed or hidden; no privacy-policy substitution; no runtime
redesign.

---

## 11. Questions for professional legal review

1. **Termux exception interpretation** (§4.2): could the `LICENSE.md`
   "Exceptions" bullet be construed as an Apache-2.0 grant covering the
   *current* terminal libraries? The evidence says no (origin phrasing, no
   file headers, GPLv3-only first sentence, GitHub NOASSERTION). A definitive
   answer requires written confirmation from the termux-app copyright
   holders — the cheapest possible resolution of this entire audit if
   favorable.
2. **Aggregation boundary in one APK container**: shipping an independent GPL
   executable (`proot`) inside the same APK and spawning it via fork/exec is
   aggregation under the GPL FAQ's arm's-length criteria; no court has ruled
   on the APK-container variant. Risk is low (the app contains no proot
   symbols; the interaction is argv/env/fds), but counsel should confirm
   comfort with the mainstream reading, and confirm the Corresponding-Source
   delivery method for proot (with-APK vs written offer vs stable URL).
3. **LGPL-3.0 inside an APK** (talloc): LGPL §4 conveys the right to modify
   the library and relink; an APK is a single signed artifact and Android
   makes library substitution impractical. Practical compliance = provide the
   library source + version pin (done via the notices recipe); the FSF's
   Android-specific position deserves counsel's review for the proprietary
   path.
4. **User Product / Installation Information** (GPL-3.0 §6): sideloaded APKs
   carry no installation constraint, so Installation Information obligations
   appear not to attach; confirm for the exact distribution channel(s)
   (GitHub releases vs any store).
5. **Store distribution terms** (if a proprietary build is ever distributed
   through an app store): check the store's terms against the chosen
   proprietary licence and the third-party obligations above.

---

## 12. Evidence appendix — what was actually inspected

- Repository tree @ `c0ec30d`: `app/build.gradle.kts`, `settings.gradle.kts`,
  `runtime-binaries.json`, `docs/RUNTIME.md`, `docs/SECURITY.md`,
  `docs/THIRD_PARTY_TERMUX_LICENSE.md`, `README.md`,
  `app/src/main/java/com/termux/**` (all 20 files),
  `app/src/main/jniLibs/{arm64-v8a,x86_64}/*` (5 libs each, sizes+digests),
  `tools/patch_elf_needed.py`, no `app/src/main/assets/` (none bundled),
  `LinuxRuntime.kt`, `PtyTerminal.kt`, `TerminalSessionRegistry.kt`,
  `TerminalScreen.kt`, `RootfsInstaller.kt`, `RuntimeMetadata.kt`.
- Upstream (fetched during the audit): `termux/termux-app@v0.118.0`
  (`LICENSE.md` full text, `termux-shared/LICENSE.md` head, README licence
  section — absent, `terminal-emulator`/`terminal-view` file headers — none,
  byte-diff of all 20 vendored files); `termux/proot@v5.1.107.96`
  (`COPYING`, `src/tracee/tracee.c` header, `src/` tree); `proot-me/proot`
  (`COPYING`); `termux-packages` (`packages/proot/build.sh`,
  `packages/libtalloc/build.sh`, `packages/libandroid-shmem/build.sh`);
  `termux/libandroid-shmem` (GitHub licence: BSD-3-Clause);
  `jackpal/Android-Terminal-Emulator` (`LICENSE` = Apache-2.0);
  samba.org `talloc-2.5.0.tar.gz` (SHA-256 `912afa237510ae542a7733998eb18a12
  bcda35ab6729c8e2ddb43e8d0ebab007`, `LICENSE` = LGPL-3.0);
  gnu.org GPL FAQ (live fetch) and the SPDX licence texts.
- Scripts/reports of the checks: `/home/z/my-project/scripts/licence_check.py`
  (byte-diff + talloc extraction), `scripts/upstream_diff/**` (per-file
  diffs).
