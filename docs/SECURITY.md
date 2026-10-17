# Security

## Threat model summary

Linuxify executes real code and can host local network services. The design keeps the
blast radius inside the app sandbox and never escalates privileges.

## Download integrity

- All artifacts are fetched over HTTPS from pinned, versioned sources.
- SHA-256 digests are pinned in `RuntimeMetadata` (rootfs) and recorded per-binary in
  `runtime-binaries.json` (proot, libtalloc, libandroid-shmem, libtermux.so).
- A checksum failure aborts installation and deletes the artifact. There is no override.

## Archive extraction

`SafeTarExtractor` enforces:
- absolute paths rejected; `..` segments rejected anywhere in the path;
- symlinks validated to resolve inside the destination (invalid links skipped);
- device/fifo entries skipped;
- hard cap on total extracted bytes (2 GiB) against archive bombs;
- output restricted to the staging directory (canonical-path check as defense in depth).

## Filesystem containment

File browsing, viewing, and deletion are guarded by canonical-path checks against the
rootfs directory. Traversal outside app storage is rejected before any operation.

## Process execution

- Command construction uses argument arrays; no shell string concatenation of
  untrusted input into host commands.
- The only executables launched from the host side are the pinned proot binary.
- Git clone accepts HTTPS URLs only (regex-validated).
- apt operations run non-interactively with an explicit mutex (no concurrent dpkg).
- No host-level command execution is exposed over any network interface.

## Network defaults

- Services bind to loopback by default; LAN access requires explicit user choice (the
  default service definition binds `http.server` on the device only).
- The app itself requests only `INTERNET` and `ACCESS_NETWORK_STATE`.
- No port is ever auto-exposed; no tunnels are created; no remote management endpoint
  exists.

## Data and secrets

- No hardcoded secrets; the build never embeds tokens (`.gitignore` excludes
  `local.properties` and keystore material).
- App-private storage only; no all-files access requested; SAF used for user-selected
  imports/exports.
- Logs contain no credentials; the technical log in the installer contains URLs, sizes,
  and stage transitions only.

## Known residual risks

- proot is user-space translation, not a security boundary: code running inside the
  Linux userspace runs with the app's own Android permissions and sandbox.
- Users can install arbitrary packages (that is the point of the product); the honesty
  boundary is that this code can never escape beyond the app sandbox without an
  Android platform vulnerability.
- The release build is currently signed with the debug key until a maintainer
  provisioning step is completed (documented in `docs/BUILDING.md`).
