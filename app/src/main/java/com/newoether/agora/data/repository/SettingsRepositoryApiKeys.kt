package com.newoether.agora.data.repository

import com.newoether.agora.data.ApiKeyEntry
import kotlinx.coroutines.launch

/**
 * Write-paths for stored API keys, split out of [SettingsRepository] to respect
 * the 800-line handwritten-source budget. Behavior is byte-for-byte equivalent
 * to the former member functions: every setter launches on the repository's
 * [SettingsRepository.scope] and reads current state from the repository's own
 * StateFlows.
 */
internal fun SettingsRepository.addApiKey(name: String, key: String, provider: String) {
    scope.launch {
        val entry = ApiKeyEntry(name = name, key = key, provider = provider)
        settingsManager.saveApiKeys(apiKeys.value + entry)
        settingsManager.setActiveApiKeyId(provider, entry.id)
    }
}

/**
 * Store exactly one key for [provider]: update the existing entry in place if there
 * is one, otherwise add it — and drop any extra entries for the same provider.
 * Idempotent, so onboarding never accumulates duplicates.
 */
internal fun SettingsRepository.upsertApiKey(name: String, key: String, provider: String) {
    scope.launch {
        val current = apiKeys.value
        val existing = current.firstOrNull { it.provider == provider }
        val entry = existing?.copy(name = name, key = key) ?: ApiKeyEntry(name = name, key = key, provider = provider)
        settingsManager.saveApiKeys(current.filter { it.provider != provider } + entry)
        settingsManager.setActiveApiKeyId(provider, entry.id)
    }
}

internal fun SettingsRepository.deleteApiKey(id: String) {
    scope.launch {
        val current = apiKeys.value
        val entry = current.find { it.id == id } ?: return@launch
        val newList = current.filter { it.id != id }
        if (activeApiKeyIds.value[entry.provider] == id) {
            val other = newList.firstOrNull { it.provider == entry.provider }
            settingsManager.setActiveApiKeyId(entry.provider, other?.id)
        }
        settingsManager.saveApiKeys(newList)
    }
}

internal fun SettingsRepository.updateApiKey(id: String, name: String, key: String) {
    scope.launch {
        settingsManager.saveApiKeys(apiKeys.value.map { if (it.id == id) it.copy(name = name, key = key) else it })
    }
}

internal fun SettingsRepository.setActiveApiKey(provider: String, id: String) {
    scope.launch { settingsManager.setActiveApiKeyId(provider, id) }
}
