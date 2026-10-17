package com.linuxify.app.runtime

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Typed installer errors — no arbitrary strings crossing layer boundaries. */
sealed class RuntimeError(val technical: String) : Exception(technical) {
    data class UnsupportedArch(val abi: String) : RuntimeError("unsupported ABI: $abi")
    data class InsufficientStorage(val available: Long, val required: Long) :
        RuntimeError("need $required bytes, have $available")
    data class Network(val why: String) : RuntimeError("network: $why")
    data class Checksum(val expected: String, val actual: String) :
        RuntimeError("checksum mismatch: expected=$expected actual=$actual")
    data class Extraction(val why: String) : RuntimeError("extraction: $why")
    data class Verification(val why: String) : RuntimeError("post-install verification failed: $why")
    data class ProotMissing(val detail: String) : RuntimeError("proot unavailable: $detail")

    /** A native library the runtime needs could not be resolved or self-healed. */
    data class NativeDep(val missing: String, val detail: String) :
        RuntimeError("native dependency unresolvable: $missing: $detail")

    data object Cancelled : RuntimeError("cancelled")
    data class Io(val why: String) : RuntimeError("io: $why")
}

object Checksum {

    fun sha256(file: File): String =
        file.inputStream().buffered().use { sha256(it, file.length()) }

    /** Streaming SHA-256 with optional progress callback (bytes read so far). */
    fun sha256(stream: InputStream, sizeHint: Long = -1, onProgress: ((Long) -> Unit)? = null): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        var read = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            read += n
            onProgress?.invoke(read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(expected: String, actual: String): Boolean =
        expected.equals(actual, ignoreCase = true)
}
