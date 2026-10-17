package com.linuxify.app

import com.linuxify.app.runtime.RootfsIntegrity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

class RootfsIntegrityTest {

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

    private fun addFile(tos: TarArchiveOutputStream, path: String, content: String) {
        val e = TarArchiveEntry(path)
        e.size = content.length.toLong()
        e.mode = 0b111101101
        tos.putArchiveEntry(e); tos.write(content.toByteArray()); tos.closeArchiveEntry()
    }

    private fun addSymlink(tos: TarArchiveOutputStream, path: String, target: String) {
        val e = TarArchiveEntry(path, TarArchiveEntry.LF_SYMLINK)
        e.linkName = target
        tos.putArchiveEntry(e); tos.closeArchiveEntry()
    }

    private fun addDir(tos: TarArchiveOutputStream, path: String) {
        tos.putArchiveEntry(TarArchiveEntry("$path/")); tos.closeArchiveEntry()
    }

    @Test
    fun `healthy tree verifies clean`() {
        val root = tmp.newFolder()
        File(root, "usr/bin").mkdirs()
        File(root, "usr/lib/aarch64-linux-gnu").mkdirs()
        Files.createSymbolicLink(File(root, "bin").toPath(), java.nio.file.Path.of("usr/bin"))
        File(root, "usr/bin/bash").writeText("elf")
        File(root, "usr/bin/uname").writeText("elf")
        File(root, "usr/bin/env").writeText("elf")
        File(root, "usr/bin/apt-get").writeText("elf")
        File(root, "usr/bin/dpkg").writeText("elf")
        File(root, "usr/bin/dash").writeText("elf")
        Files.createSymbolicLink(File(root, "bin/sh").toPath(), java.nio.file.Path.of("dash"))
        File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").writeText("loader")
        Files.createSymbolicLink(
            File(root, "usr/lib/ld-linux-aarch64.so.1").toPath(),
            java.nio.file.Path.of("aarch64-linux-gnu/ld-linux-aarch64.so.1"),
        )
        Files.createSymbolicLink(
            File(root, "lib").toPath(),
            java.nio.file.Path.of("usr/lib"),
        )
        Files.createSymbolicLink(
            File(root, "usr/bin/ld.so").toPath(),
            java.nio.file.Path.of("../lib/ld-linux-aarch64.so.1"),
        )
        File(root, "usr/lib/apt/solvers").mkdirs()
        File(root, "usr/lib/apt/solvers/dump").writeText("elf")
        File(root, "usr/lib/apt/planners").mkdirs()
        Files.createSymbolicLink(
            File(root, "usr/lib/apt/planners/dump").toPath(),
            java.nio.file.Path.of("../solvers/dump"),
        )
        File(root, "root").mkdirs()
        File(root, "tmp").mkdirs()
        File(root, "var").mkdirs()
        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\n")
        // os-release behind ../usr/lib/os-release (the shape apt needs)
        File(root, "usr/lib/os-release").writeText("ID=ubuntu\n")
        Files.createSymbolicLink(File(root, "etc/os-release").toPath(), java.nio.file.Path.of("../usr/lib/os-release"))

        assertEquals(emptyList<String>(), RootfsIntegrity.verify(root, "arm64-v8a"))
    }

    @Test
    fun `broken loader chain is detected`() {
        val root = tmp.newFolder()
        File(root, "usr/bin").mkdirs()
        File(root, "usr/lib").mkdirs()
        Files.createSymbolicLink(File(root, "bin").toPath(), java.nio.file.Path.of("usr/bin"))
        File(root, "usr/bin/bash").writeText("elf")
        // usr/lib/ld-linux-aarch64.so.1 MISSING (the alpha.2 device symptom class)

        val problems = RootfsIntegrity.verify(root, "arm64-v8a")
        assertTrue("loader must be reported missing", problems.any { it.contains("ld-linux") })
    }

