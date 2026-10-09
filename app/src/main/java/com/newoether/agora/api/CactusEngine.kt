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
 * DotProd + I8MM: the full runtime is therefore only packaged for arm64-v8a.
 * 32-bit phones get the prebuilt Needle 3 engine Cactus publishes for
 * armeabi-v7a, which runs single ".cact" models through the same [CactusChatEngine]
 * surface. On ABIs without any packaged engine [isAvailable] reports false and
 * the settings surface explains the requirement, mirroring how greyed-out
 * Vulkan options are presented.
 */
object CactusEngine {
    private const val TAG = "CactusEngine"

    /** Persistence key used by [com.newoether.agora.data.LocalChatModelConfig]. */
    const val ENGINE_ID = "cactus"

    /** Human-readable engine label shown in badges and message information. */
    const val ENGINE_LABEL = "Cactus"

    /** Vendored upstream release; weight bundles must use tags <= this version. */
    const val UPSTREAM_VERSION = "v2.2.2"

    /** Backend id: full engine built from the vendored source (arm64). */
    const val BACKEND_CACTUS = "cactus"

    /** Backend id: prebuilt Needle 3 runtime for 32-bit phones (.cact models). */
    const val BACKEND_NEEDLE = "needle"

    @Volatile
    private var availability: Boolean? = null

    @Volatile
    private var backend: String? = null

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
    private external fun nativeEngineBackend(): String

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

    /**
     * Which engine flavour this build ships: [BACKEND_CACTUS] (full runtime,
     * bundle directories, arm64), [BACKEND_NEEDLE] (prebuilt Needle 3 runtime,
     * single .cact models, armv7) or null when no engine is packaged. Only
     * meaningful after [isAvailable] returned true on the same process.
     */
    fun backendKind(): String? = backend

    /** True when this build runs the prebuilt Needle 3 runtime. */
    internal fun isNeedleBackend(): Boolean = backend == BACKEND_NEEDLE

