package com.newoether.agora.data.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun decodeSelectionMap(raw: String?): MutableMap<String?, String> =
    raw?.let {
        runCatching {
            Json.decodeFromString<Map<String, String>>(it)
                .mapKeysTo(mutableMapOf()) { entry ->
                    if (entry.key == "null") null else entry.key
                }
        }.getOrDefault(mutableMapOf())
    } ?: mutableMapOf()

internal fun encodeSelectionMap(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

/** SQLite's per-statement bound-parameter ceiling on older Android releases
 * (999 for the bundled pre-3.32 engines; 900 leaves headroom for any fixed
 * parameters a statement might add). */
internal const val SQLITE_MAX_BIND_PARAMETERS = 900

/** Only the columns needed to paint navigation; no conversation-owned payloads. */
data class DrawerConversationRow(
    val id: String,
    val title: String,
    val hasUnreadGeneration: Boolean,
    val isPinned: Boolean,
)