    @Test
    fun `repair restores dropped entries from cached artifact`() {
        // Build a small artifact tar with the full correct tree
        val artifact = tmp.newFile("rootfs.tar.gz")
        artifact.writeBytes(tarOf { tos ->
            addDir(tos, "bin")
            addSymlink(tos, "bin", "usr/bin")
            addDir(tos, "usr")
            addDir(tos, "usr/bin")
            addDir(tos, "usr/lib")
            addDir(tos, "usr/lib/aarch64-linux-gnu")
            addFile(tos, "usr/bin/bash", "elf")
            addFile(tos, "usr/bin/uname", "elf")
            addFile(tos, "usr/bin/env", "elf")
            addFile(tos, "usr/bin/apt-get", "elf")
            addFile(tos, "usr/bin/dpkg", "elf")
            addFile(tos, "usr/bin/dash", "elf")
            addSymlink(tos, "bin/sh", "dash")
            addFile(tos, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", "loader")
            addSymlink(tos, "usr/lib/ld-linux-aarch64.so.1", "aarch64-linux-gnu/ld-linux-aarch64.so.1")
            addSymlink(tos, "bin", "usr/bin")
            addSymlink(tos, "lib", "usr/lib")
            addSymlink(tos, "usr/bin/ld.so", "../lib/ld-linux-aarch64.so.1")
            addDir(tos, "usr/lib/apt/solvers")
            addFile(tos, "usr/lib/apt/solvers/dump", "elf")
            addDir(tos, "usr/lib/apt/planners")
            addSymlink(tos, "usr/lib/apt/planners/dump", "../solvers/dump")
            addDir(tos, "root")
            addDir(tos, "tmp")
            addDir(tos, "var")
            addDir(tos, "etc")
            addFile(tos, "etc/resolv.conf", "nameserver 8.8.8.8\n")
            addFile(tos, "usr/lib/os-release", "ID=ubuntu\n")
            addSymlink(tos, "etc/os-release", "../usr/lib/os-release")
        })

        // Damage the tree: delete the loader link + os-release (alpha.2 symptom)
        val root = tmp.newFolder("rootfs")
        val extract = com.linuxify.app.runtime.SafeTarExtractor(root)
        artifact.inputStream().buffered().use { extract.extract(it) }
        assertEquals(
            "freshly extracted fixture must verify clean",
            emptyList<String>(),
            RootfsIntegrity.verify(root, "arm64-v8a"),
        )

        File(root, "usr/lib/ld-linux-aarch64.so.1").delete()
        File(root, "etc/os-release").delete()
        assertFalse(RootfsIntegrity.verify(root, "arm64-v8a").isEmpty())

        val logs = mutableListOf<String>()
        assertTrue(RootfsIntegrity.repair(root, artifact, RootfsIntegrity.verify(root, "arm64-v8a")) { logs.add(it) })
        assertEquals("after repair the tree must verify clean", emptyList<String>(), RootfsIntegrity.verify(root, "arm64-v8a"))
        assertTrue(File(root, "usr/lib/ld-linux-aarch64.so.1").isFile)
        assertTrue("os-release restored behind .. link", File(root, "etc/os-release").isFile)
        assertTrue(logs.any { it.contains("restored") })
    }

    @Test
    fun `absolute symlink targets resolve against the rootfs not the host`() {
        val root = tmp.newFolder()
        File(root, "usr/lib/systemd/system").mkdirs()
        File(root, "usr/lib/systemd/system/e2scrub_reap.service").writeText("unit")
        File(root, "etc/systemd/system/multi-user.target.wants").mkdirs()
        // absolute target: /lib/systemd/system/e2scrub_reap.service
        Files.createSymbolicLink(
            File(root, "etc/systemd/system/multi-user.target.wants/e2scrub_reap.service").toPath(),
            java.nio.file.Path.of("/lib/systemd/system/e2scrub_reap.service"),
        )
        File(root, "lib").let { Files.createSymbolicLink(it.toPath(), java.nio.file.Path.of("usr/lib")) }

        val audit = RootfsIntegrity.auditBrokenLinks(root)
        assertTrue("absolute target must resolve through rootfs-relative /lib", audit.isEmpty())
    }

    // ------------------------------------------------------------------
    // Regression tests for the v0.1.0-alpha.3 on-device exec failure:
    // proot's loader must mmap+exec guest ELFs, so a dropped +x surfaces as
    // `execve(...): Permission denied`. ensureExecutableBits is the self-heal.
    // ------------------------------------------------------------------

    @Test
    fun `ensureExecutableBits repairs non-executable ELF in bin dir`() {
        val root = tmp.newFolder()
        File(root, "usr/bin").mkdirs()
        val tool = File(root, "usr/bin/uname")
        // ELF magic so isElf() matches, then break the mode to the broken state
        tool.writeBytes(byteArrayOf(0x7f) + "ELF".toByteArray() + ByteArray(8))
        RootfsIntegrity.applyTarMode(tool, 0b110100100) // 0644

        assertFalse(tool.canExecute())
        val fixed = RootfsIntegrity.ensureExecutableBits(root, "arm64-v8a")
        assertTrue("at least the ELF in usr/bin must be fixed", fixed >= 1)
        assertTrue("ELF must be executable after the sweep", tool.canExecute())
    }

    @Test
    fun `ensureExecutableBits repairs loader behind symlink chain`() {
        val root = tmp.newFolder()
        File(root, "usr/bin").mkdirs()
        File(root, "usr/lib/aarch64-linux-gnu").mkdirs()
        Files.createSymbolicLink(File(root, "lib").toPath(), java.nio.file.Path.of("usr/lib"))
        val loader = File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        loader.writeBytes(byteArrayOf(0x7f) + "ELF".toByteArray() + ByteArray(8))
        RootfsIntegrity.applyTarMode(loader, 0b110_100_100) // 0644 — broken state
        // critical path lib/ld-linux-aarch64.so.1 resolves through the lib symlink
        Files.createSymbolicLink(
            File(root, "usr/lib/ld-linux-aarch64.so.1").toPath(),
            java.nio.file.Path.of("aarch64-linux-gnu/ld-linux-aarch64.so.1"),
        )

        val fixed = RootfsIntegrity.ensureExecutableBits(root, "arm64-v8a")
        assertTrue("loader behind the chain must be fixed", fixed >= 1)
        assertTrue(loader.canExecute())
    }

    @Test
    fun `ensureExecutableBits leaves data files and text configs alone`() {
        val root = tmp.newFolder()
        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\n")
        File(root, "etc/os-release").writeText("ID=ubuntu\n")
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/README.txt").writeText("not an elf")

        RootfsIntegrity.ensureExecutableBits(root, "arm64-v8a")
        assertFalse("resolv.conf must not gain +x", File(root, "etc/resolv.conf").canExecute())
        assertFalse("os-release must not gain +x", File(root, "etc/os-release").canExecute())
        assertFalse("non-ELF in bin must not gain +x", File(root, "usr/bin/README.txt").canExecute())
    }
}
