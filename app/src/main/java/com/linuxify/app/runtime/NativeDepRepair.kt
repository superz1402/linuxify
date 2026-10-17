package com.linuxify.app.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern

/**
 * Parses Android's bionic dynamic-linker failure output.
 *
 * A proot spawn that dies before `main()` produces stderr like:
 *
 * ```
 * CANNOT LINK EXECUTABLE "/data/app/.../lib/arm64/libproot.so":
 * library "libtalloc.so.2" not found: needed by main executable
 * ```
 *
 * The linker resolves `DT_NEEDED` entries by literal file name, and Android's
 * APK extraction (jniLibs) only unpacks files named `lib*.so` — so a build
 * that stages `libtalloc.so` while proot needs `libtalloc.so.2` fails exactly
 * this way on the device, even though the library is present.
 *
 * Pure Kotlin: no Android imports, unit-testable on the JVM.
 */
object LinkerDiagnostics {

    private val MISSING_LIB: Pattern = Pattern.compile("library \"([^\"]+)\" not found")
    private const val LINK_FAILURE_MARKERS = "CANNOT LINK"

    /**
     * True when the output carries bionic's dynamic-link failure signature.
     * Covers both exec-time form (`CANNOT LINK EXECUTABLE …`) and the dlopen
     * form (`dlopen failed: library "…" not found`) — the precise
     * missing-library phrase is specific enough to drive repair, and repair
     * only ever creates shims for names that resolve to packaged candidates.
     */
    fun isLinkFailure(output: String): Boolean =
        output.contains(LINK_FAILURE_MARKERS) || missingLibrary(output) != null

    /**
     * First missing library named by the linker, e.g. `libtalloc.so.2`.
     * The linker reports one failure at a time; callers retry after repair.
     */
    fun missingLibrary(output: String): String? {
        val m = MISSING_LIB.matcher(output)
        return if (m.find()) m.group(1) else null
    }
}

/**
 * Self-healing native dependency resolution for the bundled Linux runtime.
 *
 * Strategy (no network, no code download at runtime):
 *  1. The APK ships every dependency under a `lib*.so` name.
 *  2. When the linker asks for a name we do not ship literally, we look for a
 *     packaged candidate: a pinned alias, or the same soname with the version
 *     suffix stripped (`libtalloc.so.2` -> `libtalloc.so`).
 *  3. A symlink named exactly like the requested library is created in the
 *     private `syslib` directory, which is prepended to `LD_LIBRARY_PATH` for
 *     every runtime spawn. The next attempt then links successfully.
 *
 * Everything here is idempotent and side-effect-free beyond the private
 * syslib directory, so it is safe to run on every install attempt.
 */
object NativeDepRepair {

    /**
     * Pinned aliases for dependencies whose packaged name differs from the
     * upstream soname for reasons other than versioning. Mirrors the
     * `packaged_as` mapping in runtime-binaries.json.
     */
    val KNOWN_ALIASES: Map<String, String> = mapOf(
        "libandroid-shmem.so" to "libshmem.so",
    )

    private val VERSIONED_SONAME: Pattern = Pattern.compile("^(lib.+)\\.so(\\.[0-9]+)+$")

    /**
     * Locate the packaged library that can satisfy `missing`, or null.
     *
     * Resolution order: exact packaged name -> known alias -> version suffix
     * stripped (`libfoo.so.2` -> `libfoo.so`).
     */
    fun packagedCandidateFor(missing: String, libDir: File): File? {
        val exact = File(libDir, missing)
        if (exact.isFile) return exact

        KNOWN_ALIASES[missing]?.let { alias ->
            val aliased = File(libDir, alias)
            if (aliased.isFile) return aliased
        }

        val m = VERSIONED_SONAME.matcher(missing)
        if (m.matches()) {
            // group(1) always matched when matches() is true (group is unconditional)
            val base = File(libDir, m.group(1)!! + ".so")
            if (base.isFile) return base
        }
        return null
    }

    /**
     * Ensure a file named exactly [requested] exists inside [syslibDir],
     * pointing at [candidate]. Prefers a symlink; falls back to a copy when
     * the filesystem refuses links. Returns the resolved file, or null when
     * creation failed.
     *
     * Idempotent: an existing correct link is reused, a stale/corrupt one is
     * replaced.
     */
    fun createShim(requested: String, candidate: File, syslibDir: File): File? {
        return try {
            syslibDir.mkdirs()
            val target = File(syslibDir, requested)
            if (target.isFile) {
                // already satisfied (previous run or copied file)
                return target
            }
            try {
                Files.createSymbolicLink(
                    target.toPath(),
                    candidate.absoluteFile.toPath(),
                )
            } catch (_: Exception) {
                // some mount points disallow symlinks — copy is equally valid
                // for the linker (the shim is only read, never executed)
                Files.copy(
                    candidate.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            if (target.isFile) target else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Repair every missing library reported by [linkerOutput]:
     * resolve each to a packaged candidate and create its syslib shim.
     *
     * @return the shims created (possibly empty when nothing could be resolved)
     */
    fun repairFromLinkerOutput(linkerOutput: String, libDir: File, syslibDir: File): List<File> {
        if (!LinkerDiagnostics.isLinkFailure(linkerOutput)) return emptyList()
        val missing = LinkerDiagnostics.missingLibrary(linkerOutput) ?: return emptyList()
        return repairMissing(listOf(missing), libDir, syslibDir)
    }

    /** @see repairFromLinkerOutput */
    fun repairMissing(missing: List<String>, libDir: File, syslibDir: File): List<File> {
        val created = mutableListOf<File>()
        for (name in missing.distinct()) {
            val candidate = packagedCandidateFor(name, libDir) ?: continue
            createShim(name, candidate, syslibDir)?.let { created += it }
        }
        return created
    }
}
