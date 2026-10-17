# Troubleshooting

## Installation

**"Not enough storage"** — The installer requires download + extracted + working-copy
headroom (≈350 MiB minimum, realistically more once packages are installed). Free up
space; the install resumes.

**"The downloaded files did not match their published checksums"** — The download was
corrupted (or tampered with) and was discarded. Retry. If it persists, check your
network; the app never bypasses verification.

**Download keeps failing** — Downloads resume via HTTP Range on retry. After 5 failed
attempts the installer stops with the network error; retry from the same screen.

**"A required bundled component is missing"** — The APK was built without the proot
jniLibs. Rebuild from a clean checkout (see `docs/BUILDING.md`).

**`library "libtalloc.so.2" not found: needed by main executable`** — This was a real
bug in v0.1.0-alpha.1: the linker resolves `DT_NEEDED` by literal file name, Android
only extracts `lib*.so` names from an APK, and proot asked for the versioned soname
that could not be extracted. Fixed in v0.1.0-alpha.2 two ways: proot's `DT_NEEDED`
strings are rewritten to the packaged names at build time, and the installer now runs
a native-dependency preflight that self-heals any remaining name mismatch by symlinking
the packaged library under the requested name in a private `syslib` directory (no user
action needed). If you still see this error, you are running the old APK — update.

**`library "…" not found` for a library other than libtalloc/libshmem** — The runtime
preflight tried to map the missing soname onto a packaged library and could not. The
error names the exact library; report it together with the technical log.

## Terminal

**Terminal shows nothing after install** — Wait a moment; the first shell launch goes
through proot initialization. If it stays blank, check Home shows the environment as
Ready, then start a new session (+SH key).

**`apt` says permission denied** — proot fake-root mode (`-0`) handles this; if you
spawned a manual proot without `-0`, apt will fail.

**Back button closes the app instead of the keyboard** — The terminal respects the
system keyboard flow; press Back once to dismiss the keyboard, again to leave.

## Packages

**Install fails mid-way** — Real apt output is shown in the Packages screen. Interrupted
dpkg state can be fixed inside the terminal with `dpkg --configure -a`.

**Two installs at once** — By design only one apt operation runs at a time; buttons
disable while one is running.

## Services

**Service shows "Starting" forever** — The state reflects reality: the process is alive
but its port is not reachable yet (still booting) or it failed to bind. Check Logs.

**Service dies after leaving the app** — Android may kill background processes at any
time (battery, memory, Doze). This is an honest platform limitation; long-lived hosting
needs the screen on or battery-optimization exemption (not requested automatically).

## Reset

Settings → Danger zone → Reset deletes the Linux filesystem. Projects in the app's
Projects directory are preserved. Uninstalling the app deletes everything — export
important work first.

## For developers

- `./gradlew testDebugUnitTest` — unit tests (installer support logic, path safety).
- Host runtime drill: the repository worklog (Task 2) records exact commands for
  verifying proot + rootfs behavior on a Linux host.
- Installer technical log is viewable in-app (expandable during and after install).
