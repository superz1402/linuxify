package com.linuxify.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal session record/index JSON round-trip (issue #23). Uses the REAL
 * org.json (declared as a JVM test dependency) — same code path as the app.
 */
class TerminalRecordTest {

    @Test
    fun `record json round-trips`() {
        val rec = TerminalPersistence.Record(
            id = "abc123-00ff",
            workdir = "/root/projects/demo",
            startedMs = 1_760_000_000_000L,
            endedMs = 1_760_000_060_000L,
            exitCode = 0,
        )
        val parsed = TerminalPersistence.recordFromJson(TerminalPersistence.recordToJson(rec))
        assertEquals(rec, parsed)
    }

    @Test
    fun `live record has null end and exit`() {
        val rec = TerminalPersistence.Record(id = "x", workdir = "/root", startedMs = 42L)
        assertTrue(!rec.isFinished)
        val json = TerminalPersistence.recordToJson(rec)
        assertTrue(json.isNull("ended"))
        assertTrue(json.isNull("exit"))
        val parsed = TerminalPersistence.recordFromJson(json)!!
        assertNull(parsed.endedMs)
        assertNull(parsed.exitCode)
        assertEquals(rec, parsed)
    }

    @Test
    fun `index round-trips a list of records`() {
        val records = listOf(
            TerminalPersistence.Record("a", "/root", 1L, 2L, 0),
            TerminalPersistence.Record("b", "/root/projects/p", 3L),
        )
        val parsed = TerminalPersistence.parseIndex(TerminalPersistence.indexJson(records))
        assertEquals(records, parsed)
    }

    @Test
    fun `parseIndex is tolerant of corrupt input`() {
        assertTrue(TerminalPersistence.parseIndex("garbage").isEmpty())
        assertTrue(TerminalPersistence.parseIndex("""{"format":1}""").isEmpty())
        assertTrue(TerminalPersistence.parseIndex("""{"format":2,"records":[]}""").isEmpty())
        // corrupt entries are skipped, good ones kept
        val mixed = """{"format":1,"records":[{"id":"ok1","workdir":"/root","started":5},"junk",{"no_id":true},{"id":"ok2","workdir":"/x","started":9}]}"""
        val parsed = TerminalPersistence.parseIndex(mixed)
        assertEquals(listOf("ok1", "ok2"), parsed.map { it.id })
    }
}
