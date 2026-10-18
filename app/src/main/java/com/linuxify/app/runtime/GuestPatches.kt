package com.linuxify.app.runtime

import java.io.File

/**
 * Idempotent in-place upgrades applied to an ALREADY-extracted rootfs.
 *
 * alpha.7 fixed the "groups: cannot find name for group ID" prompt warnings by
 * appending the app's runtime gids to the guest /etc/group — but on at least one
 * device (Android 16) the supplementary-gid discovery came back empty, so only
 * the primary gid was patched and every supplementary gid still warned. Host
 * verification (proot + real ubuntu-base, 2026-10-19) proved the warning comes
 * from Ubuntu's /etc/bash.bashrc sudo-hint block, which runs $(groups) on every
 * interactive login shell unless $HOME/.sudo_as_admin_successful exists.
 *
 * This object therefore applies BOTH independent fixes (defense in depth):
 *
 *  1. [GuestGroups.ensureRuntimeGroupsInGuest] — harden gid discovery (two proc
 *     sources + a static floor) and append the entries. Fixes resolution for
 *     `groups`, `id` and anything else that reads /etc/group.
 *  2. Create /root/.sudo_as_admin_successful — Ubuntu's own marker; bash.bashrc
 *     then skips the whole $(groups) check at the source. Works even if gid
 *     discovery ever fails again, and prints nothing (root is not in the
 *     admin/sudo groups, so the hint itself never fired anyway).
 *  3. Rewrite /etc/profile.d/linuxify.sh with the bell-on-prompt hook: the app
 *     turns a BEL received while backgrounded into a "run completed"
 *     notification. Existing rootfs installs get the new profile through this
 *     same in-place path (the file is ours; rewriting it is safe).
 *
 * Every operation is idempotent; a fully patched rootfs makes this a cheap
 * no-op (one small file read + existence checks). [applyAll] memoizes success
 * per rootfs path for the process lifetime so per-session calls are free.
 */
object GuestPatches {

    /** Profile file written by Linuxify — owned by the app, safe to rewrite. */
    const val PROFILE_RELATIVE_PATH = "etc/profile.d/linuxify.sh"
    const val MARKER_RELATIVE_PATH = "root/.sudo_as_admin_successful"

    /**
     * The shell profile installed into the guest. PS1/TMPDIR/LANG as before,
     * plus the bell hook guarded to interactive shells (bash >= 4.4 evaluates
     * PROMPT_COMMAND before every prompt display — i.e. right after a command
     * finished, which is exactly the "run completed" moment).
     */
    val PROFILE_SH: String = """
        # Added by Linuxify (com.linuxify.app)
        export PS1='\u@\h:\w\$ '
        export TMPDIR=/tmp
        export LANG=C.UTF-8

        # Linuxify: ring the terminal bell whenever the prompt returns (i.e. a
        # command finished). The app converts a bell received while backgrounded
        # into a "run completed" notification; foreground sessions never notify.
        case "${'$'}-" in *i*) PROMPT_COMMAND="${'$'}{PROMPT_COMMAND:+${'$'}PROMPT_COMMAND; }printf '\a'" ;; esac
    """.trimIndent() + "\n"

    @Volatile
    private var appliedForPath: String? = null

    /**
     * Apply all guest patches. Returns a short human-readable summary of what
     * was done (possibly "already patched"), or null on failure — callers log
     * that, but it is non-fatal: the worst case is cosmetic prompt warnings.
     */
    fun applyAll(rootfs: File): String? {
        appliedForPath?.let { if (it == rootfs.absolutePath) return "already patched (memoized)" }
        return try {
            // Only patch a real, extracted rootfs — never create fragments in an
            // empty/absent directory (runtime not installed yet).
            if (!rootfs.isDirectory || !File(rootfs, "bin").isDirectory) return null

            val notes = mutableListOf<String>()

            val groupLines = GuestGroups.ensureRuntimeGroupsInGuest(rootfs)
            notes += when {
                groupLines == null -> "group patch failed"
                groupLines == 0 -> "groups already resolved"
                else -> "group file: $groupLines line(s) appended"
            }

            val marker = File(rootfs, MARKER_RELATIVE_PATH)
            if (!marker.exists()) {
                marker.parentFile?.mkdirs()
                if (!marker.createNewFile() && !marker.exists()) throw java.io.IOException("could not create $MARKER_RELATIVE_PATH")
                notes += "sudo-as-admin marker created"
            } else {
                notes += "sudo-as-admin marker present"
            }

            val profile = File(rootfs, PROFILE_RELATIVE_PATH)
            val profileOk = runCatching {
                profile.parentFile?.mkdirs()
                val tmp = File(profile.parentFile, profile.name + ".tmp")
                tmp.writeText(PROFILE_SH)
                if (!tmp.renameTo(profile)) {
                    profile.writeText(PROFILE_SH)
                    tmp.delete()
                }
            }.isSuccess
            notes += if (profileOk) "profile hook installed" else "profile hook FAILED"

            val summary = notes.joinToString("; ")
            // Memoize only a fully successful pass so a later call retries after
            // a transient failure (e.g. install finishing mid-way).
            if (groupLines != null && profileOk) appliedForPath = rootfs.absolutePath
            summary
        } catch (t: Throwable) {
            null
        }
    }

    /** Test hook: forget the memo. */
    fun resetMemoForTest() {
        appliedForPath = null
    }
}
