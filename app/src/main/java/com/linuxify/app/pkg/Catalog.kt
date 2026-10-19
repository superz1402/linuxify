package com.linuxify.app.pkg

/**
 * The software catalog — DATA layered on top of the ecosystem adapters.
 *
 * Design rule (user directive): OpenCode is just one catalog entry among many.
 * Entries are declarative; the shared core (SoftwareManager + Ecosystems) does
 * all the work. Custom user tools use the exact same CatalogEntry shape.
 */
data class CatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val specs: List<InstallSpec>,
    val verifyBinary: String,
    val verifyArg: String = "--version",
    val custom: Boolean = false,
)

object Catalog {

    val CATEGORIES: List<String> = listOf("All", "System", "Dev", "AI", "Network", "Media", "Data", "Text")

    // Checksums below were downloaded from the official release URLs over TLS and
    // cross-verified against the upstream checksum manifests (yq checksums file,
    // jq sha256sum.txt) — same trust model as the rootfs artifact pinning.
    private const val YQ_V4_44_3_SHA256 = "0e7e1524f68d91b3ff9b089872d185940ab0fa020a5a9052046ef10547023156"
    private const val JQ_V1_7_1_ARM64_SHA256 = "4dd2d8a0661df0b22f1bb9a1f9830f06b6f3b8f7d91211a1ef5d7c4f06a8b4a5"

