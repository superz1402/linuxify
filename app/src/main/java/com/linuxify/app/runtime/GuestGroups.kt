package com.linuxify.app.runtime

import android.system.Os
import java.io.File

/**
 * Makes the guest's /etc/group resolve the app's real runtime groups.
 *
 * Why: Android apps run in a supplementary-group sandbox (inet 3003, net_bw 3004,
 * sdcard 1015/1023...). proot's `-0` fake-root maps uid/gid lookups to 0, but the
 * kernel's supplementary group list passes through unchanged. Ubuntu's global
 * /etc/bash.bashrc runs `$(groups)` on every interactive shell start, and coreutils
 * prints "cannot find name for group ID <gid>" for every unmapped gid — the multiple
 * warning lines seen at the terminal prompt on device. Appending the app's actual
 * gid set to the guest group file resolves them all at the source.
 */
object GuestGroups {

    /**
     * Pure computation: given the current /etc/group content and the gids that must
     * resolve, return the `name:x:gid:` lines that need appending. Idempotent —
     * already-present gids never produce duplicate entries.
     */
    fun missingEntries(groupFileText: String?, gids: Collection<Int>): List<String> {
        val present = HashSet<Int>()
        groupFileText?.lineSequence()?.forEach { line ->
            // /etc/group lines END with a colon (name:passwd:gid:members, members
            // often empty) — the gid is field #3 (index 2), not after the last colon.
            val fields = line.split(':')
            val gid = fields.getOrNull(2)?.trim()?.toIntOrNull()
            if (gid != null) present += gid
        }
        return gids
            .filter { it >= 0 && it !in present }
            .distinct()
            .map { "linuxify-g$it:x:$it:" }
    }

    /**
     * Pure parser for /proc/self/groups content (one gid per line).
     */
    fun parseProcGroups(text: String?): Set<Int> = buildSet {
        text?.split(Regex("\\s+"))?.forEach { token ->
            token.toIntOrNull()?.let { add(it) }
        }
    }

    /**
     * Pure parser for the "Groups:" line of /proc/self/status
     * (space-separated supplementary gids on a single line).
     */
    fun parseStatusGroups(text: String?): Set<Int> = buildSet {
        text?.lineSequence()
            ?.firstOrNull { it.startsWith("Groups:") }
            ?.removePrefix("Groups:")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.forEach { token -> token.toIntOrNull()?.let { add(it) } }
    }

    /**
     * The app process's runtime groups, as the spawned guest children inherit them.
     *
     * Sources, merged (all optional — the union is what matters):
     *  1. /proc/self/groups — kernel supplementary list, one gid per line;
     *  2. /proc/self/status "Groups:" — the SAME kernel data in a different file.
     *     alpha.7 relied on source 1 alone and on at least one device (Android 16)
     *     the guest still showed warnings for every supplementary gid — exactly the
     *     set 3003/9997/2xxxx/5xxxx — while the primary gid (which IS resolvable
     *     through Os.getgid()) was patched. Reading both sources removes the single
     *     point of failure;
     *  3. a static floor of the groups EVERY Android app process carries
     *     (3001 net_bt, 3002 net_bt_admin, 3003 inet, 3004 net_bw, 9997 everyone).
     *     Harmless if the process is not in one of them (no warning was printed
     *     for it anyway); naming them costs one line each and guarantees the two
     *     universal groups resolve even if both proc reads fail.
     */
    fun runtimeGids(): Set<Int> = buildSet {
        runCatching { add(Os.getgid()) }
        runCatching { addAll(parseProcGroups(File("/proc/self/groups").readText())) }
        runCatching { addAll(parseStatusGroups(File("/proc/self/status").readText())) }
        addAll(WELL_KNOWN_ANDROID_GIDS)
        remove(-1)
    }

    /** Groups every Android app process may carry (see runtimeGids()). */
    val WELL_KNOWN_ANDROID_GIDS: Set<Int> = setOf(3001, 3002, 3003, 3004, 9997)

    /**
     * Idempotently patch <rootfs>/etc/group with the runtime groups.
     * Returns the number of lines appended, or null on failure (non-fatal by
     * design: an unpatched group file only costs cosmetic warnings, never function).
     */
    fun ensureRuntimeGroupsInGuest(rootfs: File): Int? = runCatching {
        // Only patch a real, extracted rootfs — never create fragments in an
        // empty/absent directory (app not installed yet).
        if (!rootfs.isDirectory || !File(rootfs, "bin").isDirectory) return@runCatching 0

        val gids = runtimeGids()
        if (gids.isEmpty()) return@runCatching 0

        val groupFile = File(rootfs, "etc/group")
        val text = if (groupFile.exists()) groupFile.readText() else ""
        val toAdd = missingEntries(text, gids)
        if (toAdd.isEmpty()) return@runCatching 0

        val sb = StringBuilder(text)
        if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
        toAdd.forEach { sb.append(it).append('\n') }

        // atomic-ish write, same pattern as the installer's other guest files
        groupFile.parentFile?.mkdirs()
        val tmp = File(groupFile.parentFile, "group.linuxify.tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(groupFile)) {
            groupFile.writeText(sb.toString())
            tmp.delete()
        }
        toAdd.size
    }.getOrNull()
}
