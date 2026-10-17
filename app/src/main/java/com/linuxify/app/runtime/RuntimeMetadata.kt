package com.linuxify.app.runtime

/**
 * Pinned, verifiable runtime artifacts.
 *
 * Every artifact is downloaded over HTTPS and its SHA-256 must match the pinned
 * digest below. Digests were recorded from the upstream published SHA256SUMS file
 * (see runtime-binaries.json in the repository for full provenance).
 *
 * NEVER relax a checksum failure into a success. Update the pinned value only by
 * re-deriving it from the upstream SHA256SUMS and recording the change in the repo.
 */
object RuntimeMetadata {

    const val DISTRO_NAME = "Ubuntu"
    const val DISTRO_VERSION = "24.04.5 LTS (noble)"
    const val DISTRO_ID = "ubuntu"

    const val BASE_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
    const val ROOTFS_VERSION_TAG = "24.04.5"

    /** Approximate compressed download size, shown before download starts. */
    const val ROOTFS_APPROX_DOWNLOAD_BYTES: Long = 35L * 1024 * 1024

    /** Approximate extracted size. */
    const val ROOTFS_APPROX_EXTRACTED_BYTES: Long = 110L * 1024 * 1024

    data class RootfsArtifact(
        val abi: String,
        val fileName: String,
        val sha256: String,
    )

    /** Rootfs per device ABI. Unsupported ABIs must not start an install. */
    val ROOTFS_BY_ABI: Map<String, RootfsArtifact> = mapOf(
        "arm64-v8a" to RootfsArtifact(
            abi = "arm64-v8a",
            fileName = "ubuntu-base-24.04.5-base-arm64.tar.gz",
            sha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
        ),
        "x86_64" to RootfsArtifact(
            abi = "x86_64",
            fileName = "ubuntu-base-24.04.5-base-amd64.tar.gz",
            sha256 = "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86",
        ),
    )

    fun artifactForAbi(abis: Array<String>): RootfsArtifact? {
        for (abi in abis) {
            ROOTFS_BY_ABI[abi]?.let { return it }
        }
        return null
    }

    fun urlFor(artifact: RootfsArtifact): String = BASE_URL + artifact.fileName
}
