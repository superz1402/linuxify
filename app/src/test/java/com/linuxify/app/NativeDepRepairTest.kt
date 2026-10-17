package com.linuxify.app

import com.linuxify.app.runtime.LinkerDiagnostics
import com.linuxify.app.runtime.NativeDepRepair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeDepRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The exact device-reported failure from the v0.1.0-alpha.1 on-device drill. */
    private val realDeviceError = """
        CANNOT LINK EXECUTABLE "/data/app/gtoN_uGL486rCO58ToSC-A==/com.linuxify.app-q6b2rFnemlBeCPCXm4Xuhg==/lib/arm64/libproot.so":
        library "libtalloc.so.2" not found: needed by main executable
    """.trimIndent()

    @Test
    fun `parses the real device linker failure`() {
        assertTrue(LinkerDiagnostics.isLinkFailure(realDeviceError))
        assertEquals("libtalloc.so.2", LinkerDiagnostics.missingLibrary(realDeviceError))
    }

    @Test
    fun `parses dlopen-style linker failure`() {
        val err = "dlopen failed: library \"libfoo.so.1\" not found"
        assertTrue(LinkerDiagnostics.isLinkFailure(err))
        assertEquals("libfoo.so.1", LinkerDiagnostics.missingLibrary(err))
    }

    @Test
    fun `normal output is not a link failure`() {
        val out = "proot v5.3.0 + errno/ptrace\n"
        assertFalse(LinkerDiagnostics.isLinkFailure(out))
        assertNull(LinkerDiagnostics.missingLibrary(out))
    }

    @Test
    fun `versioned soname maps to packaged base name`() {
        val libDir = tmp.newFolder("libs")
        File(libDir, "libtalloc.so").writeBytes(ByteArray(1))
        val candidate = NativeDepRepair.packagedCandidateFor("libtalloc.so.2", libDir)
        assertNotNull(candidate)
        assertEquals("libtalloc.so", candidate!!.name)
    }

    @Test
    fun `pinned alias resolves libandroid-shmem`() {
        val libDir = tmp.newFolder("libs")
        File(libDir, "libshmem.so").writeBytes(ByteArray(1))
        val candidate = NativeDepRepair.packagedCandidateFor("libandroid-shmem.so", libDir)
        assertNotNull(candidate)
        assertEquals("libshmem.so", candidate!!.name)
    }

    @Test
    fun `exact packaged name wins`() {
        val libDir = tmp.newFolder("libs")
        File(libDir, "libtalloc.so.2").writeBytes(ByteArray(1))
        val candidate = NativeDepRepair.packagedCandidateFor("libtalloc.so.2", libDir)
        assertNotNull(candidate)
        assertEquals("libtalloc.so.2", candidate!!.name)
    }

    @Test
    fun `unknown library has no candidate`() {
        val libDir = tmp.newFolder("libs")
        assertNull(NativeDepRepair.packagedCandidateFor("libdoesnotexist.so.7", libDir))
    }

    @Test
    fun `repair creates a shim named exactly like the requested soname`() {
        val libDir = tmp.newFolder("libs")
        val syslib = tmp.newFolder("syslib")
        File(libDir, "libtalloc.so").writeBytes(byteArrayOf(1, 2, 3))

        val created = NativeDepRepair.repairFromLinkerOutput(realDeviceError, libDir, syslib)
        assertEquals(1, created.size)
        val shim = File(syslib, "libtalloc.so.2")
        assertTrue(shim.isFile)
        assertEquals("libtalloc.so", shim.canonicalFile.name)
    }

    @Test
    fun `repair is idempotent and overwrites stale shims`() {
        val libDir = tmp.newFolder("libs")
        val syslib = tmp.newFolder("syslib")
        File(libDir, "libtalloc.so").writeBytes(byteArrayOf(9))

        val first = NativeDepRepair.repairFromLinkerOutput(realDeviceError, libDir, syslib)
        assertEquals(1, first.size)
        // second run: no new shims needed, existing one stays valid
        val second = NativeDepRepair.repairFromLinkerOutput(realDeviceError, libDir, syslib)
        assertEquals(1, second.size)
        assertTrue(File(syslib, "libtalloc.so.2").isFile)
    }

    @Test
    fun `repair returns empty when output is not a link failure`() {
        val libDir = tmp.newFolder("libs")
        val syslib = tmp.newFolder("syslib")
        File(libDir, "libtalloc.so").writeBytes(ByteArray(1))
        val created = NativeDepRepair.repairFromLinkerOutput("segmentation fault", libDir, syslib)
        assertTrue(created.isEmpty())
    }

    @Test
    fun `repair returns empty when no candidate exists (honest failure)`() {
        val libDir = tmp.newFolder("libs")
        val syslib = tmp.newFolder("syslib")
        val err = "CANNOT LINK EXECUTABLE \"x\": library \"libnope.so.9\" not found: needed by main executable"
        val created = NativeDepRepair.repairFromLinkerOutput(err, libDir, syslib)
        assertTrue(created.isEmpty())
        assertFalse(File(syslib, "libnope.so.9").exists())
    }
}
