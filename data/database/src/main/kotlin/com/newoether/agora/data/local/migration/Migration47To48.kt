package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Fork lineage: records the local engine's performance metadata on messages.
 *
 * [promptProcessingTokensPerSecond] is the measured prompt-eval throughput of the request that
 * produced the message, and [runtimeName] names the llama.cpp backend device the model ran on.
 * Both stay null for remote providers and for messages generated before this version.
 *
 * Reconciliation note: this fork shipped these columns as its own migration 41→42 while upstream
 * 41→42 instead introduced the `semantic_*` metadata tables. Installations of the fork therefore
 * stopped at a v42 database without those tables. Upstream then continued from its own v42 to v47,
 * so the fork appends its columns at the end of the chain and repairs the tables the fork lineage
 * skipped. Every statement is defensive so the step is valid for both lineages:
 *  - upstream installs (v47 with the semantic tables, without these columns),
 *  - fork installs (v42 with these columns' siblings absent, without the semantic tables),
 *  - fresh installs (v48 created directly, this migration never runs).
 */
val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(db, "messages", "promptProcessingTokensPerSecond", "REAL")
        addColumnIfMissing(db, "messages", "runtimeName", "TEXT")
        repairSkippedSemanticMetadata(db)
    }

    /** Only a fork-lineage database lacks these tables; upstream installs already have them. */
    private fun repairSkippedSemanticMetadata(db: SupportSQLiteDatabase) {
        if (tableExists(db, "semantic_sources")) return
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS semantic_sources (
                messageId TEXT NOT NULL PRIMARY KEY,
                conversationId TEXT NOT NULL,
                eligible INTEGER NOT NULL,
                sourcePrefix BLOB NOT NULL,
                sourceFingerprint TEXT,
                searchLength INTEGER NOT NULL,
                historyEligible INTEGER NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_semantic_sources_conversationId_messageId " +
                "ON semantic_sources(conversationId, messageId)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS semantic_conversations (
                conversationId TEXT NOT NULL PRIMARY KEY,
                sourceRevision INTEGER NOT NULL,
                eligibleCount INTEGER NOT NULL,
                cacheEligible INTEGER NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS semantic_model_conversations (
                modelId TEXT NOT NULL,
                conversationId TEXT NOT NULL,
                cachedCount INTEGER NOT NULL,
                indexedRevision INTEGER NOT NULL,
                PRIMARY KEY(modelId, conversationId),
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_semantic_model_conversations_conversationId " +
                "ON semantic_model_conversations(conversationId)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS semantic_metadata_state (
                id INTEGER NOT NULL PRIMARY KEY,
                complete INTEGER NOT NULL,
                afterMessageId TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("INSERT OR IGNORE INTO semantic_metadata_state(id, complete, afterMessageId) VALUES(0, 0, NULL)")
        db.execSQL(
            "UPDATE semantic_index_ledger SET state = 'NEEDS_RECONCILE', " +
                "sourceRevision = sourceRevision + 1, reconcileRevision = sourceRevision + 1",
        )
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
            .use { it.moveToFirst() }

    private fun addColumnIfMissing(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        type: String,
    ) {
        val exists = db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }
}
