package com.linuxify.app.backup

import com.linuxify.app.runtime.SafeTarExtractor
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Backup format round-trip tests (issue #23): BackupTar writes a tree with
 * modes, an executable and a symlink; SafeTarExtractor (the SAME code the
 * installer uses) reads it back. Content, exec bit and link must survive.
 * Also covers the manifest parse and the restore skip-filter behavior via
 * the pure primitives.
 */
class BackupRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `tree round-trips content modes and symlinks`() {
        val src = tmp.newFolder("src")
        val proj = File(src, "myproj").apply { mkdirs() }
        File(proj, "readme.md").writeText("hello backup")
        val script = File(proj, "run.sh")
        script.writeText("#!/bin/sh\necho hi\n")
        script.setExecutable(true)
        val nested = File(proj, "src/deep").apply { mkdirs() }
        File(nested, "main.py").writeText("print('x')\n")
        val outside = tmp.newFolder("outside")
        File(outside, "real.txt").writeText("target content")
        try {
            java.nio.file.Files.createSymbolicLink(
                File(proj, "link.txt").toPath(),
                // relative target INSIDE the tree (like real projects use) — an
                // absolute target would dangle at the destination by design
                File("readme.md").toPath(),
            )
        } catch (_: Exception) {
            // filesystem without symlink support: skip the link assertions
        }

        // write archive: entry paths relative to filesDir (projects/myproj/...)
        val bytes = ByteArrayOutputStream().use { raw ->
            GzipCompressorOutputStream(raw.buffered()).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    BackupTar.writeStringEntry(tar, BackupManager.MANIFEST_ENTRY, """{"format":1}""")
                    BackupTar.addTree(tar, proj, "projects/myproj")
                    tar.finish()
                }
            }
            raw.toByteArray()
        }

        // read back into a fresh dest with the restore-grade extractor
        val dest = tmp.newFolder("dest")
        val res = SafeTarExtractor(dest, maxTotalBytes = 64L * 1024 * 1024)
            .extract(ByteArrayInputStream(bytes))

        assertTrue("extraction should have zero safety skips, was: ${res.skipped}", res.skipped.isEmpty())
        val restored = File(dest, "projects/myproj")
        assertTrue(restored.isDirectory)
        assertEquals("hello backup", File(restored, "readme.md").readText())
        assertEquals("print('x')\n", File(restored, "src/deep/main.py").readText())
        assertTrue("exec bit must survive the round trip", File(restored, "run.sh").canExecute())
        assertFalse("plain file must not become executable", File(restored, "readme.md").canExecute())
        if (File(proj, "link.txt").exists()) {
            assertEquals("hello backup", File(restored, "link.txt").readText())
        }
    }

    @Test
    fun `manifest json round-trips through the parser`() {
        val json = JSONObject()
            .put("format", BackupManager.FORMAT)
            .put("app", "linuxify")
            .put("versionName", "0.1.0-alpha.15")
            .put("versionCode", 15)
            .put("created", 1_760_000_000_000L)
            .put("projects", org.json.JSONArray(listOf("alpha", "beta")))
            .put("terminalSessions", 3)
            .put("rootfs", true)
            .toString()

        val m = BackupManager.parseManifestStatic(json)
        assertNotNull(m)
        assertTrue(m!!.valid)
        assertEquals("0.1.0-alpha.15", m.versionName)
        assertEquals(15, m.versionCode)
        assertEquals(listOf("alpha", "beta"), m.projectNames)
        assertEquals(3, m.terminalCount)
        assertTrue(m.rootfsIncluded)
    }

    @Test
    fun `manifest parser rejects foreign or corrupt text`() {
        assertTrue(BackupManager.parseManifestStatic("not json at all") == null)
        assertTrue(BackupManager.parseManifestStatic("""{"format":999}""")?.valid == false)
    }
}
