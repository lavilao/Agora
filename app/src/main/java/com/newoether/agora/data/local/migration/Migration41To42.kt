package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the fork's local-model performance metadata to messages.
 *
 * [promptProcessingTokensPerSecond] records the measured prompt-eval throughput of the
 * request that produced the message, and [runtimeName] names the llama.cpp backend device
 * (e.g. "Vulkan0 (PowerVR GE8320)" or "CPU") the model ran on. Both stay null for remote
 * providers and for messages generated before this version.
 *
 * Reconciliation note: this fork originally shipped these columns as migration 34→35,
 * while upstream 34→35 added [sourceJson] instead. After merging upstream (v41), the
 * fork's columns moved to the end of the chain. Every column is added defensively so the
 * step is valid for every lineage:
 *  - fork installs (v35 with these columns, without sourceJson),
 *  - upstream installs (v35 with sourceJson, without these columns),
 *  - fresh installs (v42 created directly, this migration never runs).
 */
val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(db, "messages", "promptProcessingTokensPerSecond", "REAL")
        addColumnIfMissing(db, "messages", "runtimeName", "TEXT")
        addColumnIfMissing(db, "messages", "sourceJson", "TEXT")
    }

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
