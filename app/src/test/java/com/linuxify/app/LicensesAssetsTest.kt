package com.linuxify.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Licence-compliance guards (issue #21): the bundled notices must match the
 * repository copy, and every licence text referenced by the in-app notices
 * dialog must actually ship in the APK assets.
 *
 * Gradle runs unit tests with the working directory set to the app module,
 * so repo-root paths resolve as ".." and module paths as "src/main/...".
 */
class LicensesAssetsTest {

    private val moduleDir: File = File(System.getProperty("user.dir")!!)
    private val repoDir: File = moduleDir.parentFile!!

    private fun assetLicencesDir() = File(moduleDir, "src/main/assets/licenses")

    @Test
    fun `bundled notices exist and describe the real obligations`() {
        val notices = File(assetLicencesDir(), "THIRD_PARTY_NOTICES.md")
        assertTrue("THIRD_PARTY_NOTICES.md must be bundled in assets/licenses", notices.isFile)
        val text = notices.readText()
        assertTrue(text.contains("Corresponding-source recipe"))
        assertTrue(text.contains("GPL-2.0-or-later"))
        assertTrue(text.contains("LGPL-3.0"))
        assertTrue(text.contains("BSD-3-Clause"))
        assertTrue(text.contains("termux/proot"))
        // The notice must state that it alone does not satisfy everything.
        assertTrue(text.contains("does not satisfy every licence condition"))
    }

    @Test
    fun `bundled notices are identical to the repository copy`() {
        val bundled = File(assetLicencesDir(), "THIRD_PARTY_NOTICES.md").readText()
        val repoCopy = File(repoDir, "docs/THIRD_PARTY_NOTICES.md").readText()
        assertEquals("assets and docs notices must not drift apart", repoCopy, bundled)
    }

    @Test
    fun `every referenced licence text ships in the APK assets`() {
        val dir = assetLicencesDir()
        val expectations = mapOf(
            "GPL-3.0.txt" to "Version 3, 29 June 2007",
            "GPL-2.0.txt" to "Version 2, June 1991",
            "LGPL-3.0.txt" to "GNU LESSER GENERAL PUBLIC LICENSE",
            "Apache-2.0.txt" to "Apache License",
            "BSD-3-Clause.txt" to "Redistribution and use in source and binary forms",
        )
        expectations.forEach { (name, signature) ->
            val f = File(dir, name)
            assertTrue("$name must exist in assets/licenses", f.isFile)
            assertTrue("$name must contain its licence header", f.readText().contains(signature))
        }
    }

    @Test
    fun `repository root carries the application licence text`() {
        val license = File(repoDir, "LICENSE")
        assertTrue("root LICENSE file must exist", license.isFile)
        val text = license.readText()
        assertTrue(text.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(text.contains("Version 3, 29 June 2007"))
    }

    @Test
    fun `runtime provenance still pins every shipped binary`() {
        val json = File(repoDir, "runtime-binaries.json").readText()
        listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libshmem.so", "libtermux.so")
            .forEach { name -> assertTrue("runtime-binaries.json must mention $name", json.contains(name)) }
        assertTrue("provenance must identify the Termux apt repository", json.contains("packages.termux.dev"))
        assertTrue("rootfs provenance must identify Ubuntu cdimage", json.contains("cdimage.ubuntu.com"))
    }
}
