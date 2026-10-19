package com.linuxify.app.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the pure cache-discovery rules (issue #23).
 */
class CacheTargetsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(parent: File, name: String): File {
        val d = File(parent, name)
        d.mkdirs()
        return d
    }

    private fun file(dir: File, name: String, content: String = "x"): File {
        val f = File(dir, name)
        f.writeText(content)
        return f
    }

    @Test
    fun `finds top-level and nested caches`() {
        val project = tmp.newFolder("proj")
        dir(project, "__pycache__")
        dir(dir(project, "src"), ".pytest_cache")
        dir(dir(dir(project, "web"), "node_modules"), ".cache").apply { file(this, "x", "data") }
        dir(project, ".git").apply { dir(this, "objects") }

        val found = CacheTargets.findProjectCaches(project).map { it.relativeTo(project).path }

        assertTrue(found.contains("__pycache__"))
        assertTrue(found.contains("src/.pytest_cache"))
        assertTrue(found.contains("web/node_modules/.cache"))
        // .git itself and anything inside it are never candidates
        assertTrue(found.none { it.startsWith(".git") })
        assertEquals(3, found.size)
    }

    @Test
    fun `skips build output and dependency trees as a whole`() {
        val project = tmp.newFolder("proj2")
        dir(project, "build")
        dir(project, "dist")
        dir(project, "target")
        dir(project, ".venv")
        dir(project, "vendor")

        val found = CacheTargets.findProjectCaches(project)
        assertTrue(found.isEmpty())
    }

    @Test
    fun `matched cache dir is not descended into`() {
        val project = tmp.newFolder("proj3")
        // a .cache dir that itself contains a ".cache" subdir — must report ONE hit
        val outer = dir(project, ".cache")
        dir(outer, ".cache")

        val found = CacheTargets.findProjectCaches(project)
        assertEquals(listOf(".cache"), found.map { it.relativeTo(project).path })
    }

    @Test
    fun `next cache nested path is matched`() {
        val project = tmp.newFolder("proj4")
        dir(dir(project, ".next"), "cache")

        val found = CacheTargets.findProjectCaches(project).map { it.relativeTo(project).path }
        assertEquals(listOf(".next/cache"), found)
    }

    @Test
    fun `symlinked cache is never reported`() {
        val project = tmp.newFolder("proj5")
        val outside = tmp.newFolder("outside")
        file(outside, "data.txt", "big")
        val link = File(project, ".cache")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: Exception) {
            return // filesystem refuses symlinks — nothing to assert
        }

        val found = CacheTargets.findProjectCaches(project)
        assertTrue(found.none { it.name == ".cache" })
    }

    @Test
    fun `sizeOfDirSafe counts regular files only`() {
        val root = tmp.newFolder("size")
        file(root, "a.txt", "12345") // 5 bytes
        dir(root, "sub").apply { file(this, "b.bin", "12345678") } // 8 bytes
        val size = CacheTargets.sizeOfDirSafe(root)
        assertEquals(13L, size)
    }

    @Test
    fun `deep nesting beyond depth cap is not matched`() {
        val project = tmp.newFolder("proj6")
        var cur = project
        repeat(CacheTargets.MAX_WALK_DEPTH + 1) { cur = dir(cur, "l$it") }
        dir(cur, "__pycache__")

        val found = CacheTargets.findProjectCaches(project)
        assertTrue(found.isEmpty())
    }
}
