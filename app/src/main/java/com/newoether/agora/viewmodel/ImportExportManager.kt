package com.newoether.agora.viewmodel

import android.app.Application
import android.net.Uri
import com.newoether.agora.R
import com.newoether.agora.automation.AutomationExecutionGate
import com.newoether.agora.data.ClaudeChatImporter
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import com.newoether.agora.data.DeepSeekChatImporter
import com.newoether.agora.data.GptChatImporter
import com.newoether.agora.data.QwenChatImporter
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.migration.LegacyMessageRecord
import com.newoether.agora.data.local.migration.LegacyRunBackfillPlanner
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.data.DataImporter.ImportStrategy
import com.newoether.agora.util.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private data class PlannedImportGraph(
    val runs: List<RunEntity>,
    val messages: List<MessageEntity>,
)

private fun planImportedLegacyMessages(
    messages: List<ClaudeChatImporter.ImportMessageEntity>,
): PlannedImportGraph {
    val runs = mutableListOf<RunEntity>()
    val assignedMessages = mutableListOf<MessageEntity>()
    for ((conversationId, conversationMessages) in messages.groupBy { it.conversationId }) {
        val plan = LegacyRunBackfillPlanner.plan(
            conversationId,
            conversationMessages.map {
                LegacyMessageRecord(
                    id = it.id,
                    parentId = it.parentId,
                    participant = safeValueOf<Participant>(it.participant) ?: Participant.MODEL,
                    status = safeValueOf<MessageStatus>(it.status) ?: MessageStatus.SUCCESS,
                    timestamp = it.timestamp,
                )
            },
        )
        runs += plan.runs.map {
            RunEntity(
                id = it.id,
                conversationId = it.conversationId,
                parentRunId = it.parentRunId,
                status = it.status,
                activeSlot = null,
                startedAt = it.startedAt,
                lastCheckpointAt = it.endedAt,
                endedAt = it.endedAt,
                endReason = it.endReason,
                legacyAmbiguous = it.legacyAmbiguous,
            )
        }
        val assignments = plan.assignments.associateBy { it.messageId }
        assignedMessages += conversationMessages.map { message ->
            val assignment = assignments.getValue(message.id)
            MessageEntity(
                id = message.id,
                conversationId = message.conversationId,
                parentId = message.parentId,
                text = message.text,
                images = message.images,
                thoughts = message.thoughts,
                thoughtTitle = message.thoughtTitle,
                tokenCount = message.tokenCount,
                status = safeValueOf<MessageStatus>(message.status) ?: MessageStatus.SUCCESS,
                participant = safeValueOf<Participant>(message.participant) ?: Participant.MODEL,
                timestamp = message.timestamp,
                thoughtTimeMs = message.thoughtTimeMs,
                modelName = message.modelName,
                toolCallJson = message.toolCallJson,
                attachmentMeta = message.attachmentMeta,
                runId = assignment.runId,
                runSequence = assignment.runSequence,
                consumedAtPass = assignment.consumedAtPass,
            )
        }
    }
    return PlannedImportGraph(runs, assignedMessages)
}

private inline fun <reified T : Enum<T>> safeValueOf(name: String): T? =
    try { enumValueOf<T>(name) } catch (_: Exception) { null }

/**
 * Orchestrates data export and import (native backup, Claude, and GPT formats):
 * owns the progress / preview / result flows, runs the import/export coroutines,
 * and merges parsed conversations into the DB. Delegates parsing/serialization to
 * the [DataExporter] / [DataImporter] / [ClaudeChatImporter] / [GptChatImporter]
 * engines. Extracted out of [ChatViewModel] (Phase E5); the ViewModel keeps thin
 * delegating wrappers and supplies [onDataChanged] to refresh its own data counts.
 *
 * NOTE: [chatDao] and [settingsManager] are retained here (not replaced by repos) because the
 * importer performs bulk database writes and both import/export own portable settings transfer.
 * Export conversation reads use a dedicated snapshot database and never borrow these foreground
 * Room dependencies.
 * All other managers use repositories uniformly.
 */
