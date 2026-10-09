package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Kotlin boundary of the Cactus alternative inference engine.
 *
 * Cactus (github.com/cactus-compute/cactus) is a self-contained engine with its
 * own kernels and CQ quantized weights. It loads prebuilt ".cactus" bundle
 * directories (config.txt, vocab.txt, components/manifest.json, *.weights)
 * instead of GGUF files, and its NEON kernels require ARMv8.2-A + FP16 +
 * DotProd + I8MM. The engine is therefore only packaged for arm64-v8a: on
 * 32-bit devices [isAvailable] reports false and the settings surface explains
 * that a 64-bit build is required, mirroring how greyed-out Vulkan options are
 * presented.
 */
object CactusEngine {
    private const val TAG = "CactusEngine"

    /** Persistence key used by [com.newoether.agora.data.LocalChatModelConfig]. */
    const val ENGINE_ID = "cactus"

    /** Human-readable engine label shown in badges and message information. */
    const val ENGINE_LABEL = "Cactus"

    /** Vendored upstream release; weight bundles must use tags <= this version. */
    const val UPSTREAM_VERSION = "v2.2.2"

    @Volatile
    private var availability: Boolean? = null

    init {
        // The library load is deferred to the availability probe so a 32-bit
        // build never performs a doomed System.loadLibrary during class init.
    }

    private external fun nativeInit(bundlePath: String): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeComplete(
        handle: Long,
        messagesJson: String,
        optionsJson: String?,
        toolsJson: String?,
        tokenCallback: Any?,
        responseBuffer: ByteArray,
    ): Int
    private external fun nativeGetLastError(): String
    private external fun nativeSetLogLevel(level: Int)

    // Internal indirection so CactusChatEngine can share the loaded boundary.
    internal fun nativeInitPublic(bundlePath: String): Long = nativeInit(bundlePath)
    internal fun nativeDestroyPublic(handle: Long) = nativeDestroy(handle)
    internal fun nativeStopPublic(handle: Long) = nativeStop(handle)
    internal fun nativeCompletePublic(
        handle: Long,
        messagesJson: String,
        optionsJson: String,
        toolsJson: String?,
        tokenCallback: Any?,
        responseBuffer: ByteArray,
    ): Int = nativeComplete(handle, messagesJson, optionsJson, toolsJson, tokenCallback, responseBuffer)

    /**
     * True when this APK ships the Cactus native library for the running
     * device's ABI. Probing is cheap and cached; it loads the library exactly
     * once so the JNI boundary can never throw later.
     */
    fun isAvailable(nativeLibraryDir: String): Boolean {
        availability?.let { return it }
        synchronized(this) {
            availability?.let { return it }
            val available = probeAvailability(nativeLibraryDir)
            availability = available
            if (!available) {
                DebugLog.w(TAG, "Cactus engine unavailable on this build/ABI")
            }
            return available
        }
    }

