package com.newoether.agora.data.local.migration

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Migration47To48Test {
    @Test
    fun upstreamInstallKeepsItsSemanticTablesAndOnlyAddsTheLocalRuntimeColumns() {
        withDatabaseAtV47 { db ->
            db.execSQL(OWNER)
            db.execSQL(
                "INSERT INTO semantic_sources (messageId, conversationId, eligible, sourcePrefix, " +
                    "sourceFingerprint, searchLength, historyEligible) VALUES ('m','owner',1,X'00',NULL,1,1)",
            )
            db.execSQL(LEDGER_CURRENT)
        }.also { context ->
            val database = ChatDatabase.build(context)
            try {
                val readable = database.openHelper.readableDatabase
                assertEquals(ChatDatabase.CURRENT_VERSION, readable.version)
                assertTrue(columns(readable, "messages").containsAll(RUNTIME_COLUMNS))
                readable.query("SELECT COUNT(*) FROM semantic_sources WHERE messageId = 'm'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                }
                readable.query("SELECT state, sourceRevision FROM semantic_index_ledger").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("CURRENT", it.getString(0))
                    assertEquals(3L, it.getLong(1))
                }
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun forkInstallRegainsTheSkippedSemanticTablesAndQueuesAReconcile() {
        withDatabaseAtV47 { db ->
            db.execSQL(OWNER)
            db.execSQL(LEDGER_CURRENT)
            SKIPPED_SEMANTIC_TABLES.forEach { db.execSQL("DROP TABLE $it") }
        }.also { context ->
            val database = ChatDatabase.build(context)
            try {
                val readable = database.openHelper.readableDatabase
                assertEquals(ChatDatabase.CURRENT_VERSION, readable.version)
                assertTrue(columns(readable, "messages").containsAll(RUNTIME_COLUMNS))
                SKIPPED_SEMANTIC_TABLES.forEach { table ->
                    readable.query(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                        arrayOf(table),
                    ).use {
                        assertTrue(it.moveToFirst())
                        assertEquals(table, 1, it.getInt(0))
                    }
                }
                readable.query(
                    "SELECT state, sourceRevision, reconcileRevision FROM semantic_index_ledger",
                ).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("NEEDS_RECONCILE", it.getString(0))
                    assertEquals(4L, it.getLong(1))
                    assertEquals(4L, it.getLong(2))
                }
            } finally {
                database.close()
            }
        }
    }

    /** Creates a v47 database from the exported schema, then applies [seed] on the raw connection. */
    private fun withDatabaseAtV47(seed: (SupportSQLiteDatabase) -> Unit): Application {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(
            File(root, "app/schemas/com.newoether.agora.data.local.ChatDatabase/47.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(ChatDatabase.DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(47) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.forEach { entry ->
                            val table = entry.jsonObject
                            val name = table.getValue("tableName").jsonPrimitive.content
                            db.execSQL(
                                table.getValue("createSql").jsonPrimitive.content
                                    .replace("\${TABLE_NAME}", name),
                            )
                            table["indices"]?.jsonArray?.forEach {
                                db.execSQL(
                                    it.jsonObject.getValue("createSql").jsonPrimitive.content
                                        .replace("\${TABLE_NAME}", name),
                                )
                            }
                        }
                        seed(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        ).use { it.writableDatabase }
        return context
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }

    private companion object {
        val RUNTIME_COLUMNS = setOf("promptProcessingTokensPerSecond", "runtimeName")
        val SKIPPED_SEMANTIC_TABLES = listOf(
            "semantic_sources",
            "semantic_conversations",
            "semantic_model_conversations",
            "semantic_metadata_state",
        )
        const val OWNER =
            "INSERT INTO conversations (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated," +
                "draftText,hasUnreadGeneration,createdAt) VALUES ('owner','Keep',1,1,0,'user',0,'',0,1)"
        const val LEDGER_CURRENT =
            "INSERT INTO semantic_index_ledger (modelId,state,sourceRevision,completedRevision," +
                "reconcileRevision,updatedAt,generation) VALUES ('model','CURRENT',3,3,3,1,0)"
    }
}
