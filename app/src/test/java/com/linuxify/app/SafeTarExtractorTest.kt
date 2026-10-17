package com.linuxify.app

import com.linuxify.app.runtime.SafeTarExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

class SafeTarExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tarOf(build: (TarArchiveOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        val tos = TarArchiveOutputStream(GZIPOutputStream(bos))
        build(tos)
        tos.finish()
        tos.close()
        return bos.toByteArray()
    }

    @Test
    fun `sanitizes dot segments`() {
        val ex = SafeTarExtractor(tmp.newFolder())
        assertEquals("usr/bin/bash", ex.sanitizePath("./usr/bin/bash"))
        assertEquals("usr/bin", ex.sanitizePath("usr/./bin"))
    }

    @Test
    fun `rejects absolute and traversal paths`() {
        val ex = SafeTarExtractor(tmp.newFolder())
        assertNull(ex.sanitizePath("/etc/passwd"))
        assertNull(ex.sanitizePath("../escape"))
        assertNull(ex.sanitizePath("usr/../../escape"))
        assertNull(ex.sanitizePath(".."))
        assertNull(ex.sanitizePath(""))
    }

    @Test
    fun `extracts regular files and directories`() {
        val dest = tmp.newFolder()
        val data = "hello linuxify".toByteArray()
        val bytes = tarOf { tos ->
            val dir = TarArchiveEntry("root/")
            tos.putArchiveEntry(dir); tos.closeArchiveEntry()
            val f = TarArchiveEntry("root/persistence_test.txt")
            f.size = data.size.toLong()
            f.mode = 0b110100100
            tos.putArchiveEntry(f); tos.write(data); tos.closeArchiveEntry()
        }
        val result = SafeTarExtractor(dest).extract(bytes.inputStream())
        assertEquals(2, result.entries)
        assertEquals("hello linuxify", File(dest, "root/persistence_test.txt").readText())
    }

    @Test
    fun `rejects path traversal entries during extraction`() {
        val dest = tmp.newFolder()
        val data = "evil".toByteArray()
        val bytes = tarOf { tos ->
            val f = TarArchiveEntry("../outside.txt")
            f.size = data.size.toLong()
            tos.putArchiveEntry(f); tos.write(data); tos.closeArchiveEntry()
        }
        // The extractor skips traversal entries silently (never throws for a simple skip)
        SafeTarExtractor(dest).extract(bytes.inputStream())
        // nothing was written outside the destination
        assertFalse(File(dest.parentFile, "outside.txt").exists())
    }

    @Test
    fun `marks executables from mode bits`() {
        val dest = tmp.newFolder()
        val data = "#!/bin/sh\necho hi".toByteArray()
        val bytes = tarOf { tos ->
            val f = TarArchiveEntry("bin/tool.sh")
            f.size = data.size.toLong()
            f.mode = 0b111101101
            tos.putArchiveEntry(f); tos.write(data); tos.closeArchiveEntry()
        }
        SafeTarExtractor(dest).extract(bytes.inputStream())
        val out = File(dest, "bin/tool.sh")
        assertTrue(out.exists())
        assertTrue("executable bit should be restored", out.canExecute())
    }
}
