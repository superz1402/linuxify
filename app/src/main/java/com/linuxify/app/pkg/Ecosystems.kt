package com.linuxify.app.pkg

/**
 * Installation ecosystems — the adapter layer of the Universal Software
 * Management System (user directive, 2026-10).
 *
 * The catalog is DATA on top of this; the terminal remains unrestricted for
 * anything the catalog does not know. Each [InstallSpec] describes one way to
 * install a tool; a catalog entry lists specs in preference order and the
 * runtime picks the first one that works on the current audit.
 */
sealed interface InstallSpec {
    data class Apt(val packages: List<String>) : InstallSpec

    /** npm global install. Prerequisite: Node.js + npm (EnvManager DEVELOPER profile). */
    data class Npm(val pkg: String, val binary: String) : InstallSpec

    /**
     * pip install. Prerequisite: python3-pip (Ubuntu base has python3, not pip).
     * Ubuntu 24.04 enforces PEP 668 ("externally-managed-environment") — CLI-tool
     * installs in this proot userland use --break-system-packages, the standard
     * pragmatic choice for container-like environments.
     */
    data class Pip(val pkg: String, val binary: String) : InstallSpec

    /**
     * Static single-file binary, checksum-pinned (raw download — archives are
     * rejected by design in v1 so extraction bugs cannot happen).
     */
    data class Binary(
        val url: String,
        val sha256: String,
        val binaryName: String,
        val version: String,
        val archs: List<String> = listOf("arm64"),
    ) : InstallSpec
}

object Ecosystems {
    const val APT = "apt"
    const val NPM = "npm"
    const val PIP = "pip"
    const val BINARY = "binary"

    /**
     * Charset a package/binary identifier may contain — no shell
     * metacharacters, no whitespace, no leading '@' (that is an npm-scope
     * privilege, see [CustomTools.isAllowedPackage]). Every string that ever
     * reaches a shell command line must pass this (issue #14).
     */
    val SAFE_TOKEN_RE: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9.@/_+-]{0,127}$")

    fun isSafeToken(token: String): Boolean = SAFE_TOKEN_RE.matches(token)

    fun labelOf(spec: InstallSpec): String = when (spec) {
        is InstallSpec.Apt -> "APT"
        is InstallSpec.Npm -> "npm"
        is InstallSpec.Pip -> "pip"
        is InstallSpec.Binary -> "binary"
    }

    fun idOf(spec: InstallSpec): String = when (spec) {
        is InstallSpec.Apt -> APT
        is InstallSpec.Npm -> NPM
        is InstallSpec.Pip -> PIP
        is InstallSpec.Binary -> BINARY
    }

    // ------------------------------------------------------- availability

    fun npmAvailable(audit: EnvCore.EnvAudit?): Boolean = audit != null && "npm" in audit.installedBinaries

    fun pipAvailable(audit: EnvCore.EnvAudit?): Boolean = audit != null && "pip3" in audit.installedBinaries

    /** True when the spec can be used RIGHT NOW with no prerequisites installed. */
    fun usableNow(spec: InstallSpec, audit: EnvCore.EnvAudit?): Boolean = when (spec) {
        is InstallSpec.Apt -> audit != null
        is InstallSpec.Npm -> npmAvailable(audit)
        is InstallSpec.Pip -> pipAvailable(audit)
        is InstallSpec.Binary ->
            audit != null && audit.arch != null && audit.arch in spec.archs
    }

    /**
     * Prerequisites the manager can auto-provision (never a silent guess —
     * each maps to an explicit, logged step in SoftwareManager).
     */
    fun autoPrerequisitePackages(spec: InstallSpec): List<String> = when (spec) {
        is InstallSpec.Pip -> listOf("python3-pip")
        else -> emptyList()
    }

    /** npm needs the whole Node.js toolchain — handled via the DEVELOPER profile. */
    fun needsDeveloperProfile(spec: InstallSpec): Boolean = spec is InstallSpec.Npm

    /**
     * Resolution rule: the FIRST spec usable right now wins; if none is usable,
     * the first spec whose prerequisites can be auto-provisioned wins; else
     * null (unsupported architecture / no viable path — reported, never guessed).
     */
    fun resolveSpec(specs: List<InstallSpec>, audit: EnvCore.EnvAudit?): InstallSpec? =
        installCandidates(specs, audit).firstOrNull()

    /**
     * All specs worth attempting, in preference order (issue #17): usable-now
     * first, then specs whose prerequisites the manager can auto-provision.
     * [resolveSpec] is only the first candidate — the manager walks the WHOLE
     * list so a later checksum-pinned fallback can still succeed when the
     * primary path fails (e.g. jq's static binary after a broken APT attempt).
     */
    fun installCandidates(specs: List<InstallSpec>, audit: EnvCore.EnvAudit?): List<InstallSpec> =
        specs.filter { usableNow(it, audit) || autoProvisionable(it, audit) }

    private fun autoProvisionable(spec: InstallSpec, audit: EnvCore.EnvAudit?): Boolean = when (spec) {
        is InstallSpec.Apt -> false
        is InstallSpec.Npm -> audit != null
        is InstallSpec.Pip -> audit != null
        is InstallSpec.Binary -> audit != null && audit.arch != null && audit.arch in spec.archs
    }

    // ---------------------------------------------------- command builders

    fun npmInstallCommand(pkg: String): String = "npm install -g $pkg"

    fun npmUninstallCommand(pkg: String): String = "npm uninstall -g $pkg"

    fun pipInstallCommand(pkg: String): String = "pip3 install --break-system-packages $pkg"

    fun pipUninstallCommand(pkg: String): String = "pip3 uninstall -y $pkg"

    fun aptInstallCommand(pkgs: List<String>): String = EnvCore.installCommand(pkgs)

    fun aptRemoveCommand(pkgs: List<String>): String =
        "DEBIAN_FRONTEND=noninteractive apt-get remove -y ${pkgs.joinToString(" ")}"

    // ------------------------------------------------- installed-state probe

    /** Combined one-spawn probe: `b:<binary>=yes|no` lines for catalog state. */
    fun installedProbeScript(binaries: Collection<String>): String =
        binaries
            .filter { it.isNotBlank() && isSafeToken(it) }
            .distinct()
            .joinToString("\n") { b ->
                "if command -v $b >/dev/null 2>&1; then echo \"b:$b=yes\"; else echo \"b:$b=no\"; fi"
            }

    fun parseInstalledProbe(lines: List<String>): Map<String, Boolean> = buildMap {
        lines.forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("b:")) return@forEach
            val body = line.removePrefix("b:")
            val name = body.substringBefore("=").trim()
            if (name.isNotEmpty()) put(name, body.substringAfter("=", "") == "yes")
        }
    }
}
