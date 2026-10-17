package com.linuxify.app

import com.linuxify.app.runtime.RuntimeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMetadataTest {

    @Test
    fun `arm64 devices map to arm64 rootfs`() {
        val artifact = RuntimeMetadata.artifactForAbi(arrayOf("arm64-v8a", "armeabi-v7a"))
        assertNotNull(artifact)
        assertEquals("arm64-v8a", artifact!!.abi)
        assertTrue(artifact.sha256.length == 64)
    }

    @Test
    fun `x86_64 devices map to amd64 rootfs`() {
        val artifact = RuntimeMetadata.artifactForAbi(arrayOf("x86_64", "arm64-v8a"))
        assertNotNull(artifact)
        assertEquals("x86_64", artifact!!.abi)
    }

    @Test
    fun `unsupported abis return null`() {
        assertNull(RuntimeMetadata.artifactForAbi(arrayOf("mips", "armeabi-v7a")))
    }

    @Test
    fun `urls are https and point to ubuntu cdimage`() {
        RuntimeMetadata.ROOTFS_BY_ABI.values.forEach { a ->
            val url = RuntimeMetadata.urlFor(a)
            assertTrue(url.startsWith("https://cdimage.ubuntu.com/"))
            assertTrue(url.endsWith(a.fileName))
        }
    }
}
