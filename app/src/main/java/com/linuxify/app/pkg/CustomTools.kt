package com.linuxify.app.pkg

import org.json.JSONArray
import org.json.JSONObject

/**
 * User-defined catalog entries ("custom tools"). Pure JSON logic — file IO
 * lives in SoftwareManager so this object stays JVM-testable.
 *
 * Supported ecosystems for custom tools in v1: apt, npm, pip. Custom static
 * binaries require a URL + sha256 and are intentionally NOT exposed in v1
 * (a mistyped checksum is a silent brick risk; the terminal already covers it).
 */
object CustomTools {

    val SUPPORTED_ECOSYSTEMS: List<String> = listOf(Ecosystems.APT, Ecosystems.NPM, Ecosystems.PIP)

    private val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    /** npm scoped packages (@scope/name) — issue #20: a leading '@' is legal for npm only. */
    private val NPM_SCOPE_RE = Regex("^@[A-Za-z0-9][A-Za-z0-9.@/_+-]{0,126}$")

    /** Is [pkg] a package name the given ecosystem accepts (injection-safe by charset)? */
    fun isAllowedPackage(ecosystem: String, pkg: String): Boolean = when (ecosystem) {
        Ecosystems.NPM -> Ecosystems.isSafeToken(pkg) || NPM_SCOPE_RE.matches(pkg)
        else -> Ecosystems.isSafeToken(pkg)
    }

    /**
     * Validates and builds a custom entry. Returns null with a human-readable
     * reason in [error] when invalid — the UI surfaces it, never a crash.
     */
    fun buildEntry(
        rawName: String,
        ecosystem: String,
        rawPkg: String,
        rawBinary: String?,
        rawDescription: String?,
        error: (String) -> Unit,
    ): CatalogEntry? {
        val name = rawName.trim()
        val pkg = rawPkg.trim()
        if (name.isEmpty() || !ID_RE.matches(name.lowercase().replace(' ', '-'))) {
            error("Name must be 1-64 characters: letters, numbers, dots, dashes.")
            return null
        }
        val id = name.lowercase().replace(' ', '-')
        if (pkg.isEmpty() || !isAllowedPackage(ecosystem, pkg)) {
            error(
                if (ecosystem == Ecosystems.NPM) {
                    "Package name must be a valid npm name (scoped names like @scope/pkg are allowed)."
                } else {
                    "Package name contains characters APT/npm/pip would reject."
                },
            )
            return null
        }
        val binary = rawBinary?.trim()?.takeIf { it.isNotEmpty() }
        if (binary != null && !Ecosystems.isSafeToken(binary)) {
            error("Binary name contains characters that are not valid for an executable name.")
            return null
        }
        val description = rawDescription?.trim().orEmpty()
            .ifEmpty { "Custom ${ecoLabel(ecosystem)} tool." }
        val spec: InstallSpec = when (ecosystem) {
            Ecosystems.APT -> InstallSpec.Apt(listOf(pkg))
            Ecosystems.NPM -> InstallSpec.Npm(pkg, binary ?: pkg)
            Ecosystems.PIP -> InstallSpec.Pip(pkg, binary ?: pkg)
            else -> {
                error("Ecosystem must be one of: apt, npm, pip.")
                return null
            }
        }
        return CatalogEntry(
            id = id,
            name = name,
            description = description,
            category = "Custom",
            specs = listOf(spec),
            verifyBinary = binary ?: pkg,
            custom = true,
        )
    }

    private fun ecoLabel(ecosystem: String): String = when (ecosystem) {
        Ecosystems.APT -> "APT"
        Ecosystems.NPM -> "npm"
        Ecosystems.PIP -> "pip"
        else -> ecosystem
    }

    fun toJson(entries: List<CatalogEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            val spec = e.specs.firstOrNull() ?: return@forEach
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("description", e.description)
                    .put("ecosystem", Ecosystems.idOf(spec))
                    .put("pkg", pkgOf(spec))
                    .putOpt("binary", binaryOf(spec))
                    .put("verifyBinary", e.verifyBinary)
                    .put("verifyArg", e.verifyArg),
            )
        }
        return arr.toString(2)
    }

    fun fromJson(text: String): List<CatalogEntry> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val eco = o.optString("ecosystem")
            val pkg = o.optString("pkg")
            if (pkg.isEmpty()) return@mapNotNull null
            val binary = o.optString("binary").takeIf { it.isNotEmpty() }
            val verifyBinary = o.optString("verifyBinary").ifEmpty { binary ?: pkg }
            // Issue #14 — re-validate EVERYTHING read back from disk. The file
            // lives in app-private storage, but a corrupt or hand-edited file
            // must never feed unvalidated strings into guest shell commands.
            // Entries failing validation are skipped, not trusted.
            if (eco !in SUPPORTED_ECOSYSTEMS) return@mapNotNull null
            if (!isAllowedPackage(eco, pkg)) return@mapNotNull null
            if (binary != null && !Ecosystems.isSafeToken(binary)) return@mapNotNull null
            if (verifyBinary.isEmpty() || !isAllowedPackage(eco, verifyBinary)) return@mapNotNull null
            val spec: InstallSpec = when (eco) {
                Ecosystems.APT -> InstallSpec.Apt(listOf(pkg))
                Ecosystems.NPM -> InstallSpec.Npm(pkg, binary ?: pkg)
                Ecosystems.PIP -> InstallSpec.Pip(pkg, binary ?: pkg)
                else -> return@mapNotNull null
            }
            CatalogEntry(
                id = o.optString("id").ifEmpty { pkg },
                name = o.optString("name").ifEmpty { pkg },
                description = o.optString("description").ifEmpty { "Custom tool." },
                category = "Custom",
                specs = listOf(spec),
                verifyBinary = verifyBinary,
                verifyArg = o.optString("verifyArg").ifEmpty { "--version" },
                custom = true,
            )
        }
    }.getOrDefault(emptyList())

    /** Built-in first, custom appended; a custom entry never shadows a built-in id. */
    fun merge(builtIn: List<CatalogEntry>, custom: List<CatalogEntry>): List<CatalogEntry> {
        val builtInIds = builtIn.mapTo(HashSet()) { it.id }
        val seen = HashSet<String>()
        return builtIn + custom.filter { it.id !in builtInIds && seen.add(it.id) }
    }

    private fun pkgOf(spec: InstallSpec): String = when (spec) {
        is InstallSpec.Apt -> spec.packages.firstOrNull() ?: ""
        is InstallSpec.Npm -> spec.pkg
        is InstallSpec.Pip -> spec.pkg
        is InstallSpec.Binary -> spec.binaryName
    }

    private fun binaryOf(spec: InstallSpec): String? = when (spec) {
        is InstallSpec.Npm -> spec.binary
        is InstallSpec.Pip -> spec.binary
        else -> null
    }
}
