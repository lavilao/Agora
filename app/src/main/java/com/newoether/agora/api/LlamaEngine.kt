package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import java.io.File

object LlamaEngine {
    private const val TAG = "LlamaEngine"

    private var nativeHandle: Long = 0L

    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("agora_llama")
    }

    private external fun nativeInitializeBackends(nativeLibraryDir: String): Boolean
    private external fun nativeListBackendDevices(): Array<String>?
    private external fun nativeVulkanInstanceVersion(): Int
    private external fun nativeLoadModel(path: String): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeComputeEmbedding(handle: Long, text: String): FloatArray?
    private external fun nativeGetEmbeddingDim(handle: Long): Int

    internal fun initializeBackends(nativeLibraryDir: String): Boolean =
        nativeInitializeBackends(nativeLibraryDir)

    /** One backend device as reported by ggml after backends were loaded. */
    data class BackendDevice(
        val type: String,
        val name: String,
        val description: String,
    ) {
        val isGpu: Boolean get() = type == "gpu"
    }

    /** Packed Vulkan 1.2 version, the minimum the ggml Vulkan backend accepts. */
    const val VULKAN_MIN_REQUIRED: Int = 0x00402000

    /**
     * Instance version exposed by the system Vulkan loader, or null when there
     * is no usable loader. Used to explain a greyed-out Vulkan runtime option:
     * llama.cpp refuses to register GPU devices below [VULKAN_MIN_REQUIRED].
     */
    fun vulkanInstanceVersion(): Int? = try {
        nativeVulkanInstanceVersion().takeIf { it > 0 }
    } catch (_: UnsatisfiedLinkError) {
        null
    }

    /** "major.minor" rendering of a packed Vulkan version for status messages. */
    fun formatVulkanVersion(version: Int): String =
        "${version shr 22}.${(version shr 12) and 0x3ff}"

    /**
     * Enumerates the runtime backends present in this APK + device, e.g.
     * "gpu|Vulkan0|PowerVR GE8320" and "cpu|CPU|CPU". Returns an empty list when
     * backends have not been initialized yet or enumeration is unsupported.
     */
    fun listBackendDevices(): List<BackendDevice> = try {
        nativeListBackendDevices().orEmpty().mapNotNull { entry ->
            val parts = entry.split("|", limit = 3)
            if (parts.size == 3) {
                BackendDevice(type = parts[0], name = parts[1], description = parts[2])
            } else {
                null
            }
        }
    } catch (_: UnsatisfiedLinkError) {
        emptyList()
    }

    fun isModelReady(modelPath: String): Boolean {
        return modelPath.isNotBlank() && File(modelPath).exists() && File(modelPath).length() > 0
    }

    suspend fun computeEmbedding(text: String, modelPath: String): FloatArray? {
        val results = computeEmbeddings(listOf(text), modelPath)
        return results.firstOrNull()
    }

    suspend fun computeEmbeddings(texts: List<String>, modelPath: String): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()
        val start = System.currentTimeMillis()
        return LocalModelRuntime.runEmbedding(modelPath) {
            texts.mapIndexed { i, text ->
                try {
                    val embd = nativeComputeEmbedding(nativeHandle, text)
                    if (embd == null) {
                        DebugLog.e(TAG, "nativeComputeEmbedding returned null for text len=${text.length} (${i + 1}/${texts.size})")
                    }
                    embd
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Embedding computation crashed for text ${i + 1}/${texts.size}", e)
                    null
                }
            }
        }?.also {
            DebugLog.d(TAG, "Batch complete: ${texts.size} texts in ${System.currentTimeMillis() - start}ms")
        } ?: texts.map { null }
    }

    internal fun loadResident(modelPath: String): Boolean {
        check(nativeHandle == 0L) { "Embedding model already resident" }
        val start = System.currentTimeMillis()
        nativeHandle = nativeLoadModel(modelPath)
        if (nativeHandle == 0L) {
            DebugLog.e(TAG, "Failed to load model (${System.currentTimeMillis() - start}ms)")
            return false
        }
        DebugLog.d(
            TAG,
            "Model loaded in ${System.currentTimeMillis() - start}ms, dim=${nativeGetEmbeddingDim(nativeHandle)}",
        )
        return true
    }

    internal fun unloadResident() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) nativeFreeModel(handle)
    }
}