class ImportExportManager(
    private val app: Application,
    private val conversations: ConversationRepository,
    private val database: ChatDatabase,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager,
    private val conversationSettingsTransfers: ConversationSettingsTransferCoordinator,
    private val scope: CoroutineScope,
    private val emitSnackbar: suspend (SnackbarEvent) -> Unit,
    private val onDataChanged: suspend () -> Unit,
    private val automationExecutionGate: AutomationExecutionGate,
    private val quiesceAutomation: suspend () -> Unit,
    private val resumeAutomationScheduling: () -> Unit,
) {
    private val _exportProgress = MutableStateFlow<Float?>(null)
    val exportProgress: StateFlow<Float?> = _exportProgress.asStateFlow()

    private val _importProgress = MutableStateFlow<Float?>(null)
    val importProgress: StateFlow<Float?> = _importProgress.asStateFlow()

    private val _importPreviewLoading = MutableStateFlow(false)
    val importPreviewLoading: StateFlow<Boolean> = _importPreviewLoading.asStateFlow()

    private val _importManifest = MutableStateFlow<DataImporter.ImportManifest?>(null)
    val importManifest: StateFlow<DataImporter.ImportManifest?> = _importManifest.asStateFlow()

    private val _importPreview = MutableStateFlow<DataImporter.ImportPreview?>(null)
    val importPreview: StateFlow<DataImporter.ImportPreview?> = _importPreview.asStateFlow()

    // Claude import state
    private val _claudeImportPreview = MutableStateFlow<ClaudeChatImporter.ImportPreview?>(null)
    val claudeImportPreview: StateFlow<ClaudeChatImporter.ImportPreview?> = _claudeImportPreview.asStateFlow()

    private val _claudeImportProgress = MutableStateFlow<Float?>(null)
    val claudeImportProgress: StateFlow<Float?> = _claudeImportProgress.asStateFlow()

    private val _claudeImportResult = MutableStateFlow<ClaudeChatImporter.ImportResult?>(null)
    val claudeImportResult: StateFlow<ClaudeChatImporter.ImportResult?> = _claudeImportResult.asStateFlow()

    // GPT import state
    private val _gptImportPreview = MutableStateFlow<GptChatImporter.ImportPreview?>(null)
    val gptImportPreview: StateFlow<GptChatImporter.ImportPreview?> = _gptImportPreview.asStateFlow()

    private val _gptImportProgress = MutableStateFlow<Float?>(null)
    val gptImportProgress: StateFlow<Float?> = _gptImportProgress.asStateFlow()

    private val _gptImportResult = MutableStateFlow<GptChatImporter.ImportResult?>(null)
    val gptImportResult: StateFlow<GptChatImporter.ImportResult?> = _gptImportResult.asStateFlow()

    // DeepSeek import state
    private val _deepseekImportPreview = MutableStateFlow<DeepSeekChatImporter.ImportPreview?>(null)
    val deepseekImportPreview: StateFlow<DeepSeekChatImporter.ImportPreview?> =
        _deepseekImportPreview.asStateFlow()

    private val _deepseekImportProgress = MutableStateFlow<Float?>(null)
    val deepseekImportProgress: StateFlow<Float?> = _deepseekImportProgress.asStateFlow()

    private val _deepseekImportResult = MutableStateFlow<DeepSeekChatImporter.ImportResult?>(null)
    val deepseekImportResult: StateFlow<DeepSeekChatImporter.ImportResult?> =
        _deepseekImportResult.asStateFlow()

    // Qwen import state
    private val _qwenImportPreview = MutableStateFlow<QwenChatImporter.ImportPreview?>(null)
    val qwenImportPreview: StateFlow<QwenChatImporter.ImportPreview?> =
        _qwenImportPreview.asStateFlow()

    private val _qwenImportProgress = MutableStateFlow<Float?>(null)
    val qwenImportProgress: StateFlow<Float?> = _qwenImportProgress.asStateFlow()

    private val _qwenImportResult = MutableStateFlow<QwenChatImporter.ImportResult?>(null)
    val qwenImportResult: StateFlow<QwenChatImporter.ImportResult?> =
        _qwenImportResult.asStateFlow()

    fun exportData(uri: Uri, categories: Set<DataExporter.ExportCategory>, includeApiKeys: Boolean) {
        _exportProgress.value = 0f
        scope.launch(Dispatchers.IO) {
            try {
                val exporter = DataExporter(
                    app,
                    settingsManager,
                    memoryManager,
                    skillManager,
                )
                val result = exporter.export(uri, categories, includeApiKeys) { progress ->
                    _exportProgress.value = progress
                }
                _exportProgress.value = null
                emitSnackbar(
                    SnackbarEvent(
                        if (result.missingResourceCount > 0) {
                            app.getString(
                                R.string.export_success_missing_resources,
                                result.missingResourceCount,
                            )
                        } else {
                            app.getString(R.string.export_success)
                        },
                    ),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _exportProgress.value = null
                throw cancelled
            } catch (e: Exception) {
                _exportProgress.value = null
                emitSnackbar(SnackbarEvent(
                    app.getString(R.string.export_failed, e.localizedMessage ?: "")
                ))
            }
        }
    }

    fun previewImport(uri: Uri) {
        _importPreviewLoading.value = true
        clearImportState()
        scope.launch(Dispatchers.IO) {
            try {
                val importer = DataImporter(
                    app,
                    database,
                    chatDao,
                    settingsManager,
                    memoryManager,
                    skillManager,
                    conversationSettingsTransfers,
                )
                // One open answers both questions: is this a backup, and what does it hold.
                val preview = importer.preview(uri)
                if (preview.manifest.version == 0) {
                    emitSnackbar(SnackbarEvent(app.getString(R.string.import_invalid_file)))
                    return@launch
                }
                if (!preview.hasImportableData) {
                    emitSnackbar(SnackbarEvent(app.getString(R.string.import_no_data)))
                    return@launch
                }
                _importManifest.value = preview.manifest
                _importPreview.value = preview
            } catch (e: Exception) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.import_failed, e.localizedMessage ?: "")))
            } finally {
                _importPreviewLoading.value = false
            }
        }
    }

    fun clearImportState() {
        _importManifest.value = null
        _importPreview.value = null
    }

    fun setClaudeImportPreview(preview: ClaudeChatImporter.ImportPreview) {
        _claudeImportPreview.value = preview
    }

    /** Hard ceiling on import file size; beyond this we refuse rather than risk OOM. */
    private val maxImportBytes = 256L * 1024 * 1024

    /** Opens a readable stream for [uri], or throws with a localized message. */
    private fun openImportStream(uri: Uri): java.io.InputStream =
        app.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException(app.getString(R.string.could_not_read_file))

    /**
     * Returns a localized error if [uri] exceeds [maxImportBytes], else null.
     * The size is read from provider metadata without opening the file.
     */
    private fun importSizeError(uri: Uri): String? {
        val size = app.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
        return if (size > maxImportBytes) {
            app.getString(R.string.import_file_too_large, size / (1024 * 1024))
        } else null
    }

    fun previewClaudeChat(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                importSizeError(uri)?.let {
                    emitSnackbar(SnackbarEvent(it)); _claudeImportPreview.value = null; return@launch
                }
                val importer = ClaudeChatImporter()
                val parseResult = importer.extractAndParse { openImportStream(uri) }
                if (parseResult.isSuccess) {
                    _claudeImportPreview.value = importer.preview(parseResult.getOrThrow())
                } else {
                    emitSnackbar(SnackbarEvent(parseResult.exceptionOrNull()?.localizedMessage ?: app.getString(R.string.parse_error)))
                    _claudeImportPreview.value = null
                }
            } catch (e: OutOfMemoryError) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.import_out_of_memory)))
                _claudeImportPreview.value = null
            } catch (e: Exception) {
                emitSnackbar(SnackbarEvent(e.localizedMessage ?: app.getString(R.string.unknown_error)))
                _claudeImportPreview.value = null
            }
        }
    }

    fun setClaudeImportError(error: String) {
        scope.launch { emitSnackbar(SnackbarEvent(error)) }
        _claudeImportPreview.value = null
    }

    fun clearClaudeImportState() {
        _claudeImportPreview.value = null
        _claudeImportProgress.value = null
        _claudeImportResult.value = null
    }

    fun importClaudeChat(uri: Uri, strategy: ImportStrategy, selectedIds: Set<String>) {
        scope.launch(Dispatchers.IO) {
            try {
                _claudeImportProgress.value = 0.2f
                importSizeError(uri)?.let {
                    _claudeImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.claude_import_error_detail, it)))
                    return@launch
                }

                val importer = ClaudeChatImporter()
                val parseResult = importer.extractAndParse { openImportStream(uri) }
                if (parseResult.isFailure) {
                    _claudeImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.claude_import_error_detail, parseResult.exceptionOrNull()?.localizedMessage ?: app.getString(R.string.parse_error))))
                    return@launch
                }

                _claudeImportProgress.value = 0.4f
                val parsed = parseResult.getOrThrow()
                val preview = importer.preview(parsed)
                val importData = importer.toImportFormat(parsed, selectedIds)

                if (preview.totalMessageCount == 0) {
                    _claudeImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.claude_import_no_data)))
                    return@launch
                }

                _claudeImportProgress.value = 0.6f

                // Convert to Room entities
                val chatEntities = importData.conversations.map { ce ->
                    ChatEntity(ce.id, ce.title, ce.lastUpdated, dataChangedAt = 0L, selectedBranchesJson = ce.selectedBranchesJson, systemPromptId = ce.systemPromptId, modelId = ce.modelId)
                }
                if (strategy == ImportStrategy.REPLACE) {
                    val graph = planImportedLegacyMessages(importData.messages)
                    conversations.importExternalConversationGraph(
                        conversations = chatEntities,
                        runs = graph.runs,
                        messages = graph.messages,
                        replace = true,
                    )
                    _claudeImportProgress.value = 0.8f
                    _claudeImportResult.value = ClaudeChatImporter.ImportResult(chatEntities.size, graph.messages.size)
                } else {
                    val existingConvIds = conversations.getAllConversationsList().map { it.id }.toSet()
                    val existingMsgIds = conversations.findExistingMessageIds(importData.messages.map { it.id }).toSet()
                    val newCh = chatEntities.filterNot { it.id in existingConvIds }
                    val newMessageDrafts = importData.messages.filterNot { it.id in existingMsgIds }
                    val graph = planImportedLegacyMessages(newMessageDrafts)
                    conversations.importExternalConversationGraph(
                        conversations = newCh,
                        runs = graph.runs,
                        messages = graph.messages,
                        replace = false,
                    )
                    _claudeImportProgress.value = 0.8f
                    _claudeImportResult.value = ClaudeChatImporter.ImportResult(newCh.size, graph.messages.size)
                }
                onDataChanged()
            } catch (e: OutOfMemoryError) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.import_out_of_memory)))
            } catch (e: Exception) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.claude_import_error_detail, e.localizedMessage ?: "")))
            } finally {
                // Early exits (oversize, parse failure, no data) must not freeze the dialog.
                _claudeImportProgress.value = null
            }
        }
    }

    fun previewGptChat(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                importSizeError(uri)?.let {
                    emitSnackbar(SnackbarEvent(it)); _gptImportPreview.value = null; return@launch
                }
                val importer = GptChatImporter()
                val parseResult = importer.extractAndParse { openImportStream(uri) }
                if (parseResult.isSuccess) {
                    _gptImportPreview.value = importer.preview(parseResult.getOrThrow())
                } else {
                    emitSnackbar(SnackbarEvent(parseResult.exceptionOrNull()?.localizedMessage ?: app.getString(R.string.parse_error)))
                    _gptImportPreview.value = null
                }
            } catch (e: OutOfMemoryError) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.import_out_of_memory)))
                _gptImportPreview.value = null
            } catch (e: Exception) {
                emitSnackbar(SnackbarEvent(e.localizedMessage ?: app.getString(R.string.unknown_error)))
                _gptImportPreview.value = null
            }
        }
    }

    fun setGptImportError(error: String) {
        scope.launch { emitSnackbar(SnackbarEvent(error)) }
        _gptImportPreview.value = null
    }

    fun clearGptImportState() {
        _gptImportPreview.value = null
        _gptImportProgress.value = null
        _gptImportResult.value = null
    }

    fun importGptChat(uri: Uri, strategy: ImportStrategy, selectedIds: Set<String>) {
        scope.launch(Dispatchers.IO) {
            try {
                _gptImportProgress.value = 0.2f
                importSizeError(uri)?.let {
                    _gptImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.gpt_import_error_detail, it)))
                    return@launch
                }

                val importer = GptChatImporter()
                val parseResult = importer.extractAndParse { openImportStream(uri) }
                if (parseResult.isFailure) {
                    _gptImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.gpt_import_error_detail, parseResult.exceptionOrNull()?.localizedMessage ?: app.getString(R.string.parse_error))))
                    return@launch
                }

                _gptImportProgress.value = 0.4f
                val parsed = parseResult.getOrThrow()
                val preview = importer.preview(parsed)
                val importData = importer.toImportFormat(parsed, selectedIds)

                if (preview.totalMessageCount == 0) {
                    _gptImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.gpt_import_no_data)))
                    return@launch
                }

                _gptImportProgress.value = 0.6f

                val chatEntities = importData.conversations.map { ce ->
                    ChatEntity(ce.id, ce.title, ce.lastUpdated, dataChangedAt = 0L, selectedBranchesJson = ce.selectedBranchesJson, systemPromptId = ce.systemPromptId, modelId = ce.modelId)
                }
                val thoughtsCount = importData.messages.count { it.thoughts != null && it.thoughts.isNotBlank() }
                if (strategy == ImportStrategy.REPLACE) {
                    val graph = planImportedLegacyMessages(importData.messages)
                    conversations.importExternalConversationGraph(
                        conversations = chatEntities,
                        runs = graph.runs,
                        messages = graph.messages,
                        replace = true,
                    )
                    _gptImportProgress.value = 0.8f
                    _gptImportResult.value = GptChatImporter.ImportResult(chatEntities.size, graph.messages.size, thoughtsCount)
                } else {
                    val existingConvIds = conversations.getAllConversationsList().map { it.id }.toSet()
                    val existingMsgIds = conversations.findExistingMessageIds(importData.messages.map { it.id }).toSet()
                    val newCh = chatEntities.filterNot { it.id in existingConvIds }
                    val newMessageDrafts = importData.messages.filterNot { it.id in existingMsgIds }
                    val newThoughtsCount = newMessageDrafts.count { it.thoughts != null && it.thoughts.isNotBlank() }
                    val graph = planImportedLegacyMessages(newMessageDrafts)
                    conversations.importExternalConversationGraph(
                        conversations = newCh,
                        runs = graph.runs,
                        messages = graph.messages,
                        replace = false,
                    )
                    _gptImportProgress.value = 0.8f
                    _gptImportResult.value = GptChatImporter.ImportResult(newCh.size, graph.messages.size, newThoughtsCount)
                }
                onDataChanged()
            } catch (e: OutOfMemoryError) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.import_out_of_memory)))
            } catch (e: Exception) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.gpt_import_error_detail, e.localizedMessage ?: "")))
            } finally {
                // Early exits (oversize, parse failure, no data) must not freeze the dialog.
                _gptImportProgress.value = null
            }
        }
    }

    fun previewDeepSeekChat(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                importSizeError(uri)?.let {
                    emitSnackbar(SnackbarEvent(it)); _deepseekImportPreview.value = null; return@launch
                }
                val importer = DeepSeekChatImporter()
                val parseResult = importer.extractAndParse { openImportStream(uri) }
                if (parseResult.isSuccess) {
                    _deepseekImportPreview.value = importer.preview(parseResult.getOrThrow())
                } else {
                    emitSnackbar(SnackbarEvent(parseResult.exceptionOrNull()?.localizedMessage ?: app.getString(R.string.parse_error)))
                    _deepseekImportPreview.value = null
                }
            } catch (e: OutOfMemoryError) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.import_out_of_memory)))
                _deepseekImportPreview.value = null
            } catch (e: Exception) {
                emitSnackbar(SnackbarEvent(e.localizedMessage ?: app.getString(R.string.unknown_error)))
                _deepseekImportPreview.value = null
            }
        }
    }

    fun clearDeepSeekImportState() {
        _deepseekImportPreview.value = null
        _deepseekImportProgress.value = null
        _deepseekImportResult.value = null
    }

    fun importDeepSeekChat(uri: Uri, strategy: ImportStrategy, selectedIds: Set<String>) {
        scope.launch(Dispatchers.IO) {
            try {
                _deepseekImportProgress.value = 0.2f
                importSizeError(uri)?.let {
                    _deepseekImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.deepseek_import_error_detail, it)))
                    return@launch
                }

                val importer = DeepSeekChatImporter()
                val parseResult = importer.extractAndParse { openImportStream(uri) }
                if (parseResult.isFailure) {
                    _deepseekImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.deepseek_import_error_detail, parseResult.exceptionOrNull()?.localizedMessage ?: app.getString(R.string.parse_error))))
                    return@launch
                }

                _deepseekImportProgress.value = 0.4f
                val parsed = parseResult.getOrThrow()
                val preview = importer.preview(parsed)
                val importData = importer.toImportFormat(parsed, selectedIds)

                if (preview.totalMessageCount == 0) {
                    _deepseekImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.deepseek_import_no_data)))
                    return@launch
                }

                _deepseekImportProgress.value = 0.6f

                val chatEntities = importData.conversations.map { ce ->
                    ChatEntity(ce.id, ce.title, ce.lastUpdated, dataChangedAt = 0L, selectedBranchesJson = ce.selectedBranchesJson, systemPromptId = ce.systemPromptId, modelId = ce.modelId)
                }
                if (strategy == ImportStrategy.REPLACE) {
                    val graph = planImportedLegacyMessages(importData.messages)
                    conversations.importExternalConversationGraph(
                        conversations = chatEntities,
                        runs = graph.runs,
                        messages = graph.messages,
                        replace = true,
                    )
                    _deepseekImportProgress.value = 0.8f
                    _deepseekImportResult.value = DeepSeekChatImporter.ImportResult(chatEntities.size, graph.messages.size)
                } else {
                    val existingConvIds = conversations.getAllConversationsList().map { it.id }.toSet()
                    val existingMsgIds = conversations.findExistingMessageIds(importData.messages.map { it.id }).toSet()
                    val newCh = chatEntities.filterNot { it.id in existingConvIds }
                    val newMessageDrafts = importData.messages.filterNot { it.id in existingMsgIds }
                    val graph = planImportedLegacyMessages(newMessageDrafts)
                    conversations.importExternalConversationGraph(
                        conversations = newCh,
                        runs = graph.runs,
                        messages = graph.messages,
                        replace = false,
                    )
                    _deepseekImportProgress.value = 0.8f
                    _deepseekImportResult.value = DeepSeekChatImporter.ImportResult(newCh.size, graph.messages.size)
                }
                onDataChanged()
            } catch (e: OutOfMemoryError) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.import_out_of_memory)))
            } catch (e: Exception) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.deepseek_import_error_detail, e.localizedMessage ?: "")))
            } finally {
                // Early exits (oversize, parse failure, no data) must not freeze the dialog at 40%.
                _deepseekImportProgress.value = null
            }
        }
    }

    fun previewQwenChat(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                importSizeError(uri)?.let {
                    emitSnackbar(SnackbarEvent(it)); _qwenImportPreview.value = null; return@launch
                }
                val importer = QwenChatImporter()
                val parseResult = importer.extractAndParse { openImportStream(uri) }
                if (parseResult.isSuccess) {
                    _qwenImportPreview.value = importer.preview(parseResult.getOrThrow())
                } else {
                    emitSnackbar(SnackbarEvent(parseResult.exceptionOrNull()?.localizedMessage ?: app.getString(R.string.parse_error)))
                    _qwenImportPreview.value = null
                }
            } catch (e: OutOfMemoryError) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.import_out_of_memory)))
                _qwenImportPreview.value = null
            } catch (e: Exception) {
                emitSnackbar(SnackbarEvent(e.localizedMessage ?: app.getString(R.string.unknown_error)))
                _qwenImportPreview.value = null
            }
        }
    }

    fun clearQwenImportState() {
        _qwenImportPreview.value = null
        _qwenImportProgress.value = null
        _qwenImportResult.value = null
    }

    fun importQwenChat(uri: Uri, strategy: ImportStrategy, selectedIds: Set<String>) {
        scope.launch(Dispatchers.IO) {
            try {
                _qwenImportProgress.value = 0.2f
                importSizeError(uri)?.let {
                    _qwenImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.qwen_import_error_detail, it)))
                    return@launch
                }

                val importer = QwenChatImporter()
                val parseResult = importer.extractAndParse { openImportStream(uri) }
                if (parseResult.isFailure) {
                    _qwenImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.qwen_import_error_detail, parseResult.exceptionOrNull()?.localizedMessage ?: app.getString(R.string.parse_error))))
                    return@launch
                }

                _qwenImportProgress.value = 0.4f
                val parsed = parseResult.getOrThrow()
                val preview = importer.preview(parsed)
                val importData = importer.toImportFormat(parsed, selectedIds)

                if (preview.totalMessageCount == 0) {
                    _qwenImportProgress.value = null
                    emitSnackbar(SnackbarEvent(app.getString(R.string.qwen_import_no_data)))
                    return@launch
                }

                _qwenImportProgress.value = 0.6f

                val chatEntities = importData.conversations.map { ce ->
                    ChatEntity(ce.id, ce.title, ce.lastUpdated, dataChangedAt = 0L, selectedBranchesJson = ce.selectedBranchesJson, systemPromptId = ce.systemPromptId, modelId = ce.modelId)
                }
                if (strategy == ImportStrategy.REPLACE) {
                    val graph = planImportedLegacyMessages(importData.messages)
                    conversations.importExternalConversationGraph(
                        conversations = chatEntities,
                        runs = graph.runs,
                        messages = graph.messages,
                        replace = true,
                    )
                    _qwenImportProgress.value = 0.8f
                    _qwenImportResult.value = QwenChatImporter.ImportResult(chatEntities.size, graph.messages.size)
                } else {
                    val existingConvIds = conversations.getAllConversationsList().map { it.id }.toSet()
                    val existingMsgIds = conversations.findExistingMessageIds(importData.messages.map { it.id }).toSet()
                    val newCh = chatEntities.filterNot { it.id in existingConvIds }
                    val newMessageDrafts = importData.messages.filterNot { it.id in existingMsgIds }
                    val graph = planImportedLegacyMessages(newMessageDrafts)
                    conversations.importExternalConversationGraph(
                        conversations = newCh,
                        runs = graph.runs,
                        messages = graph.messages,
                        replace = false,
                    )
                    _qwenImportProgress.value = 0.8f
                    _qwenImportResult.value = QwenChatImporter.ImportResult(newCh.size, graph.messages.size)
                }
                onDataChanged()
            } catch (e: OutOfMemoryError) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.import_out_of_memory)))
            } catch (e: Exception) {
                emitSnackbar(SnackbarEvent(app.getString(R.string.qwen_import_error_detail, e.localizedMessage ?: "")))
            } finally {
                // Early exits (oversize, parse failure, no data) must not freeze the dialog at 40%.
                _qwenImportProgress.value = null
            }
        }
    }

    fun importData(uri: Uri, decisions: Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>) {
        _importProgress.value = 0f
        scope.launch(Dispatchers.IO) {
            try {
                val importer = DataImporter(
                    app,
                    database,
                    chatDao,
                    settingsManager,
                    memoryManager,
                    skillManager,
                    conversationSettingsTransfers,
                )
                suspend fun performImport() = importer.import(uri, decisions) { progress ->
                    _importProgress.value = progress
                }
                val importsConversationGraph = decisions[DataExporter.ExportCategory.CONVERSATIONS]
                    ?.let { it != DataImporter.ImportStrategy.SKIP } == true
                val result = if (importsConversationGraph) {
                    try {
                        automationExecutionGate.withExclusiveImport(
                            onQuiescing = quiesceAutomation,
                        ) { performImport() }
                    } finally {
                        resumeAutomationScheduling()
                    }
                } else {
                    performImport()
                }
                _importProgress.value = null
                _importManifest.value = null
                _importPreview.value = null
                onDataChanged()

                val parts = mutableListOf<String>()
                if (result.conversationsImported > 0) parts.add("${result.conversationsImported} conversations")
                if (result.tasksImported > 0) parts.add("${result.tasksImported} tasks")
                if (result.loopsImported > 0) parts.add("${result.loopsImported} loops")
                if (result.memoriesImported > 0) parts.add("${result.memoriesImported} memories")
                if (result.systemPromptsImported > 0) parts.add("${result.systemPromptsImported} prompts")
                if (result.settingsImported) parts.add("settings")
                if (result.apiKeysImported) parts.add("API keys")

                val summary = if (result.errors.isEmpty()) {
                    app.getString(R.string.import_success, parts.joinToString(", "))
                } else {
                    app.getString(R.string.import_failed,
                        "${result.errors.size} error(s): ${result.errors.first()}")
                }
                emitSnackbar(SnackbarEvent(summary))
            } catch (e: Exception) {
                _importProgress.value = null
                emitSnackbar(SnackbarEvent(app.getString(R.string.import_failed, e.localizedMessage ?: "")))
            }
        }
    }
}
