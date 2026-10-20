package com.linuxify.app

import com.linuxify.app.proj.ProjectsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probe TTL cache freshness (alpha.17 battery audit): Projects tab landings
 * must not re-spawn proot+git more than once per TTL window — and a device
 * clock that steps BACKWARDS between calls must read as stale, never as
 * "negative age = extra fresh" (that bug was caught by exactly this test).
 */
class ProjectsProbeTtlTest {

    @Test
    fun `fresh within ttl`() {
        val cachedAt = 1_000_000L
        assertTrue(ProjectsManager.probeCacheFresh(cachedAt, nowMs = cachedAt))            // same instant
        assertTrue(ProjectsManager.probeCacheFresh(cachedAt, nowMs = cachedAt + 1))        // 1 ms later
        assertTrue(ProjectsManager.probeCacheFresh(cachedAt, nowMs = cachedAt + ProjectsManager.PROBE_TTL_MS - 1))
    }

    @Test
    fun `stale at or after ttl`() {
        val cachedAt = 1_000_000L
        val ttl = ProjectsManager.PROBE_TTL_MS
        assertFalse("exactly TTL must already be stale (strict <)", ProjectsManager.probeCacheFresh(cachedAt, nowMs = cachedAt + ttl))
        assertFalse(ProjectsManager.probeCacheFresh(cachedAt, nowMs = cachedAt + ttl * 10))
    }

    @Test
    fun `negative age (clock stepped backwards) is stale`() {
        val cachedAt = 2_000_000L
        // "now" before cachedAt: a naive now-cachedAt < ttl would happily pass.
        assertFalse(ProjectsManager.probeCacheFresh(cachedAt, nowMs = cachedAt - 1))
        assertFalse(ProjectsManager.probeCacheFresh(cachedAt, nowMs = cachedAt - 10_000))
    }

    @Test
    fun `never cached is stale`() {
        assertFalse(ProjectsManager.probeCacheFresh(null, nowMs = 1_000_000L))
    }
}
