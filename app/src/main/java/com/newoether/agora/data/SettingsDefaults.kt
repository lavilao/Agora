package com.newoether.agora.data

import java.util.Locale

internal const val DEFAULT_CONTEXT_COMPACT_ENABLED = true
internal const val DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT = 0
internal const val DEFAULT_CONTEXT_COMPACT_PRESERVE_SYSTEM_PROMPT = true
internal const val DEFAULT_CONTEXT_COMPACT_THRESHOLD_PERCENT = 90
internal val CONTEXT_COMPACT_THRESHOLD_PERCENT_RANGE = 50..100
internal const val DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES = 5
internal const val DEFAULT_LOCAL_LOW_CONTEXT_MODE_ENABLED = false
internal val LOCAL_MODEL_IDLE_RETENTION_PRESETS = intArrayOf(0, 1, 2, 5, 10, 15, 30)

/** Runtime backend preference for local llama.cpp models. */
internal val LOCAL_RUNTIME_PREFERENCES = listOf("auto", "cpu", "vulkan")
internal const val DEFAULT_LOCAL_RUNTIME_PREFERENCE = "auto"

/** Koboldcpp-derived engine capability switches for local llama.cpp models. */
internal val LOCAL_FLASH_ATTENTION_MODES = listOf("auto", "on", "off")
internal const val DEFAULT_LOCAL_FLASH_ATTENTION = "auto"
internal const val DEFAULT_LOCAL_MMAP = true
internal val LOCAL_KV_CACHE_TYPES = listOf(
    "f16", "f32", "bf16", "q8_0", "q4_0", "q4_1", "iq4_nl", "q5_0", "q5_1",
)
internal const val DEFAULT_LOCAL_KV_CACHE_TYPE = "f16"
internal const val DEFAULT_LOCAL_SWA_FULL = false
internal val LOCAL_THREAD_COUNTS = listOf(0, 1, 2, 3, 4, 6, 8)
internal const val DEFAULT_LOCAL_THREADS = 0

/** Speculative decoding (koboldcpp --usemtp / --draftmodel + llama.cpp --spec-type). */
internal val LOCAL_SPECULATIVE_TYPES = listOf(
    "off", "ngram_simple", "ngram_map_k", "ngram_map_k4v", "ngram_mod", "draft",
)
internal const val DEFAULT_LOCAL_SPECULATIVE_TYPE = "off"
internal val LOCAL_DRAFT_AMOUNTS = listOf(1, 2, 4, 8, 16, 24, 32, 48, 64)
internal const val DEFAULT_LOCAL_DRAFT_AMOUNT = 4
internal val LOCAL_NGRAM_MATCHES = listOf(16, 24, 32, 48, 64)
internal const val DEFAULT_LOCAL_NGRAM_MATCH = 24

/** Cache behavior (koboldcpp --smartcache / --smartcontext / --noshift / --nofastforward). */
internal const val DEFAULT_LOCAL_SMART_CACHE = false
internal val LOCAL_SMART_CACHE_SLOTS = listOf(1, 2, 3, 4)
internal const val DEFAULT_LOCAL_SMART_CACHE_SLOTS = 1
internal const val DEFAULT_LOCAL_SMART_CONTEXT = true
internal const val DEFAULT_LOCAL_CONTEXT_SHIFT = true
internal const val DEFAULT_LOCAL_FAST_FORWARD = true

/** Loading / batching (koboldcpp --usedirectio / --batchsize / --ubatchsize). */
internal const val DEFAULT_LOCAL_DIRECT_IO = false
internal val LOCAL_BATCH_SIZES = listOf(0, 32, 64, 128, 256, 512, 1024, 2048)
internal const val DEFAULT_LOCAL_BATCH_SIZE = 0

internal fun normalizeLocalFlashAttention(value: String?): String =
    value?.lowercase()?.takeIf { it in LOCAL_FLASH_ATTENTION_MODES }
        ?: DEFAULT_LOCAL_FLASH_ATTENTION

internal fun normalizeLocalKvCacheType(value: String?): String =
    value?.lowercase()?.takeIf { it in LOCAL_KV_CACHE_TYPES } ?: DEFAULT_LOCAL_KV_CACHE_TYPE

