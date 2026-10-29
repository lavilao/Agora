package com.newoether.agora.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.ui.components.rememberUnsavedChangesBack
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LocalChatModelEditor(
    initial: LocalChatModelConfig?,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    showDocFab: Boolean,
) {
    val start = remember(initial?.id) { LocalChatModelDraft.from(initial) }
    var draft by remember(initial?.id) { mutableStateOf(start) }
    val imports = remember(initial?.id) { LocalChatModelImports() }
    var closed by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<LocalChatImportTarget?>(null) }
    var importing by remember { mutableStateOf<LocalChatImportTarget?>(null) }
    var showGgufError by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LocalChatModelDraftError?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = picking != null || importing != null

    DisposableEffect(imports) {
        onDispose {
            closed = true
            imports.close()
        }
    }

    val leave = {
        closed = true
        imports.close()
        onBack()
    }
    val guardedBack = rememberUnsavedChangesBack(
        hasUnsavedChanges = draft != start || busy,
        onBack = leave,
    )
    BackHandler(enabled = !closed) { guardedBack() }

    fun importFile(uri: Uri, target: LocalChatImportTarget) {
        importing = target
        scope.launch {
            try {
                val file = importLocalChatGguf(context, uri, target.prefix, imports)
                if (!closed) {
                    if (file == null) {
                        showGgufError = true
                    } else {
                        when (target) {
                            LocalChatImportTarget.PROJECTOR -> {
                                imports.discard(draft.projectorPath)
                                draft = draft.copy(projectorPath = file.path)
                            }
                            LocalChatImportTarget.DRAFT -> {
                                imports.discard(draft.draftPath)
                                draft = draft.copy(draftPath = file.path)
                            }
                            LocalChatImportTarget.MODEL -> {
                                imports.discard(draft.modelPath)
                                draft = draft.copy(modelPath = file.path)
                            }
                        }
                        error = null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DebugLog.e("LocalChatModelEditor", "GGUF import failed", failure)
                if (!closed) showGgufError = true
            } finally {
                if (!closed) importing = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = picking
        picking = null
        if (!closed && target != null && uri != null) importFile(uri, target)
    }
    fun pick(target: LocalChatImportTarget) {
        if (closed || picking != null || importing != null) return
        picking = target
        picker.launch(arrayOf("*/*"))
    }

    fun save() {
        if (closed || picking != null || importing != null) return
        error = draft.validate { id ->
            viewModel.modelManager.isLocalModelIdTaken(id, excludeId = initial?.id)
        }
        if (error != null) return
        val config = draft.toConfig(initial)
        if (initial == null) {
            viewModel.modelManager.addLocalChatModel(config)
        } else {
            viewModel.modelManager.updateLocalChatModel(
                uuid = initial.id,
                newModelId = config.modelId,
                newAlias = config.alias,
                nCtx = config.nCtx,
                temperature = config.temperature,
                topP = config.topP,
                maxTokens = config.maxTokens,
                mmprojPath = config.mmprojPath,
                draftModelPath = config.draftModelPath,
            )
        }
        // ModelManager now owns these paths; disposal must never unlink a saved model.
        imports.transfer(config.localFilePath, config.mmprojPath, config.draftModelPath)
        leave()
    }

    val errorText = error?.let { stringResource(it.messageResource()) }
    val idError = error in setOf(
        LocalChatModelDraftError.ID_REQUIRED,
        LocalChatModelDraftError.ID_INVALID,
        LocalChatModelDraftError.ID_TAKEN,
    )
    fun update(next: LocalChatModelDraft) {
        draft = next
        error = null
    }

    CollapsingSettingsScaffold(
        title = stringResource(
            if (initial == null) R.string.add_local_chat_model else R.string.embedding_edit_model,
        ),
        onBack = guardedBack,
        actions = {
            IconButton(onClick = ::save, enabled = !busy && !closed) {
                Icon(Icons.Default.Save, stringResource(R.string.save))
            }
        },
        floatingActionButton = { if (showDocFab) DocumentationFab("provider.md") },
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.embedding_general),
                items = buildList {
                    if (initial == null) {
                        add {
                            ModelImportItem(
                                title = stringResource(R.string.local_chat_import_title),
                                filePath = draft.modelPath,
                                description = stringResource(R.string.local_chat_import_desc),
                                icon = Icons.Default.AutoAwesome,
                                importing = importing == LocalChatImportTarget.MODEL,
                                enabled = !busy && !closed,
                                onClick = { pick(LocalChatImportTarget.MODEL) },
                            )
                        }
                    }
                    add {
                        SettingsIconContent(icon = Icons.Default.Tag) {
                            McpLabeledField(
                                label = stringResource(R.string.model_id_label),
                                value = draft.modelId,
                                onValueChange = { update(draft.copy(modelId = it)) },
                                isError = idError,
                                supportingText = errorText.takeIf { idError },
                            )
                        }
                    }
                    add {
                        SettingsIconContent(icon = Icons.Default.Label) {
                            McpLabeledField(
                                label = stringResource(R.string.model_alias_label),
                                value = draft.alias,
                                onValueChange = { update(draft.copy(alias = it)) },
                            )
                        }
                    }
                    if (!draft.isCactus) {
                        add {
                            ModelImportItem(
                                title = stringResource(R.string.local_projector_title),
                                filePath = draft.projectorPath,
                                description = stringResource(R.string.local_projector_desc),
                                icon = Icons.Default.Visibility,
                                emptyIcon = Icons.Default.VisibilityOff,
                                importedEffect = stringResource(R.string.local_projector_enabled),
                                importing = importing == LocalChatImportTarget.PROJECTOR,
                                enabled = !busy && !closed,
                                onClick = { pick(LocalChatImportTarget.PROJECTOR) },
                                onRemove = {
                                    imports.discard(draft.projectorPath)
                                    update(draft.copy(projectorPath = ""))
                                },
                            )
                        }
                        add {
                            ModelImportItem(
                                title = stringResource(R.string.local_draft_title),
                                filePath = draft.draftPath,
                                description = stringResource(R.string.local_draft_desc),
                                icon = Icons.Default.Memory,
                                importedEffect = stringResource(R.string.local_draft_enabled),
                                importing = importing == LocalChatImportTarget.DRAFT,
                                enabled = !busy && !closed,
                                onClick = { pick(LocalChatImportTarget.DRAFT) },
                                onRemove = {
                                    imports.discard(draft.draftPath)
                                    update(draft.copy(draftPath = ""))
                                },
                            )
                        }
                    }
                },
            )
            SettingsGroup(
                title = stringResource(R.string.advanced_title),
                items = buildList {
                    if (!draft.isCactus) {
                        add {
                            SettingsIconContent(icon = Icons.Default.Memory) {
                                McpLabeledField(
                                    label = stringResource(R.string.local_ctx_size),
                                    value = draft.nCtx,
                                    onValueChange = { update(draft.copy(nCtx = it)) },
                                    keyboardType = KeyboardType.Number,
                                    isError = error == LocalChatModelDraftError.CONTEXT,
                                )
                            }
                        }
                    }
                    add {
                        SettingsIconContent(icon = Icons.Default.Tune) {
                            McpLabeledField(
                                label = stringResource(R.string.local_temperature),
                                value = draft.temperature,
                                onValueChange = { update(draft.copy(temperature = it)) },
                                keyboardType = KeyboardType.Decimal,
                                isError = error == LocalChatModelDraftError.TEMPERATURE,
                            )
                        }
                    }
                    add {
                        SettingsIconContent(icon = Icons.Default.Tune) {
                            McpLabeledField(
                                label = stringResource(R.string.local_top_p),
                                value = draft.topP,
                                onValueChange = { update(draft.copy(topP = it)) },
                                keyboardType = KeyboardType.Decimal,
                                isError = error == LocalChatModelDraftError.TOP_P,
                            )
                        }
                    }
                    add {
                        SettingsIconContent(icon = Icons.Default.Tune) {
                            McpLabeledField(
                                label = stringResource(R.string.local_max_tokens),
                                value = draft.maxTokens,
                                onValueChange = { update(draft.copy(maxTokens = it)) },
                                keyboardType = KeyboardType.Number,
                                isError = error == LocalChatModelDraftError.MAX_TOKENS ||
                                    error == LocalChatModelDraftError.OUTPUT_EXCEEDS_CONTEXT,
                            )
                        }
                    }
                },
            )
            if (errorText != null && !idError) {
                Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (showDocFab) Spacer(Modifier.height(80.dp))
    }

    if (showGgufError && !closed) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showGgufError = false },
            title = { Text(stringResource(R.string.import_invalid_gguf_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.import_invalid_gguf_desc)) },
            confirmButton = {
                TextButton(onClick = { showGgufError = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

private fun LocalChatModelDraftError.messageResource(): Int = when (this) {
    LocalChatModelDraftError.ID_REQUIRED -> R.string.local_model_error_id_required
    LocalChatModelDraftError.ID_INVALID -> R.string.local_model_error_id_invalid
    LocalChatModelDraftError.ID_TAKEN -> R.string.local_model_error_id_taken
    LocalChatModelDraftError.MODEL_REQUIRED -> R.string.local_model_error_file_required
    LocalChatModelDraftError.CONTEXT -> R.string.local_model_error_context
    LocalChatModelDraftError.TEMPERATURE -> R.string.local_model_error_temperature
    LocalChatModelDraftError.TOP_P -> R.string.local_model_error_top_p
    LocalChatModelDraftError.MAX_TOKENS -> R.string.local_model_error_max_tokens
    LocalChatModelDraftError.OUTPUT_EXCEEDS_CONTEXT -> R.string.local_model_error_output
}