    val ENTRIES: List<CatalogEntry> = listOf(
        // ---------------- System ----------------
        CatalogEntry(
            id = "htop", name = "htop", category = "System",
            description = "Interactive process viewer — see what the environment is running.",
            specs = listOf(InstallSpec.Apt(listOf("htop"))), verifyBinary = "htop",
        ),
        CatalogEntry(
            id = "tmux", name = "tmux", category = "System",
            description = "Terminal multiplexer — persistent sessions, split panes, detach/reattach.",
            specs = listOf(InstallSpec.Apt(listOf("tmux"))), verifyBinary = "tmux",
        ),
        CatalogEntry(
            id = "tree", name = "tree", category = "System",
            description = "Show directory trees in the terminal.",
            specs = listOf(InstallSpec.Apt(listOf("tree"))), verifyBinary = "tree",
        ),
        CatalogEntry(
            id = "rsync", name = "rsync", category = "System",
            description = "Fast, incremental file copying and syncing.",
            specs = listOf(InstallSpec.Apt(listOf("rsync"))), verifyBinary = "rsync",
        ),

        // ---------------- Dev ----------------
        CatalogEntry(
            id = "ripgrep", name = "ripgrep (rg)", category = "Dev",
            description = "Extremely fast recursive text search — a modern grep.",
            specs = listOf(InstallSpec.Apt(listOf("ripgrep"))), verifyBinary = "rg",
        ),
        CatalogEntry(
            id = "fd-find", name = "fd-find (fd)", category = "Dev",
            description = "Simple, fast alternative to find (command: fdfind on Ubuntu).",
            specs = listOf(InstallSpec.Apt(listOf("fd-find"))), verifyBinary = "fdfind",
        ),
        CatalogEntry(
            id = "typescript", name = "TypeScript", category = "Dev",
            description = "TypeScript compiler (tsc) — installed globally via npm.",
            specs = listOf(InstallSpec.Npm("typescript", "tsc")), verifyBinary = "tsc",
        ),
        CatalogEntry(
            id = "nodemon", name = "nodemon", category = "Dev",
            description = "Auto-restart Node.js apps when files change.",
            specs = listOf(InstallSpec.Npm("nodemon", "nodemon")), verifyBinary = "nodemon",
        ),
        CatalogEntry(
            id = "pnpm", name = "pnpm", category = "Dev",
            description = "Fast, disk-efficient JavaScript package manager.",
            specs = listOf(InstallSpec.Npm("pnpm", "pnpm")), verifyBinary = "pnpm",
        ),
        CatalogEntry(
            id = "yarn", name = "Yarn", category = "Dev",
            description = "Alternative JavaScript package manager.",
            specs = listOf(InstallSpec.Npm("yarn", "yarn")), verifyBinary = "yarn",
        ),
        CatalogEntry(
            id = "prettier", name = "Prettier", category = "Dev",
            description = "Opinionated code formatter for JS/TS, CSS, JSON and more.",
            specs = listOf(InstallSpec.Npm("prettier", "prettier")), verifyBinary = "prettier",
        ),
        CatalogEntry(
            id = "ruff", name = "Ruff", category = "Dev",
            description = "Extremely fast Python linter and formatter.",
            specs = listOf(InstallSpec.Pip("ruff", "ruff")), verifyBinary = "ruff",
        ),
        CatalogEntry(
            id = "black", name = "Black", category = "Dev",
            description = "The uncompromising Python code formatter.",
            specs = listOf(InstallSpec.Pip("black", "black")), verifyBinary = "black",
        ),
        CatalogEntry(
            id = "poetry", name = "Poetry", category = "Dev",
            description = "Python packaging and dependency manager.",
            specs = listOf(InstallSpec.Pip("poetry", "poetry")), verifyBinary = "poetry",
        ),

        // ---------------- AI ----------------
        CatalogEntry(
            id = "opencode", name = "OpenCode", category = "AI",
            description = "AI coding agent for the terminal (npm package opencode-ai).",
            specs = listOf(InstallSpec.Npm("opencode-ai", "opencode")), verifyBinary = "opencode",
        ),

        // ---------------- Network ----------------
        CatalogEntry(
            id = "httpie", name = "HTTPie", category = "Network",
            description = "Human-friendly HTTP client for APIs (command: http).",
            specs = listOf(InstallSpec.Pip("httpie", "http")), verifyBinary = "http",
        ),

        // ---------------- Media ----------------
        CatalogEntry(
            id = "ffmpeg", name = "FFmpeg", category = "Media",
            description = "Convert, record and stream audio and video.",
            specs = listOf(InstallSpec.Apt(listOf("ffmpeg"))), verifyBinary = "ffmpeg",
        ),
        CatalogEntry(
            id = "imagemagick", name = "ImageMagick", category = "Media",
            description = "Create, edit, compose and convert images (command: convert).",
            specs = listOf(InstallSpec.Apt(listOf("imagemagick"))), verifyBinary = "convert",
        ),
        CatalogEntry(
            id = "yt-dlp", name = "yt-dlp", category = "Media",
            description = "Download videos from many sites (install FFmpeg too for merging).",
            specs = listOf(InstallSpec.Pip("yt-dlp", "yt-dlp")), verifyBinary = "yt-dlp",
        ),

        // ---------------- Data ----------------
        CatalogEntry(
            id = "jq", name = "jq", category = "Data",
            description = "Lightweight JSON processor (APT, with a checksum-pinned static binary fallback).",
            specs = listOf(
                InstallSpec.Apt(listOf("jq")),
                InstallSpec.Binary(
                    url = "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-arm64",
                    sha256 = JQ_V1_7_1_ARM64_SHA256,
                    binaryName = "jq",
                    version = "1.7.1",
                ),
            ),
            verifyBinary = "jq",
        ),
        CatalogEntry(
            id = "yq", name = "yq", category = "Data",
            description = "YAML/XML/TOML processor (mikefarah) — checksum-pinned static binary.",
            specs = listOf(
                InstallSpec.Binary(
                    url = "https://github.com/mikefarah/yq/releases/download/v4.44.3/yq_linux_arm64",
                    sha256 = YQ_V4_44_3_SHA256,
                    binaryName = "yq",
                    version = "4.44.3",
                ),
            ),
            verifyBinary = "yq",
        ),
        CatalogEntry(
            id = "sqlite3", name = "SQLite 3", category = "Data",
            description = "Self-contained SQL database engine — command-line shell.",
            specs = listOf(InstallSpec.Apt(listOf("sqlite3"))), verifyBinary = "sqlite3",
        ),

        // ---------------- Text ----------------
        CatalogEntry(
            id = "nano", name = "nano", category = "Text",
            description = "Simple, friendly terminal text editor.",
            specs = listOf(InstallSpec.Apt(listOf("nano"))), verifyBinary = "nano",
        ),
    )

    /** Pure search: case-insensitive substring over id/name/description + category filter. */
    fun search(
        entries: List<CatalogEntry>,
        query: String?,
        category: String? = null,
    ): List<CatalogEntry> {
        var out = entries
        if (!category.isNullOrBlank() && category != "All") {
            out = out.filter { it.category == category }
        }
        val q = query?.trim()?.lowercase().orEmpty()
        if (q.isNotEmpty()) {
            out = out.filter { entry ->
                entry.id.lowercase().contains(q) ||
                    entry.name.lowercase().contains(q) ||
                    entry.description.lowercase().contains(q) ||
                    entry.category.lowercase().contains(q)
            }
        }
        return out
    }

    fun findById(entries: List<CatalogEntry>, id: String): CatalogEntry? =
        entries.firstOrNull { it.id == id }
}