internal fun normalizeLocalThreads(value: Int?): Int =
    value?.takeIf { it in LOCAL_THREAD_COUNTS } ?: DEFAULT_LOCAL_THREADS

internal fun normalizeLocalSpeculativeType(value: String?): String =
    value?.lowercase()?.takeIf { it in LOCAL_SPECULATIVE_TYPES }
        ?: DEFAULT_LOCAL_SPECULATIVE_TYPE

internal fun normalizeLocalDraftAmount(value: Int?): Int =
    value?.takeIf { it in LOCAL_DRAFT_AMOUNTS } ?: DEFAULT_LOCAL_DRAFT_AMOUNT

internal fun normalizeLocalNgramMatch(value: Int?): Int =
    value?.takeIf { it in LOCAL_NGRAM_MATCHES } ?: DEFAULT_LOCAL_NGRAM_MATCH

internal fun normalizeLocalSmartCacheSlots(value: Int?): Int =
    value?.takeIf { it in LOCAL_SMART_CACHE_SLOTS } ?: DEFAULT_LOCAL_SMART_CACHE_SLOTS

internal fun normalizeLocalBatchSize(value: Int?): Int =
    value?.takeIf { it in LOCAL_BATCH_SIZES } ?: DEFAULT_LOCAL_BATCH_SIZE

/**
 * Persisted engine tuning for the local llama.cpp runtime. Part of the resident model's
 * identity, so any change reloads the model and applies from the next load, mirroring
 * how the backend preference behaves. The draft-model path is per-model config instead.
 */
data class LocalEngineTuning(
    val flashAttention: String = DEFAULT_LOCAL_FLASH_ATTENTION,
    val useMmap: Boolean = DEFAULT_LOCAL_MMAP,
    val cacheTypeK: String = DEFAULT_LOCAL_KV_CACHE_TYPE,
    val cacheTypeV: String = DEFAULT_LOCAL_KV_CACHE_TYPE,
    val swaFull: Boolean = DEFAULT_LOCAL_SWA_FULL,
    val threads: Int = DEFAULT_LOCAL_THREADS,
    val speculativeType: String = DEFAULT_LOCAL_SPECULATIVE_TYPE,
    val specDraftAmount: Int = DEFAULT_LOCAL_DRAFT_AMOUNT,
    val ngramMatch: Int = DEFAULT_LOCAL_NGRAM_MATCH,
    val smartCache: Boolean = DEFAULT_LOCAL_SMART_CACHE,
    val smartCacheSlots: Int = DEFAULT_LOCAL_SMART_CACHE_SLOTS,
    val smartContext: Boolean = DEFAULT_LOCAL_SMART_CONTEXT,
    val contextShift: Boolean = DEFAULT_LOCAL_CONTEXT_SHIFT,
    val fastForward: Boolean = DEFAULT_LOCAL_FAST_FORWARD,
    val directIo: Boolean = DEFAULT_LOCAL_DIRECT_IO,
    val nBatch: Int = DEFAULT_LOCAL_BATCH_SIZE,
    val nUbatch: Int = DEFAULT_LOCAL_BATCH_SIZE,
)

internal fun normalizeLocalRuntimePreference(value: String?): String =
    value?.lowercase()?.takeIf { it in LOCAL_RUNTIME_PREFERENCES }
        ?: DEFAULT_LOCAL_RUNTIME_PREFERENCE

internal fun normalizeLocalModelIdleRetentionMinutes(value: Int?): Int =
    value?.takeIf { it in LOCAL_MODEL_IDLE_RETENTION_PRESETS }
        ?: DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES

internal fun migrateUnmodifiedBuiltInDefault(
    prompts: List<SystemPromptEntry>,
    locale: Locale,
): List<SystemPromptEntry> {
    if (prompts.isEmpty()) return prompts
    val currentDefault = DefaultSystemPrompt.create(locale)
    return prompts.map { entry ->
        if (DefaultSystemPrompt.isUnmodifiedPreviousVersion(entry)) {
            entry.copy(
                content = "",
                systemItems = currentDefault.systemItems,
                userItems = currentDefault.resolvedUserItems,
                assistantItems = currentDefault.resolvedAssistantItems,
                userPrependItems = emptyList(),
                userPostpendItems = emptyList(),
            )
        } else {
            entry
        }
    }
}

