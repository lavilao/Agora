package com.newoether.agora.sandbox

import android.os.Build
import java.io.File

/**
 * Architecture profile for the on-device Alpine sandbox.
 *
 * The rootfs architecture MUST match the ABI of the running app process: proot asks
 * the kernel to exec binaries from the rootfs, and on a 32-bit ROM/kernel an
 * aarch64 (64-bit) rootfs fails with ENOEXEC for every command. The process ABI is
 * read from `os.arch`, which Android reports as "aarch64" for a 64-bit process and
 * "armv7l" / "armv8l" for a 32-bit ARM process (the latter on ARMv8 hardware
 * running a 32-bit ROM). Build.SUPPORTED_ABIS is only a fallback because it
 * describes the device, not necessarily this process.
 */
object AlpineArchitecture {

    /** Alpine architecture matching this process ("aarch64", "armv7", "x86_64", "x86"). */
    val alpineArch: String = resolveAlpineArch()

    /** Pinned minirootfs download URL for [alpineArch]. */
    val rootfsUrl: String get() = profile(alpineArch).url

    /** Pinned SHA-256 the downloaded minirootfs must match before extraction. */
    val rootfsSha256: String get() = profile(alpineArch).sha256

    /** Rootfs-relative musl dynamic-linker paths to probe for [alpineArch]. */
    val muslLinkerPaths: List<String> get() = profile(alpineArch).linkerPaths

    /**
     * True when [rootfsDir] contains a musl linker belonging to any OTHER supported
     * architecture — i.e. a rootfs extracted for the wrong device, which cannot exec.
     */
    fun hasForeignMuslLinker(rootfsDir: File): Boolean {
        val own = muslLinkerPaths.toSet()
        return ROOTFS.values.asSequence()
            .flatMap { it.linkerPaths.asSequence() }
            .filterNot { it in own }
            .any { File(rootfsDir, it).exists() }
    }

    private fun profile(arch: String): RootfsProfile =
        requireNotNull(ROOTFS[arch]) { "Unsupported Alpine arch: $arch" }

    private class RootfsProfile(
        val url: String,
        val sha256: String,
        val linkerPaths: List<String>,
    )

    // Pinned Alpine v3.21.0 minirootfs per architecture; SHA-256 digests from
    // dl-cdn.alpinelinux.org. Keep the version in sync with ProotSandboxManager's
    // alpineMirror (v3.21 branch).
    private val ROOTFS: Map<String, RootfsProfile> = mapOf(
        "aarch64" to RootfsProfile(
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz",
            sha256 = "f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1",
            linkerPaths = listOf("lib/ld-musl-aarch64.so.1", "usr/lib/ld-musl-aarch64.so.1"),
        ),
        "armv7" to RootfsProfile(
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/armv7/alpine-minirootfs-3.21.0-armv7.tar.gz",
            sha256 = "9b70427fd8d119c1d6064f63f9be2b92413663145312c00b711bb5edac1eee09",
            // Alpine's armv7 port keeps the hard-float musl linker name from armhf.
            linkerPaths = listOf("lib/ld-musl-armhf.so.1", "usr/lib/ld-musl-armhf.so.1"),
        ),
        "x86_64" to RootfsProfile(
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-minirootfs-3.21.0-x86_64.tar.gz",
            sha256 = "55ea3e5a7c2c35e6268c5dcbb8e45a9cd5b0e372e7b4e798499a526834f7ed90",
            linkerPaths = listOf("lib/ld-musl-x86_64.so.1", "usr/lib/ld-musl-x86_64.so.1"),
        ),
        "x86" to RootfsProfile(
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86/alpine-minirootfs-3.21.0-x86.tar.gz",
            sha256 = "51bf4165cc71e099d4ee5202df4da67d9c6c1e0e717d720c6f449f1e68cb4cf0",
            linkerPaths = listOf("lib/ld-musl-i386.so.1", "usr/lib/ld-musl-i386.so.1"),
        ),
    )

    private fun resolveAlpineArch(): String {
        val osArch = System.getProperty("os.arch") ?: ""
        return when {
            osArch.equals("aarch64", ignoreCase = true) || osArch.startsWith("arm64") -> "aarch64"
            osArch.startsWith("arm") -> "armv7" // armv7l / armv8l: 32-bit ARM process ABI
            osArch.equals("x86_64", ignoreCase = true) || osArch.equals("amd64", ignoreCase = true) -> "x86_64"
            osArch.equals("i686", ignoreCase = true) || osArch.equals("i386", ignoreCase = true) ||
                osArch.equals("x86", ignoreCase = true) -> "x86"
            else -> when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "arm64-v8a" -> "aarch64"
                "armeabi-v7a", "armeabi" -> "armv7"
                "x86_64" -> "x86_64"
                "x86" -> "x86"
                else -> "aarch64"
            }
        }
    }
}
