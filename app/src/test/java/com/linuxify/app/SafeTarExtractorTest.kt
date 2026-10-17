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

    // ------------------------------------------------------------------
    // Regression tests for the on-device v0.1.0-alpha.2 install failure:
    // SafeTarExtractor silently dropped every symlink whose target contained
    // ".." — including the ELF loader chain — so proot failed every guest exec
    // with: proot error: execve("/usr/bin/bash"): No such file or directory.
    // ------------------------------------------------------------------

    /** usrmerge-like layout: bin -> usr/bin, loader link through "..". */
    private fun usrmergeTar(loaderRealPath: String, loaderLink: String, loaderTarget: String): ByteArray {
        val data = "elf".toByteArray()
        return tarOf { tos ->
            val bin = TarArchiveEntry("bin", TarArchiveEntry.LF_SYMLINK)
            bin.linkName = "usr/bin"
            tos.putArchiveEntry(bin); tos.closeArchiveEntry()
            val usrBin = TarArchiveEntry("usr/bin/")
            tos.putArchiveEntry(usrBin); tos.closeArchiveEntry()
            val bash = TarArchiveEntry("usr/bin/bash")
            bash.size = data.size.toLong(); bash.mode = 0b111101101
            tos.putArchiveEntry(bash); tos.write(data); tos.closeArchiveEntry()
            val loader = TarArchiveEntry(loaderRealPath)
            loader.size = data.size.toLong(); loader.mode = 0b111101101
            tos.putArchiveEntry(loader); tos.write(data); tos.closeArchiveEntry()
            val link = TarArchiveEntry(loaderLink, TarArchiveEntry.LF_SYMLINK)
            link.linkName = loaderTarget
            tos.putArchiveEntry(link); tos.closeArchiveEntry()
        }
    }

    @Test
    fun `preserves usrmerge loader symlink with dotdot target`() {
        val dest = tmp.newFolder()
        val bytes = usrmergeTar(
            loaderRealPath = "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            loaderLink = "usr/lib/ld-linux-aarch64.so.1",
            loaderTarget = "aarch64-linux-gnu/ld-linux-aarch64.so.1",
        )
        val result = SafeTarExtractor(dest).extract(bytes.inputStream())
        assertEquals(0, result.skipped.size)
        assertTrue("top-level bin symlink must exist", File(dest, "bin").isDirectory)
        assertTrue("bash reachable via bin symlink", File(dest, "bin/bash").isFile)
        assertTrue("loader must exist behind the symlink", File(dest, "usr/lib/ld-linux-aarch64.so.1").isFile)
    }

    @Test
    fun `preserves dotdot loader link like usr_lib64 (amd64 shape)`() {
        val dest = tmp.newFolder()
        val bytes = usrmergeTar(
            loaderRealPath = "usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
            loaderLink = "usr/lib64/ld-linux-x86-64.so.2",
            loaderTarget = "../lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        )
        val result = SafeTarExtractor(dest).extract(bytes.inputStream())
        assertEquals("the '..' loader link must not be dropped", 0, result.skipped.size)
        assertTrue(File(dest, "usr/lib64/ld-linux-x86-64.so.2").isFile)
    }

    @Test
    fun `still rejects links escaping the rootfs`() {
        val dest = tmp.newFolder()
        val bytes = tarOf { tos ->
            val evil = TarArchiveEntry("etc/evil", TarArchiveEntry.LF_SYMLINK)
            evil.linkName = "../../outside"
            tos.putArchiveEntry(evil); tos.closeArchiveEntry()
        }
        val result = SafeTarExtractor(dest).extract(bytes.inputStream())
        assertFalse(File(dest, "etc/evil").exists())
        assertEquals(1, result.skipped.size)
        assertTrue(result.skipped[0].contains("escapes"))
    }

    @Test
    fun `resolves hardlinks whose source comes later in the archive`() {
        val dest = tmp.newFolder()
        val data = "perl-binary".toByteArray()
        val bytes = tarOf { tos ->
            // hardlink entry FIRST, real source AFTER (ubuntu-base order for perl)
            val hl = TarArchiveEntry("usr/bin/perl5.38.2", TarArchiveEntry.LF_LINK)
            hl.linkName = "usr/bin/perl"
            tos.putArchiveEntry(hl); tos.closeArchiveEntry()
            val src = TarArchiveEntry("usr/bin/perl")
            src.size = data.size.toLong(); src.mode = 0b111101101
            tos.putArchiveEntry(src); tos.write(data); tos.closeArchiveEntry()
        }
        val result = SafeTarExtractor(dest).extract(bytes.inputStream())
        assertEquals(0, result.skipped.size)
        assertEquals("perl-binary", File(dest, "usr/bin/perl5.38.2").readText())
        assertEquals("perl-binary", File(dest, "usr/bin/perl").readText())
    }

    @Test
    fun `hardlink target is archive-root relative not dir relative`() {
        val dest = tmp.newFolder()
        val ex = SafeTarExtractor(dest)
        // usr/bin/perl5.38.2 -> usr/bin/perl must resolve to usr/bin/perl, never usr/bin/usr/bin/perl
        assertEquals("usr/bin/perl", ex.resolveHardlinkInsideRootfs("usr/bin/perl"))
        assertEquals("bin/gunzip", ex.resolveHardlinkInsideRootfs("./bin/gunzip"))
        assertNull(ex.resolveHardlinkInsideRootfs("../../escape"))
    }
}
