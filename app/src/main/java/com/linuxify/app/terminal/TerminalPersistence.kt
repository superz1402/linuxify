package com.linuxify.app.terminal

import android.content.Context
import com.linuxify.app.runtime.RuntimeDirs
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk persistence for terminal sessions (issue #23).
 *
 * WHAT is saved and WHERE:
 *  - `filesDir/runtime/terminal/sessions.json` — the index: one record per
 *    known session (live, ended, or historical) with its id, start dir in the
 *    guest, timestamps and exit code.
 *  - `filesDir/runtime/terminal/<id>.log` — the full transcript (scrollback +
 *    screen) of that session, plain text.
 *
 * WHEN it is written:
 *  - register: record appended to the index (so even a force-kill right after
 *    spawn leaves a trace), empty log created.
 *  - throttled sweep: sessions marked dirty by the client's onTextChanged
 *    callback are snapshot at most once every [MIN_SNAPSHOT_INTERVAL_MS]; a
 *    snapshot is skipped entirely when the transcript is byte-identical to the
 *    last one written.
 *  - session finish: final snapshot + record finalized (end time, exit code).
 *  - app background (ON_STOP) + a 10s periodic loop in AppViewModel both call
 *    [flushAll] / [sweep], so the worst case loss window is a few seconds of
 *    scrollback on a hard process kill.
 *
 * Nothing here is ever deleted implicitly — history lives until the user
 * deletes a single entry or clears all of it ("does not get wiped until we
 * do", issue #23).
 *
 * Restoring a LIVE pty across process death is impossible (the child is a
 * process child of the app), so the honest maximum is what this class does:
 * full text history + metadata + one-tap "start a shell in the same
 * directory".
 */
object TerminalPersistence {

    private const val FORMAT = 1
    private const val INDEX_FILE = "sessions.json"
    private const val MIN_SNAPSHOT_INTERVAL_MS = 3_000

    /** Metadata kept for every known session (also the JSON record shape). */
    data class Record(
        val id: String,
        val workdir: String,
        val startedMs: Long,
        val endedMs: Long? = null,
        val exitCode: Int? = null,
        val endCause: String? = null,
    ) {
        val isFinished: Boolean get() = endedMs != null
    }

    // ---- in-memory state --------------------------------------------------

    /** All known records, keyed by id — the single source for index writes. */
    private val records = LinkedHashMap<String, Record>()

    /** Live sessions -> their record id (finished sessions are removed). */
    private val liveIds = ConcurrentHashMap<TerminalSession, String>()

    /** Sessions registered in THIS process -> record id. Kept after finalize so
        the async exit callback (onSessionFinished) can still write the real
        exit code + cause when the tab was closed in the same instant the
        process died — the earlier finalize gets upgraded instead of lost. */
    private val knownIds = ConcurrentHashMap<TerminalSession, String>()

    /** Sessions whose transcript changed since the last snapshot. */
    private val dirty = ConcurrentHashMap.newKeySet<TerminalSession>()

    /** Last transcript written per session id — skips identical rewrites. */
    private val lastSnapshot = ConcurrentHashMap<String, Int>() // id -> transcript hashCode

    private val lastWriteMs = ConcurrentHashMap<String, Long>()

    /**
     * THE single disk writer for this class — one coroutine at a time, in
     * enqueue order.
     *
     * Why it exists: every disk touch here used to run on the CALLER's thread,
     * and the callers are the main thread in the three paths that matter most:
     * spawn (register → index write), session end (finalize → up to a few
     * hundred KB of transcript + index — a visible frame hang exactly when the
     * shell ends), and ON_STOP (flushAll — a multi-file flash write while the
     * user is mid-backgrounding gesture). All writes now land on this serial
     * queue: order is preserved (a queued final snapshot always beats a later
     * queued delete), tmp+rename never interleaves, and the caller returns
     * instantly. The foreground service keeps the process alive in the
     * background, so an ON_STOP flush still completes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // ---- paths -------------------------------------------------------------

    fun dir(ctx: Context): File = RuntimeDirs.terminalDir(ctx)
    fun logFile(ctx: Context, id: String): File = File(dir(ctx), "$id.log")
    private fun indexFile(ctx: Context): File = File(dir(ctx), INDEX_FILE)

    // ---- lifecycle hooks (called from TerminalSessionRegistry/client) ------

    fun register(ctx: Context, session: TerminalSession, workdir: String) {
        val rec = Record(
            id = newId(),
            workdir = workdir,
            startedMs = System.currentTimeMillis(),
        )
        liveIds[session] = rec.id
        knownIds[session] = rec.id
        synchronized(records) { records[rec.id] = rec }
        dirty.add(session)
        val emptyLog = logFile(ctx, rec.id)
        writeScope.launch { runCatching { emptyLog.writeText("") } }
        writeIndex(ctx)
    }

    /** Mark a session's transcript changed (cheap; called per output chunk). */
    fun markDirty(session: TerminalSession) {
        if (liveIds.containsKey(session)) dirty.add(session)
    }

    /**
     * Snapshot sessions marked dirty — at most one write per session per
     * [MIN_SNAPSHOT_INTERVAL_MS]. Called from the 10s AppViewModel loop.
     */
    fun sweep(ctx: Context) {
        val now = System.currentTimeMillis()
        for (s in dirty) {
            val id = liveIds[s]
            if (id == null) {
                dirty.remove(s)
                continue
            }
            val last = lastWriteMs[id] ?: 0L
            if (now - last < MIN_SNAPSHOT_INTERVAL_MS) continue
            snapshot(ctx, s, id)
        }
    }

    /** Snapshot everything live right now (ON_STOP / finalize paths). */
    fun flushAll(ctx: Context) {
        for ((s, id) in liveIds) snapshot(ctx, s, id)
    }

    /**
     * Final snapshot + index update when a session exits. Idempotent AND
     * upgradable: the first call stamps the end time; a later call with the
     * real exit code/cause (the async onSessionFinished after a tab close)
     * fills them in without resetting the timestamp.
     */
    fun finalizeSession(ctx: Context, session: TerminalSession, exitCode: Int?, cause: String? = null) {
        val id = knownIds[session] ?: return
        liveIds.remove(session)
        dirty.remove(session)
        snapshot(ctx, session, id)
        synchronized(records) {
            records[id]?.let {
                records[id] = it.copy(
                    endedMs = it.endedMs ?: System.currentTimeMillis(),
                    exitCode = exitCode ?: it.exitCode,
                    endCause = cause ?: it.endCause,
                )
            }
        }
        writeIndex(ctx)
    }

    /** Registry removal (tab closed while app alive) — finalize as ended. */
    fun forget(ctx: Context, session: TerminalSession) {
        finalizeSession(ctx, session, exitCodeOrNull(session))
    }

    // ---- history (read path) ----------------------------------------------

    /** All known records without a live session, oldest first. */
    fun history(): List<Record> = synchronized(records) {
        val live = liveIds.values.toHashSet()
        records.values.filter { it.id !in live }.sortedBy { it.startedMs }
    }

    /**
     * Load the index from disk. Called ONCE per process, before any session
     * is registered (AppViewModel init). Records for sessions that were alive
     * when the process died keep `endedMs == null` and are surfaced as
     * "interrupted" in the UI — the transcript on disk is whatever the last
     * sweep captured.
     */
    fun loadFromDisk(ctx: Context) {
        synchronized(records) { records.clear() }
        knownIds.clear()
        lastSnapshot.clear()
        val f = indexFile(ctx)
        if (!f.isFile) return
        runCatching {
            parseIndex(f.readText()).forEach { records[it.id] = it }
        }
    }

    // ---- user actions on history ------------------------------------------

    /** Delete one history entry (log + record). Live sessions are untouched. */
    fun deleteHistory(ctx: Context, id: String): Boolean {
        if (liveIds.containsValue(id)) return false
        val removed = synchronized(records) { records.remove(id) } != null
        if (removed) {
            knownIds.values.removeAll { it == id }
            writeIndex(ctx)
            val f = logFile(ctx, id)
            writeScope.launch { runCatching { f.delete() } }
        }
        return removed
    }

    /** Delete ALL history (logs + index records). Live sessions keep running. */
    fun clearHistory(ctx: Context): Int {
        val live = liveIds.values.toHashSet()
        val doomed = synchronized(records) {
            val d = records.values.filter { it.id !in live }.map { it.id }
            d.forEach { records.remove(it) }
            d
        }
        if (doomed.isNotEmpty()) {
            knownIds.values.removeAll { it in doomed }
            writeIndex(ctx)
            doomed.forEach { id ->
                val f = logFile(ctx, id)
                writeScope.launch { runCatching { f.delete() } }
            }
        }
        return doomed.size
    }

    // ---- internals ----------------------------------------------------------

    private fun snapshot(ctx: Context, session: TerminalSession, id: String) {
        dirty.remove(session)
        lastWriteMs[id] = System.currentTimeMillis()
        val transcript = try {
            session.emulator?.screen?.transcriptText
        } catch (_: Exception) {
            null
        } ?: return
        // Identical transcript -> skip the write entirely (flash friendliness).
        if (lastSnapshot[id] == transcript.hashCode()) return
        val out = logFile(ctx, id)
        val tmp = File(out.parentFile, "${out.name}.tmp")
        // Disk write runs on the single serial writer (see writeScope): the
        // caller — frequently the MAIN thread via finalize/ON_STOP — returns
        // immediately instead of blocking on flash. The skip-hash is recorded
        // only AFTER a successful write, so a failed write is retried by the
        // next sweep instead of being silently dropped forever.
        writeScope.launch {
            runCatching {
                tmp.writeText(transcript)
                if (!tmp.renameTo(out)) {
                    // rename can fail across mount boundaries — fall back to copy
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                lastSnapshot[id] = transcript.hashCode()
            }
        }
    }

    private fun writeIndex(ctx: Context) {
        val f = indexFile(ctx)
        val tmp = File(f.parentFile, "$INDEX_FILE.tmp")
        // JSON is serialized at EXECUTION time on the single writer thread, so
        // the final file always reflects the freshest record state regardless
        // of enqueue order.
        writeScope.launch {
            runCatching {
                val json = synchronized(records) { indexJson(records.values.toList()) }
                tmp.writeText(json)
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    private fun newId(): String =
        System.currentTimeMillis().toString(36) + "-" + (0..0xFFFF).random().toString(16).padStart(4, '0')

    private fun exitCodeOrNull(session: TerminalSession): Int? = try {
        if (session.isRunning) null else session.exitStatus
    } catch (_: Exception) {
        null
    }

    // ---- JSON (pure, JVM-tested) -------------------------------------------

    fun recordToJson(r: Record): JSONObject = JSONObject()
        .put("id", r.id)
        .put("workdir", r.workdir)
        .put("started", r.startedMs)
        .put("ended", r.endedMs ?: JSONObject.NULL)
        .put("exit", r.exitCode ?: JSONObject.NULL)
        .put("cause", r.endCause ?: JSONObject.NULL)

    fun recordFromJson(o: JSONObject): Record? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return Record(
            id = id,
            workdir = o.optString("workdir", "/root"),
            startedMs = o.optLong("started", 0L),
            endedMs = if (o.isNull("ended")) null else o.optLong("ended"),
            exitCode = if (o.isNull("exit")) null else o.optInt("exit"),
            endCause = o.optString("cause").takeIf { it.isNotEmpty() },
        )
    }

    fun indexJson(list: List<Record>): String = JSONObject()
        .put("format", FORMAT)
        .put("records", JSONArray().apply { list.forEach { put(recordToJson(it)) } })
        .toString()

    /** Tolerant parse: malformed entries are skipped, never thrown. */
    fun parseIndex(text: String): List<Record> = runCatching {
        val root = JSONObject(text)
        if (root.optInt("format") != FORMAT) return emptyList()
        root.optJSONArray("records")?.let { arr ->
            (0 until arr.length()).mapNotNull { recordFromJson(arr.optJSONObject(it) ?: return@mapNotNull null) }
        } ?: emptyList()
    }.getOrDefault(emptyList())
}
