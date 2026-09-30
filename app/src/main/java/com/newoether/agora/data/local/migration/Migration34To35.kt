package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds local-model performance metadata to messages.
 *
 * [promptProcessingTokensPerSecond] records the measured prompt-eval throughput of the
 * request that produced the message, and [runtimeName] names the llama.cpp backend device
 * (e.g. "Vulkan0 (PowerVR GE8320)" or "CPU") the model ran on. Both stay null for remote
 * providers and for messages generated before this version.
 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN promptProcessingTokensPerSecond REAL")
        db.execSQL("ALTER TABLE messages ADD COLUMN runtimeName TEXT")
    }
}
