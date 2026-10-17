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
