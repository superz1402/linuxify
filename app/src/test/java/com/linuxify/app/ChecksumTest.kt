package com.linuxify.app

import com.linuxify.app.runtime.Checksum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ChecksumTest {

    @Test
    fun `sha256 of known vector`() {
        // echo -n "abc" | sha256sum
        val digest = Checksum.sha256(ByteArrayInputStream("abc".toByteArray()))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest,
        )
    }

    @Test
    fun `matches is case-insensitive`() {
        assertTrue(Checksum.matches("ABC123", "abc123"))
        assertFalse(Checksum.matches("ABC123", "abc124"))
    }

    @Test
    fun `empty stream digest`() {
        val digest = Checksum.sha256(ByteArrayInputStream(ByteArray(0)))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digest,
        )
    }
}