    private fun probeAvailability(nativeLibraryDir: String): Boolean {
        if (!File(nativeLibraryDir, "libagora_cactus.so").isFile) return false
        return try {
            System.loadLibrary("agora_cactus")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    internal fun lastError(): String = try {
        nativeGetLastError()
    } catch (_: UnsatisfiedLinkError) {
        ""
    }

    internal fun setLogLevel(level: Int) {
        try {
            nativeSetLogLevel(level)
        } catch (_: UnsatisfiedLinkError) {
            // Library absent on this ABI; nothing to configure.
        }
    }
}

/** One chat turn sent to [CactusChatEngine.complete] in OpenAI-ish JSON form. */
internal data class CactusChatTurn(
    val role: String,
    val content: String,
    val images: List<String> = emptyList(),
    val toolName: String = "",
    val toolCalls: List<CactusTurnToolCall> = emptyList(),
)

internal data class CactusTurnToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** Generation options serialized into the engine's options JSON. */
internal data class CactusCompletionOptions(
    val temperature: Double = 0.7,
    val topP: Double = 0.9,
    val maxTokens: Int = 1024,
    val enableThinking: Boolean = false,
) {
    fun toJson(): String = buildJsonObject {
        put("temperature", temperature)
        put("top_p", topP)
        put("max_tokens", maxTokens)
        put("enable_thinking_if_supported", enableThinking)
        // Agora is a local-first app: the engine's cloud handoff and its
        // Supabase telemetry are hard-off regardless, these flags make every
        // single request explicit about running fully on device.
        put("auto_handoff", false)
        put("handoff_with_images", false)
        put("telemetry_enabled", false)
    }.toString()
}

internal data class CactusFunctionCall(
    val name: String,
    val argumentsJson: String,
)

/** Parsed payload of the engine's completion response JSON. */
internal data class CactusCompletionResult(
    val success: Boolean,
    val error: String?,
    val response: String,
    val thinking: String,
    val functionCalls: List<CactusFunctionCall>,
    val timeToFirstTokenMs: Double,
    val totalTimeMs: Double,
    val prefillTps: Double,
    val decodeTps: Double,
    val ramUsageMb: Double,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val totalTokens: Int,
) {
    companion object {
        fun parse(raw: String): CactusCompletionResult {
            val root = try {
                CactusJson.parse(raw)
            } catch (e: Exception) {
                return failed("Malformed engine response: ${e.message}")
            }
            val functionCalls = root["function_calls"]?.jsonArray?.mapNotNull { element ->
                val call = element.jsonObject
                val name = call["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val arguments = when (val args = call["arguments"]) {
                    null -> "{}"
                    is kotlinx.serialization.json.JsonObject -> args.toString()
                    else -> args.jsonPrimitive.content.ifBlank { "{}" }
                }
                CactusFunctionCall(name = name, argumentsJson = arguments)
            }.orEmpty()
            return CactusCompletionResult(
                success = root["success"]?.jsonPrimitive?.content == "true",
                error = root["error"]?.jsonPrimitive?.content?.takeIf { it != "null" && it.isNotBlank() },
                response = root["response"]?.jsonPrimitive?.content.orEmpty(),
                thinking = root["thinking"]?.jsonPrimitive?.content.orEmpty(),
                functionCalls = functionCalls,
                timeToFirstTokenMs = root.doubleOf("time_to_first_token_ms"),
                totalTimeMs = root.doubleOf("total_time_ms"),
                prefillTps = root.doubleOf("prefill_tps"),
                decodeTps = root.doubleOf("decode_tps"),
                ramUsageMb = root.doubleOf("ram_usage_mb"),
                prefillTokens = root.intOf("prefill_tokens"),
                decodeTokens = root.intOf("decode_tokens"),
                totalTokens = root.intOf("total_tokens"),
            )
        }

        private fun failed(message: String) = CactusCompletionResult(
            success = false,
            error = message,
            response = "",
            thinking = "",
            functionCalls = emptyList(),
            timeToFirstTokenMs = 0.0,
            totalTimeMs = 0.0,
            prefillTps = 0.0,
            decodeTps = 0.0,
            ramUsageMb = 0.0,
            prefillTokens = 0,
            decodeTokens = 0,
            totalTokens = 0,
        )

        private fun JsonObject.doubleOf(key: String): Double =
            this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

        private fun JsonObject.intOf(key: String): Int =
            this[key]?.jsonPrimitive?.intOrNull ?: 0
    }
}

internal object CactusJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): JsonObject {
        val trimmed = raw.trimEnd('\u0000').trim()
        val element = json.parseToJsonElement(trimmed)
        return element.jsonObject
    }

    fun turnsToJson(turns: List<CactusChatTurn>): String = buildJsonArray {
        turns.forEach { turn ->
            add(buildJsonObject {
                put("role", turn.role)
                put("content", turn.content)
                if (turn.images.isNotEmpty()) {
                    put("images", buildJsonArray {
                        turn.images.forEach { image -> add(JsonPrimitive(image)) }
                    })
                }
                if (turn.role == "tool" && turn.toolName.isNotBlank()) {
                    put("name", turn.toolName)
                }
                if (turn.toolCalls.isNotEmpty()) {
                    put("tool_calls", buildJsonArray {
                        turn.toolCalls.forEach { call ->
                            add(buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", call.name)
                                    put("arguments", call.argumentsJson.ifBlank { "{}" })
                                })
                            })
                        }
                    })
                }
            })
        }
    }.toString()
}

/**
 * Resident chat model for one ".cactus" bundle directory. Mirrors the
 * lifecycle discipline of [LlamaChatEngine]: a handle is created by [load],
 * released exactly once in [close], and [cancel] is safe to call from any
 * thread while a completion is running.
 */