    private fun probeAvailability(nativeLibraryDir: String): Boolean {
        if (!File(nativeLibraryDir, "libagora_cactus.so").isFile) return false
        return try {
            System.loadLibrary("agora_cactus")
            backend = try {
                nativeEngineBackend()
            } catch (_: UnsatisfiedLinkError) {
                // An older library without the probe is the full engine.
                BACKEND_CACTUS
            }
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
    /** Needle only: calls the engine withheld for low confidence. */
    val suppressedCalls: List<CactusFunctionCall>,
    /** Needle only: calibrated confidence of the emitted decision. */
    val confidence: Double,
    /** True when this is a settled continuation (after tool results), not a decision. */
    val isContinuation: Boolean,
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
            val suppressedCalls = root["suppressed_calls"]?.jsonArray?.mapNotNull { element ->
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
                // The Needle runtime names its fields "reasoning" and
                // "peak_ram_mb"; the full engine uses "thinking" and
                // "ram_usage_mb".
                response = root["response"]?.jsonPrimitive?.content.orEmpty(),
                thinking = (
                    root["thinking"]?.jsonPrimitive?.content
                        ?: root["reasoning"]?.jsonPrimitive?.content
                    ).orEmpty(),
                functionCalls = functionCalls,
                suppressedCalls = suppressedCalls,
                confidence = root.doubleOf("confidence"),
                isContinuation = root["continuation"]?.jsonPrimitive?.content == "true",
                timeToFirstTokenMs = root.doubleOf("time_to_first_token_ms"),
                totalTimeMs = root.doubleOf("total_time_ms"),
                prefillTps = root.doubleOf("prefill_tps"),
                decodeTps = root.doubleOf("decode_tps"),
                ramUsageMb = root.doubleOf("ram_usage_mb")
                    .takeIf { it > 0.0 } ?: root.doubleOf("peak_ram_mb"),
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
            suppressedCalls = emptyList(),
            confidence = 0.0,
            isContinuation = false,
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

    /**
     * Converts the OpenAI-shaped tool list the chat pipeline builds (each entry
     * {"type":"function","function":{...}}) into the flat schema the Needle
     * runtime declares (each entry {name, description, parameters}). Anything
     * already flat — or unparseable — is returned unchanged so a future
     * producer stays loadable.
     */
    fun toolsToNeedleFlat(toolsJson: String): String = try {
        val array = json.parseToJsonElement(toolsJson).jsonArray
        buildJsonArray {
            array.forEach { element ->
                val entry = element.jsonObject
                val wrapped = entry["function"]?.jsonObject
                val wrappedName = wrapped?.get("name")?.jsonPrimitive?.content
                if (wrapped != null && wrappedName != null &&
                    entry["type"]?.jsonPrimitive?.content == "function"
                ) {
                    add(buildJsonObject {
                        put("name", wrappedName)
                        put(
                            "description",
                            wrapped["description"]?.jsonPrimitive?.content.orEmpty(),
                        )
                        put("parameters", wrapped["parameters"] ?: buildJsonObject { })
                    })
                } else {
                    add(entry)
                }
            }
        }.toString()
    } catch (e: Exception) {
        toolsJson
    }
}

/**
 * Resident chat model for one Cactus model. Mirrors the lifecycle discipline
 * of [LlamaChatEngine]: a handle is created by [load], released exactly once
 * in [close], and [cancel] is safe to call from any thread while a completion
 * is running.
 *
 * [bundlePath] is a ".cactus" bundle directory on the full engine (arm64) or
 * a single ".cact" model file on the prebuilt Needle runtime (armv7).
 */
internal class CactusChatEngine(
    val bundlePath: String,
) : Closeable {

    @Volatile
    private var nativeHandle: Long = 0L

    /** Needle session: user turns already accumulated inside the runtime. */
    private var fedUserTurns: List<String> = emptyList()

    private val needleBackend: Boolean
        get() = CactusEngine.isNeedleBackend()

    fun isLoaded(): Boolean = nativeHandle != 0L

    fun load(): Boolean {
        check(nativeHandle == 0L) { "Cactus model already resident" }
        val target = File(bundlePath)
        when {
            target.isDirectory -> {
                // Full engine: a bundle directory is identified by config.txt.
                if (!File(target, "config.txt").isFile) {
                    DebugLog.e(TAG, "Cactus bundle directory is missing config.txt: $bundlePath")
                    return false
                }
            }
            target.isFile -> {
                // Needle runtime: a single .cact model file.
            }
            else -> {
                DebugLog.e(TAG, "Cactus model path does not exist: $bundlePath")
                return false
            }
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
        if (needleBackend) {
            return completeNeedle(turns, options, toolsJson)
        }
        val messagesJson = CactusJson.turnsToJson(turns)
        val buffer = ByteArray(1024 * 1024)
        val written = CactusEngineBridge.complete(
            handle, messagesJson, options.toJson(), toolsJson, onToken, buffer,
        )
        if (written < 0) {
            return completionFailure(CactusEngine.lastError())
        }
        val raw = buffer.decodeToString(0, written.toInt())
        return CactusCompletionResult.parse(raw)
    }

    /**
     * Needle runtime completion. The runtime keeps its own accumulating
     * conversation and answers one user turn per call, so this layer tracks
     * which user turns were already fed ([fedUserTurns]) and only sends the
     * delta: a matching prefix extends the conversation, anything else
     * (branch, regenerate, another conversation) replays from scratch.
     * Assistant and tool turns are never fed: the runtime already owns the
     * calls it generated, and feeding tool results pollutes its router.
     */
    private fun completeNeedle(
        turns: List<CactusChatTurn>,
        options: CactusCompletionOptions,
        toolsJson: String?,
    ): CactusCompletionResult {
        val handle = nativeHandle
        // Agora's system prompt can carry injected context far beyond what an
        // 8192-token router model digests; keep a bounded tail of facts.
        val system = turns.firstOrNull { it.role == "system" }?.content.orEmpty()
            .take(NEEDLE_MAX_SYSTEM_CHARS)
        val userTexts = turns.filter { it.role == "user" }.map { it.content }

        var feed: List<String> = userTexts
        var reset = true
        val extendsFed = fedUserTurns.size <= userTexts.size &&
            fedUserTurns.indices.all { fedUserTurns[it] == userTexts[it] }
        if (extendsFed) {
            feed = userTexts.drop(fedUserTurns.size)
            reset = false
        }
        if (feed.isEmpty()) {
            if (turns.lastOrNull()?.role == "tool") {
                // Continuation after tool results: nothing new to route; the
                // settled no-op envelope closes the generation cleanly.
                return CactusCompletionResult.parse(
                    "{\"success\":true,\"error\":null,\"response\":\"\"," +
                        "\"function_calls\":[],\"suppressed_calls\":[]," +
                        "\"continuation\":true}",
                )
            }
            // Regeneration of the previous turn: replay the conversation.
            feed = userTexts
            reset = true
        }

        val envelope = buildJsonObject {
            put("system", system)
            put("feed", buildJsonArray { feed.forEach { add(JsonPrimitive(it)) } })
            put("reset", reset)
            // The C API is synchronous with no cancellation hook; bound the
            // worst case so a runaway generation cannot wedge the resident
            // engine for minutes.
            put("max_new_tokens", options.maxTokens.coerceIn(64, NEEDLE_MAX_NEW_TOKENS))
        }.toString()
        val toolsFlat = toolsJson?.let { CactusJson.toolsToNeedleFlat(it) } ?: "[]"
        val buffer = ByteArray(1024 * 1024)
        val written = CactusEngineBridge.complete(
            handle, envelope, "{}", toolsFlat, null, buffer,
        )
        if (written < 0) {
            return completionFailure(CactusEngine.lastError())
        }
        fedUserTurns = userTexts
        return CactusCompletionResult.parse(buffer.decodeToString(0, written.toInt()))
    }

    private fun completionFailure(message: String): CactusCompletionResult {
        val detail = message.ifBlank { "Cactus completion failed" }
        return CactusCompletionResult.parse(
            "{\"success\":false,\"error\":" +
                kotlinx.serialization.json.JsonPrimitive(detail).toString() +
                ",\"response\":\"\",\"prefill_tokens\":0,\"decode_tokens\":0}",
        )
    }

    /**
     * Streaming completion producing the same event vocabulary the llama.cpp
     * engine emits, so [com.newoether.agora.api.local.LocalProvider] consumes
     * both engines identically. When tools are active the raw token stream is
     * suppressed — the engine's structured function calls only settle in the
     * final JSON, and streaming the intermediate tool syntax as chat text
     * would leak protocol noise into the conversation.
     *
     * The Needle runtime has no token callback, so it always answers in
     * buffered mode. When it routes nothing (no tool matched, or the call was
     * withheld for low confidence) [emptyResponseText] — provided by the
     * caller, which owns localization — becomes the visible answer instead
     * of an empty bubble.
     */
    fun generate(
        turns: List<CactusChatTurn>,
        options: CactusCompletionOptions,
        toolsJson: String?,
        emptyResponseText: String? = null,
    ): Flow<LlamaGenerationEvent> = callbackFlow {
        val handle = nativeHandle
        if (handle == 0L) {
            close(RuntimeException("Model not loaded"))
            return@callbackFlow
        }
        val streaming = toolsJson == null && !needleBackend
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
            val visibleText = if (result.isContinuation) {
                // After tool results the cards are the answer: no filler text.
                ""
            } else {
                result.response.ifBlank {
                    if (result.functionCalls.isEmpty()) emptyResponseText.orEmpty() else ""
                }
            }
            if (visibleText.isNotEmpty()) {
                trySendBlocking(LlamaGenerationEvent.Text(visibleText))
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

        /** Static-prefix budget for the needle router (8192-token context). */
        private const val NEEDLE_MAX_SYSTEM_CHARS = 6000

        /** Hard ceiling on one needle generation (see completeNeedle). */
        private const val NEEDLE_MAX_NEW_TOKENS = 512
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
