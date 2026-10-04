package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Owns the koboldcpp-derived engine tuning preferences for the local llama.cpp runtime:
 * attention/cache/thread switches, speculative decoding, KV snapshotting and batching.
 * The settings facade delegates here so the tuning surface has a single owner, mirroring
 * the model- and backup-preference stores.
 */
internal class SettingsEngineTuningStore(private val dataStore: DataStore<Preferences>) {
    /** Atomic snapshot; see [LocalEngineTuning]. */
    val localEngineTuning: Flow<LocalEngineTuning> = dataStore.data.map { prefs ->
        LocalEngineTuning(
            flashAttention = normalizeLocalFlashAttention(prefs[LOCAL_FLASH_ATTENTION]),
            useMmap = prefs[LOCAL_MMAP] ?: DEFAULT_LOCAL_MMAP,
            cacheTypeK = normalizeLocalKvCacheType(prefs[LOCAL_CACHE_TYPE_K]),
            cacheTypeV = normalizeLocalKvCacheType(prefs[LOCAL_CACHE_TYPE_V]),
            swaFull = prefs[LOCAL_SWA_FULL] ?: DEFAULT_LOCAL_SWA_FULL,
            threads = normalizeLocalThreads(prefs[LOCAL_THREADS]),
            speculativeType = normalizeLocalSpeculativeType(prefs[LOCAL_SPECULATIVE_TYPE]),
            specDraftAmount = normalizeLocalDraftAmount(prefs[LOCAL_SPEC_DRAFT_AMOUNT]),
            ngramMatch = normalizeLocalNgramMatch(prefs[LOCAL_NGRAM_MATCH]),
            smartCache = prefs[LOCAL_SMART_CACHE] ?: DEFAULT_LOCAL_SMART_CACHE,
            smartCacheSlots = normalizeLocalSmartCacheSlots(prefs[LOCAL_SMART_CACHE_SLOTS]),
            smartContext = prefs[LOCAL_SMART_CONTEXT] ?: DEFAULT_LOCAL_SMART_CONTEXT,
            contextShift = prefs[LOCAL_CONTEXT_SHIFT] ?: DEFAULT_LOCAL_CONTEXT_SHIFT,
            fastForward = prefs[LOCAL_FAST_FORWARD] ?: DEFAULT_LOCAL_FAST_FORWARD,
            directIo = prefs[LOCAL_DIRECT_IO] ?: DEFAULT_LOCAL_DIRECT_IO,
            nBatch = normalizeLocalBatchSize(prefs[LOCAL_N_BATCH]),
            nUbatch = normalizeLocalBatchSize(prefs[LOCAL_N_UBATCH]),
        )
    }

    suspend fun saveLocalFlashAttention(mode: String) {
        dataStore.edit { it[LOCAL_FLASH_ATTENTION] = normalizeLocalFlashAttention(mode) }
    }

    suspend fun saveLocalMmap(enabled: Boolean) {
        dataStore.edit { it[LOCAL_MMAP] = enabled }
    }

    suspend fun saveLocalCacheTypeK(type: String) {
        dataStore.edit { it[LOCAL_CACHE_TYPE_K] = normalizeLocalKvCacheType(type) }
    }

    suspend fun saveLocalCacheTypeV(type: String) {
        dataStore.edit { it[LOCAL_CACHE_TYPE_V] = normalizeLocalKvCacheType(type) }
    }

    suspend fun saveLocalSwaFull(enabled: Boolean) {
        dataStore.edit { it[LOCAL_SWA_FULL] = enabled }
    }

    suspend fun saveLocalThreads(threads: Int) {
        dataStore.edit { it[LOCAL_THREADS] = normalizeLocalThreads(threads) }
    }

    suspend fun saveLocalSpeculativeType(type: String) {
        dataStore.edit { it[LOCAL_SPECULATIVE_TYPE] = normalizeLocalSpeculativeType(type) }
    }

    suspend fun saveLocalSpecDraftAmount(amount: Int) {
        dataStore.edit { it[LOCAL_SPEC_DRAFT_AMOUNT] = normalizeLocalDraftAmount(amount) }
    }

    suspend fun saveLocalNgramMatch(match: Int) {
        dataStore.edit { it[LOCAL_NGRAM_MATCH] = normalizeLocalNgramMatch(match) }
    }

    suspend fun saveLocalSmartCache(enabled: Boolean) {
        dataStore.edit { it[LOCAL_SMART_CACHE] = enabled }
    }

    suspend fun saveLocalSmartCacheSlots(slots: Int) {
        dataStore.edit { it[LOCAL_SMART_CACHE_SLOTS] = normalizeLocalSmartCacheSlots(slots) }
    }

    suspend fun saveLocalSmartContext(enabled: Boolean) {
        dataStore.edit { it[LOCAL_SMART_CONTEXT] = enabled }
    }

    suspend fun saveLocalContextShift(enabled: Boolean) {
        dataStore.edit { it[LOCAL_CONTEXT_SHIFT] = enabled }
    }

    suspend fun saveLocalFastForward(enabled: Boolean) {
        dataStore.edit { it[LOCAL_FAST_FORWARD] = enabled }
    }

    suspend fun saveLocalDirectIo(enabled: Boolean) {
        dataStore.edit { it[LOCAL_DIRECT_IO] = enabled }
    }

    suspend fun saveLocalNBatch(size: Int) {
        dataStore.edit { it[LOCAL_N_BATCH] = normalizeLocalBatchSize(size) }
    }

    suspend fun saveLocalNUbatch(size: Int) {
        dataStore.edit { it[LOCAL_N_UBATCH] = normalizeLocalBatchSize(size) }
    }
}