internal class CactusChatEngine(
    val bundlePath: String,
) : Closeable {

    @Volatile
    private var nativeHandle: Long = 0L

    fun isLoaded(): Boolean = nativeHandle != 0L

    fun load(): Boolean {
        check(nativeHandle == 0L) { "Cactus model already resident" }
        val bundle = File(bundlePath)
        if (!bundle.isDirectory || !File(bundle, "config.txt").isFile) {
            DebugLog.e(TAG, "Cactus bundle directory is missing config.txt: $bundlePath")
            return false
        }
        nativeHandle = CactusEngineBridge.init(bundlePath)
        if (nativeHandle == 0L) {
            DebugLog.e(TAG, "Cactus model failed to load: ${CactusEngine.lastError()}")
            return false
        }
        return true
    }

    override fun close() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            CactusEngineBridge.destroy(handle)
        }
    }

    /** Asks a running completion to stop; generation then ends promptly. */
    fun cancel() {
        val handle = nativeHandle
        if (handle != 0L) {
            CactusEngineBridge.stop(handle)
        }
    }

    /**
     * Runs one blocking completion. When [onToken] is provided it receives the
     * text of every generated token on the engine's native thread; returning
     * false aborts generation. When null, tokens are not delivered and only
     * the final response JSON is returned.
     */
    fun complete(
        turns: List<CactusChatTurn>,
        options: CactusCompletionOptions,
        toolsJson: String?,
        onToken: CactusTokenStream?,
    ): CactusCompletionResult {
        val handle = nativeHandle
        if (handle == 0L) {
            return CactusCompletionResult.parse("{\"success\":false,\"error\":\"model not loaded\"}")
        }
        val messagesJson = CactusJson.turnsToJson(turns)
        val buffer = ByteArray(1024 * 1024)
        val written = CactusEngineBridge.complete(
            handle, messagesJson, options.toJson(), toolsJson, onToken, buffer,
        )
        if (written < 0) {
            val message = CactusEngine.lastError().ifBlank { "Cactus completion failed" }
            return CactusCompletionResult.parse(
                "{\"success\":false,\"error\":${kotlinx.serialization.json.JsonPrimitive(message)}" +
                    ",\"response\":\"\",\"prefill_tokens\":0,\"decode_tokens\":0}",
            )
        }
        val raw = buffer.decodeToString(0, written.toInt())
        return CactusCompletionResult.parse(raw)
    }

    /**
     * Streaming completion producing the same event vocabulary the llama.cpp
     * engine emits, so [com.newoether.agora.api.local.LocalProvider] consumes
     * both engines identically. When tools are active the raw token stream is
     * suppressed — the engine's structured function calls only settle in the
     * final JSON, and streaming the intermediate tool syntax as chat text
     * would leak protocol noise into the conversation.
     */
    fun generate(
        turns: List<CactusChatTurn>,
        options: CactusCompletionOptions,
        toolsJson: String?,
    ): Flow<LlamaGenerationEvent> = callbackFlow {
        val handle = nativeHandle
        if (handle == 0L) {
            close(RuntimeException("Model not loaded"))
            return@callbackFlow
        }
        val streaming = toolsJson == null
        val callback = if (streaming) {
            CactusTokenStream { token ->
                trySendBlocking(LlamaGenerationEvent.Text(token)).isSuccess
            }
        } else {
            null
        }
        val result = try {
            complete(turns, options, toolsJson, callback)
        } catch (e: Throwable) {
            close(e)
            return@callbackFlow
        }
        if (!result.success) {
            trySendBlocking(
                LlamaGenerationEvent.Failed(
                    message = result.error ?: "Cactus generation failed",
                    inputTokenCount = result.prefillTokens,
                    outputTokenCount = result.decodeTokens,
                    promptTokensPerSecond = result.prefillTps,
                    runtimeName = CactusEngine.ENGINE_LABEL,
                ),
            )
            close()
            return@callbackFlow
        }
        if (!streaming) {
            // Buffered mode: the engine already partitioned the final payload.
            if (result.thinking.isNotEmpty()) {
                trySendBlocking(LlamaGenerationEvent.Thought(result.thinking))
            }
            if (result.response.isNotEmpty()) {
                trySendBlocking(LlamaGenerationEvent.Text(result.response))
            }
        }
        if (result.functionCalls.isNotEmpty()) {
            trySendBlocking(
                LlamaGenerationEvent.ToolCallsCompleted(
                    result.functionCalls.mapIndexed { index, call ->
                        LlamaToolCall(
                            index = index,
                            id = null,
                            name = call.name,
                            arguments = call.argumentsJson,
                        )
                    },
                ),
            )
        }
        val reason = if (result.decodeTokens >= options.maxTokens) {
            LlamaGenerationStopReason.MAX_TOKENS
        } else {
            LlamaGenerationStopReason.EOG
        }
        trySendBlocking(
            LlamaGenerationEvent.Completed(
                reason = reason,
                inputTokenCount = result.prefillTokens,
                outputTokenCount = result.decodeTokens,
                promptTokensPerSecond = result.prefillTps,
                runtimeName = CactusEngine.ENGINE_LABEL,
            ),
        )
        close()
    }

    private companion object {
        private const val TAG = "CactusChatEngine"
    }
}

/** Fun interface bridging the native token callback into Kotlin lambdas. */
internal fun interface CactusTokenStream {
    fun onToken(token: String): Boolean
}

/** Type-safe indirection over the external functions in [CactusEngine]. */
private object CactusEngineBridge {
    fun init(bundlePath: String): Long = CactusEngine.nativeInitPublic(bundlePath)
    fun destroy(handle: Long) = CactusEngine.nativeDestroyPublic(handle)
    fun stop(handle: Long) = CactusEngine.nativeStopPublic(handle)
    fun complete(
        handle: Long,
        messagesJson: String,
        optionsJson: String,
        toolsJson: String?,
        onToken: CactusTokenStream?,
        buffer: ByteArray,
    ): Int = CactusEngine.nativeCompletePublic(handle, messagesJson, optionsJson, toolsJson, onToken, buffer)
}
