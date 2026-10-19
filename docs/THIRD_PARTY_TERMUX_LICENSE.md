<!--
INTERPRETIVE NOTE (added by the licensing audit, issue #21 — see
docs/PROPRIETARY_DISTRIBUTION_AUDIT.md §4.2). The text below is a VERBATIM
copy of termux/termux-app v0.118.0's LICENSE.md (the project the vendored
terminal code came from). Reading notes:

- The operative grant is the FIRST sentence: the repository is released under
  GPLv3 only. GitHub's licence detection therefore reports NOASSERTION.
- The "Exceptions" bullets record ORIGIN/attribution facts (jackpal's
  Android Terminal Emulator code was used under Apache-2.0), NOT a regrant of
  Apache-2.0 for the current terminal-view / terminal-emulator files. Those
  files carry no per-file licence headers, and Termux's modifications to them
  were published under the project's GPLv3-only statement.
- Practical consequence: the vendored com/termux files are GPLv3-only code.
  They may NOT be used in a proprietary app without written permission from
  the termux-app copyright holders. They are compliantly used here because
  Linuxify itself is distributed under GPL-3.0-or-later.
-->

The `termux/termux-app` repository is released under [GPLv3 only](https://www.gnu.org/licenses/gpl-3.0.html) license.

### Exceptions

- [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) code is used which is released under [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). Check [`terminal-view`](terminal-view) and [`terminal-emulator`](terminal-emulator) libraries.
- Check [`termux-shared/LICENSE.md`](termux-shared/LICENSE.md) for `termux-shared` library related exceptions.
