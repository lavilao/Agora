package com.newoether.agora.api

import android.content.Context
import com.newoether.agora.data.CactusBundleManager
import com.newoether.agora.util.DebugLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin boundary of the Whistle speech model on the prebuilt Needle engine.
 *
 * Whistle is Cactus' on-device speech-to-text model: a single 16.9 MB ".cact"
 * file that runs on the very same engine as Needle 3 (HuggingFace
 * Cactus-Compute/whistle). The engine keeps one model per kind —
 * NEEDLE_TEXT for the chat router, NEEDLE_SPEECH for transcription — so a
 * Whistle model lives beside the resident chat model without disturbing it,
 * and transcription stays available whichever model the conversation uses
 * (Needle .cact or any GGUF through llama.cpp).
 *
 * Lifecycle mirrors the chat side: the runtime has no unload API, so the
 * model is loaded on the first transcription and stays resident for the
 * process. Transcription serializes with chat generation on the engine's
 * single native mutex, which is the correct discipline for its
 * non-thread-safe, process-global context.
 */
internal object CactusSpeechEngine {
    private const val TAG = "CactusSpeechEngine"

    /** Catalog filename of the Whistle speech model. */
    const val WHISTLE_FILENAME = "whistle.cact"

    private external fun nativeLoadSpeechModel(path: String): Boolean

    /** PCM is 16 kHz mono float in [-1, 1]; returns the engine JSON verbatim. */
    private external fun nativeTranscribe(
        pcm: FloatArray,
        samples: Int,
        language: String?,
        keywords: String?,
    ): String

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val loadMutex = Mutex()

    @Volatile
    private var loadedPath: String? = null

    /**
     * The installed Whistle model file: the catalog download under
     * [WHISTLE_FILENAME], or any imported ".cact" whose name starts with
     * "whistle" (the import flow keeps the original file name).
     */
    fun whistleModelFile(context: Context): File? {
        val root = CactusBundleManager(context).bundlesRoot()
        if (!root.isDirectory) return null
        val exact = File(root, WHISTLE_FILENAME)
        if (exact.isFile && exact.length() > 0) return exact
        return root.listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0 && it.name.endsWith(".cact", true) }
            .sortedBy { it.name }
            .firstOrNull { it.name.startsWith("whistle", true) }
    }

    /**
     * True when the microphone can be offered in the composer: the process
     * runs the prebuilt Needle engine (32-bit build) and a Whistle model is
     * installed. The arm64 full engine has no .cact support, so the mic hides
     * there rather than failing at record time.
     */
    fun isModelAvailable(context: Context): Boolean =
        CactusEngine.isNeedleBackend() && whistleModelFile(context) != null

    /**
     * Loads the Whistle model if needed and returns its file, or null when it
     * is missing or the engine refused it (a text .cact, a corrupt download).
     * Safe to call before every transcription: an already-resident model is
     * confirmed without touching the engine.
     */
    suspend fun ensureModelLoaded(context: Context): File? {
        val file = whistleModelFile(context) ?: return null
        val path = file.absolutePath
        if (loadedPath == path) return file
        loadMutex.withLock {
            if (loadedPath == path) return file
            val loaded = withContext(Dispatchers.IO) {
                try {
                    nativeLoadSpeechModel(path)
                } catch (e: UnsatisfiedLinkError) {
                    DebugLog.e(TAG, "Speech engine unavailable", e)
                    false
                }
            }
            if (!loaded) {
                DebugLog.e(TAG, "Whistle model failed to load: ${CactusEngine.lastError()}")
                return null
            }
            loadedPath = path
            return file
        }
    }

    /**
     * Transcribes one clip. [pcm] holds 16 kHz mono samples in [-1, 1] and is
     * truncated to the engine's 30 s ceiling by the caller; [language] is an
     * ISO code ("en", "de", "fr", "es", "it", "nl", "pl") or null to let the
     * model detect it. Runs on the IO dispatcher and never throws: failures
     * arrive as [WhistleTranscription.error].
     */
    suspend fun transcribe(
        pcm: FloatArray,
        language: String? = null,
    ): WhistleTranscription {
        if (pcm.isEmpty()) {
            return WhistleTranscription.failure("no audio was captured")
        }
        if (loadedPath == null) {
            return WhistleTranscription.failure("the speech model is not loaded")
        }
        var nativeError: String? = null
        val raw = withContext(Dispatchers.IO) {
            try {
                nativeTranscribe(pcm, pcm.size, language, null)
            } catch (e: UnsatisfiedLinkError) {
                nativeError = "the speech engine is unavailable on this build"
                ""
            } catch (e: Throwable) {
                nativeError = e.message ?: "transcription failed"
                ""
            }
        }
        if (raw.isBlank()) {
            return WhistleTranscription.failure(
                nativeError ?: CactusEngine.lastError().ifBlank { "transcription failed" },
            )
        }
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val error = root["error"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() && it != "null" }
            WhistleTranscription(
                text = root["text"]?.jsonPrimitive?.content.orEmpty(),
                language = root["language"]?.jsonPrimitive?.content.orEmpty(),
                timeToFirstTokenMs = root["ttft_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                decodeTokensPerSecond = root["decode_tps"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                error = error,
            )
        } catch (e: Exception) {
            WhistleTranscription.failure("malformed engine response: ${e.message}")
        }
    }
}

/** One Whistle result; [error] is null exactly when the engine answered. */
internal data class WhistleTranscription(
    val text: String,
    val language: String,
    val timeToFirstTokenMs: Double,
    val decodeTokensPerSecond: Double,
    val error: String?,
) {
    companion object {
        fun failure(message: String) = WhistleTranscription(
            text = "",
            language = "",
            timeToFirstTokenMs = 0.0,
            decodeTokensPerSecond = 0.0,
            error = message,
        )
    }
}
