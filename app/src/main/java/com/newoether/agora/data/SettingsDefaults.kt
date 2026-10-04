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

internal fun normalizeLocalFlashAttention(value: String?): String =
    value?.lowercase()?.takeIf { it in LOCAL_FLASH_ATTENTION_MODES }
        ?: DEFAULT_LOCAL_FLASH_ATTENTION

internal fun normalizeLocalKvCacheType(value: String?): String =
    value?.lowercase()?.takeIf { it in LOCAL_KV_CACHE_TYPES } ?: DEFAULT_LOCAL_KV_CACHE_TYPE

internal fun normalizeLocalThreads(value: Int?): Int =
    value?.takeIf { it in LOCAL_THREAD_COUNTS } ?: DEFAULT_LOCAL_THREADS

/**
 * Persisted engine tuning for the local llama.cpp runtime. Part of the resident model's
 * identity, so any change reloads the model and applies from the next load, mirroring
 * how the backend preference behaves.
 */
data class LocalEngineTuning(
    val flashAttention: String = DEFAULT_LOCAL_FLASH_ATTENTION,
    val useMmap: Boolean = DEFAULT_LOCAL_MMAP,
    val cacheTypeK: String = DEFAULT_LOCAL_KV_CACHE_TYPE,
    val cacheTypeV: String = DEFAULT_LOCAL_KV_CACHE_TYPE,
    val swaFull: Boolean = DEFAULT_LOCAL_SWA_FULL,
    val threads: Int = DEFAULT_LOCAL_THREADS,
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

