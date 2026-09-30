package com.newoether.agora.ui.settings.datacontrol

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.DataImporter
import com.newoether.agora.data.DeepSeekChatImporter
import com.newoether.agora.data.GptChatImporter
import com.newoether.agora.ui.settings.PillTabSwitcher

/**
 * Generic "replace everything?" confirmation shared by the Claude, GPT and
 * DeepSeek third-party import flows. The caller keeps ownership of the pending
 * import (provider + selected ids) and dispatches the actual import from
 * [onConfirm]; [onDismiss] reopens the source preview dialog.
 */
@Composable
internal fun ExternalReplaceConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.external_import_replace_confirm_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = { Text(stringResource(R.string.external_import_replace_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.external_import_replace_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * DeepSeek import preview: merge/replace strategy, per-conversation selection
 * and the attachment notice, mirroring the Claude/GPT preview dialogs.
 * Selection and strategy state is local to the dialog and reset per preview.
 */
@Composable
internal fun DeepSeekImportPreviewDialog(
    preview: DeepSeekChatImporter.ImportPreview,
    onImport: (DataImporter.ImportStrategy, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedIds by remember(preview) {
        mutableStateOf<Set<String>>(preview.conversations.mapTo(mutableSetOf()) { it.uuid })
    }
    var strategy by remember(preview) { mutableStateOf(DataImporter.ImportStrategy.MERGE) }

    val allIds = preview.conversations.map { it.uuid }.toSet()
    val allSelected = selectedIds.size == allIds.size
    val selectedConvCount = preview.conversations.count { it.uuid in selectedIds }
    val selectedMsgCount = preview.conversations
        .filter { it.uuid in selectedIds }
        .sumOf { it.messageCount }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deepseek_import_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    stringResource(R.string.claude_import_strategy),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                PillTabSwitcher(
                    tabs = listOf(
                        stringResource(R.string.import_strategy_merge),
                        stringResource(R.string.import_strategy_replace),
                    ),
                    selectedIndex = if (strategy == DataImporter.ImportStrategy.MERGE) 0 else 1,
                    onSelect = { index ->
                        strategy = if (index == 0) {
                            DataImporter.ImportStrategy.MERGE
                        } else {
                            DataImporter.ImportStrategy.REPLACE
                        }
                    },
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "$selectedConvCount ${stringResource(R.string.deepseek_import_conversations)}, " +
                        "$selectedMsgCount ${stringResource(R.string.deepseek_import_messages)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (preview.hasAttachments) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.deepseek_import_attachments_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selectedIds = if (allSelected) emptySet() else allIds
                    }) {
                        Text(
                            if (allSelected) stringResource(R.string.deselect_all)
                            else stringResource(R.string.select_all),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(preview.conversations.size) { index ->
                        val conv = preview.conversations[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (conv.uuid in selectedIds) {
                                        selectedIds - conv.uuid
                                    } else {
                                        selectedIds + conv.uuid
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = conv.uuid in selectedIds,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) {
                                        selectedIds + conv.uuid
                                    } else {
                                        selectedIds - conv.uuid
                                    }
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    conv.title.ifEmpty { "Untitled" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    "${conv.messageCount} messages",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(strategy, selectedIds) },
                enabled = selectedIds.isNotEmpty()
            ) {
                Text(stringResource(R.string.deepseek_import_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * GPT import preview: merge/replace strategy and per-conversation selection,
 * identical in shape to the Claude preview dialog. Selection and strategy
 * state is local to the dialog and reset per preview.
 */
@Composable
internal fun GptImportPreviewDialog(
    preview: GptChatImporter.ImportPreview,
    onImport: (DataImporter.ImportStrategy, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedIds by remember(preview) {
        mutableStateOf<Set<String>>(preview.conversations.mapTo(mutableSetOf()) { it.uuid })
    }
    var strategy by remember(preview) { mutableStateOf(DataImporter.ImportStrategy.MERGE) }

    val allIds = preview.conversations.map { it.uuid }.toSet()
    val allSelected = selectedIds.size == allIds.size
    val selectedConvCount = preview.conversations.count { it.uuid in selectedIds }
    val selectedMsgCount = preview.conversations
        .filter { it.uuid in selectedIds }
        .sumOf { it.messageCount }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gpt_import_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    stringResource(R.string.claude_import_strategy),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                PillTabSwitcher(
                    tabs = listOf(
                        stringResource(R.string.import_strategy_merge),
                        stringResource(R.string.import_strategy_replace),
                    ),
                    selectedIndex = if (strategy == DataImporter.ImportStrategy.MERGE) 0 else 1,
                    onSelect = { index ->
                        strategy = if (index == 0) {
                            DataImporter.ImportStrategy.MERGE
                        } else {
                            DataImporter.ImportStrategy.REPLACE
                        }
                    },
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "$selectedConvCount ${stringResource(R.string.gpt_import_conversations)}, " +
                        "$selectedMsgCount ${stringResource(R.string.gpt_import_messages)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selectedIds = if (allSelected) emptySet() else allIds
                    }) {
                        Text(
                            if (allSelected) stringResource(R.string.deselect_all)
                            else stringResource(R.string.select_all),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(preview.conversations.size) { index ->
                        val conv = preview.conversations[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (conv.uuid in selectedIds) {
                                        selectedIds - conv.uuid
                                    } else {
                                        selectedIds + conv.uuid
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = conv.uuid in selectedIds,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) {
                                        selectedIds + conv.uuid
                                    } else {
                                        selectedIds - conv.uuid
                                    }
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    conv.title.ifEmpty { "Untitled" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    "${conv.messageCount} messages",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(strategy, selectedIds) },
                enabled = selectedIds.isNotEmpty()
            ) {
                Text(stringResource(R.string.gpt_import_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Shown after a DeepSeek import completes; lists per-conversation errors if any. */
@Composable
internal fun DeepSeekImportSuccessDialog(
    result: DeepSeekChatImporter.ImportResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deepseek_import_success), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(stringResource(R.string.deepseek_import_success_detail, result.conversationsImported, result.messagesImported))
                if (result.errors.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Errors: ${result.errors.joinToString(", ")}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.provider_close))
            }
        }
    )
}
