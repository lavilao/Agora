package com.newoether.agora.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add / edit dialogs for local chat models, extracted from the provider detail page. Each
 * dialog picks the model file's companions: the vision projector (mmproj) and, for
 * speculative decoding, a small draft GGUF whose guesses the main model verifies in one
 * batch. Picked files are copied into app storage and the previous copy is deleted when
 * replaced or removed.
 */
@Composable
internal fun LocalChatModelDialogs(
    viewModel: ChatViewModel,
    showAddModelDialog: Boolean,
    copiedFilePath: String?,
    editModel: LocalChatModelConfig?,
    onAddDismissed: () -> Unit,
    onEditDismissed: () -> Unit,
) {
    if (!showAddModelDialog && editModel == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun deleteFilesAsync(vararg paths: String?) {
        val targets = paths.filterNotNull().filter(String::isNotBlank)
        if (targets.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                targets.forEach { java.io.File(it).delete() }
            }
        }
    }

    fun copyPickedGguf(uri: android.net.Uri, prefix: String, onCopied: (String?) -> Unit) {
        scope.launch {
            val importedPath = withContext(Dispatchers.IO) {
                try {
                    val dest = java.io.File(
                        context.filesDir,
                        "${prefix}_${java.util.UUID.randomUUID()}.gguf",
                    )
                    val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                        true
                    } == true
                    if (copied) dest.absolutePath else null
                } catch (e: Exception) {
                    DebugLog.e("ProviderDetail", "$prefix import", e)
                    null
                }
            }
            onCopied(importedPath)
        }
    }

    var mmprojPickedUri by remember { mutableStateOf<String?>(null) }
    var draftPickedUri by remember { mutableStateOf<String?>(null) }
    val mmprojLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyPickedGguf(uri, "mmproj") { if (it != null) mmprojPickedUri = it }
    }
    val draftLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyPickedGguf(uri, "draft_model") { if (it != null) draftPickedUri = it }
    }

    if (showAddModelDialog && copiedFilePath != null) {
        AddLocalModelDialog(
            viewModel = viewModel,
            copiedFilePath = copiedFilePath,
            mmprojPickedUri = mmprojPickedUri,
            draftPickedUri = draftPickedUri,
            onMmprojConsumed = { mmprojPickedUri = null },
            onDraftConsumed = { draftPickedUri = null },
            launchMmprojPicker = { mmprojLauncher.launch(arrayOf("*/*")) },
            launchDraftPicker = { draftLauncher.launch(arrayOf("*/*")) },
            deleteFilesAsync = ::deleteFilesAsync,
            onDismissed = onAddDismissed,
        )
    }
    editModel?.let { model ->
        EditLocalModelDialog(
            viewModel = viewModel,
            model = model,
            mmprojPickedUri = mmprojPickedUri,
            draftPickedUri = draftPickedUri,
            onMmprojConsumed = { mmprojPickedUri = null },
            onDraftConsumed = { draftPickedUri = null },
            launchMmprojPicker = { mmprojLauncher.launch(arrayOf("*/*")) },
            launchDraftPicker = { draftLauncher.launch(arrayOf("*/*")) },
            deleteFilesAsync = ::deleteFilesAsync,
            onDismissed = onEditDismissed,
        )
    }
}

@Composable
private fun ModelFilePickerRow(
    label: String,
    path: String,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val hasFile = path.isNotBlank()
        OutlinedButton(
            onClick = onPick,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (hasFile) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            ),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (hasFile) path.split("/").lastOrNull() ?: "" else label,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasFile) {
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AddLocalModelDialog(
    viewModel: ChatViewModel,
    copiedFilePath: String,
    mmprojPickedUri: String?,
    draftPickedUri: String?,
    onMmprojConsumed: () -> Unit,
    onDraftConsumed: () -> Unit,
    launchMmprojPicker: () -> Unit,
    launchDraftPicker: () -> Unit,
    deleteFilesAsync: (Array<out String?>) -> Unit,
    onDismissed: () -> Unit,
) {
    var modelId by remember { mutableStateOf("") }; var modelAlias by remember { mutableStateOf("") }
    var addMmprojPath by remember { mutableStateOf("") }; var addDraftPath by remember { mutableStateOf("") }
    var nCtx by remember { mutableStateOf("16384") }; var temperature by remember { mutableStateOf("0.7") }; var topP by remember { mutableStateOf("0.9") }; var maxTokens by remember { mutableStateOf("1024") }
    var idError by remember { mutableStateOf<String?>(null) }; var formError by remember { mutableStateOf<String?>(null) }
    val idRegex = remember { Regex("^[a-z0-9._-]+\$") }
    LaunchedEffect(mmprojPickedUri) {
        mmprojPickedUri?.let { newPath ->
            deleteFilesAsync(arrayOf(addMmprojPath))
            addMmprojPath = newPath
            onMmprojConsumed()
        }
    }
    LaunchedEffect(draftPickedUri) {
        draftPickedUri?.let { newPath ->
            deleteFilesAsync(arrayOf(addDraftPath))
            addDraftPath = newPath
            onDraftConsumed()
        }
    }
    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = {
            deleteFilesAsync(arrayOf(copiedFilePath, addMmprojPath, addDraftPath))
            onDismissed()
        },
        title = { Text(stringResource(R.string.add_local_chat_model), fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = modelId, onValueChange = { modelId = it; idError = null }, label = { Text(stringResource(R.string.model_id_label)) }, supportingText = if (idError != null) {{ Text(idError!!, color = MaterialTheme.colorScheme.error) }} else null, isError = idError != null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = modelAlias, onValueChange = { modelAlias = it }, label = { Text(stringResource(R.string.model_alias_label)) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = nCtx, onValueChange = { nCtx = it }, label = { Text(stringResource(R.string.local_ctx_size)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            ModelFilePickerRow(
                label = stringResource(R.string.local_mmproj_path_label),
                path = addMmprojPath,
                onPick = launchMmprojPicker,
                onRemove = {
                    val removedPath = addMmprojPath
                    addMmprojPath = ""
                    deleteFilesAsync(arrayOf(removedPath))
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            ModelFilePickerRow(
                label = stringResource(R.string.local_draft_model_path_label),
                path = addDraftPath,
                onPick = launchDraftPicker,
                onRemove = {
                    val removedPath = addDraftPath
                    addDraftPath = ""
                    deleteFilesAsync(arrayOf(removedPath))
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = temperature, onValueChange = { temperature = it }, label = { Text(stringResource(R.string.local_temperature)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = topP, onValueChange = { topP = it }, label = { Text(stringResource(R.string.local_top_p)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, label = { Text(stringResource(R.string.local_max_tokens)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            formError?.let { Spacer(modifier = Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }},
        confirmButton = { TextButton(onClick = {
            val id = modelId.trim(); idError = null; formError = null
            if (id.isBlank()) { idError = "ID is required"; return@TextButton }
            if (!idRegex.matches(id)) { idError = "Only a-z, 0-9, . _ - allowed"; return@TextButton }
            if (viewModel.modelManager.isLocalModelIdTaken(id)) { idError = "Already in use"; return@TextButton }
            val n = nCtx.toIntOrNull()?.takeIf { it > 0 } ?: run { formError = "Context size must be positive"; return@TextButton }
            val t = temperature.toFloatOrNull()?.takeIf { it in 0f..2f } ?: run { formError = "Temperature must be 0–2"; return@TextButton }
            val p = topP.toFloatOrNull()?.takeIf { it in 0f..1f } ?: run { formError = "Top P must be 0–1"; return@TextButton }
            val m = maxTokens.toIntOrNull()?.takeIf { it > 0 } ?: run { formError = "Max tokens must be positive"; return@TextButton }
            if (m > n) { formError = "Max tokens must not exceed context size"; return@TextButton }
            viewModel.modelManager.addLocalChatModel(LocalChatModelConfig(modelId = id, alias = modelAlias.ifBlank { id }, localFilePath = copiedFilePath, mmprojPath = addMmprojPath.trim(), draftModelPath = addDraftPath.trim(), nCtx = n, temperature = t, topP = p, maxTokens = m))
            onDismissed()
        }) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = {
            deleteFilesAsync(arrayOf(copiedFilePath, addMmprojPath, addDraftPath))
            onDismissed()
        }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun EditLocalModelDialog(
    viewModel: ChatViewModel,
    model: LocalChatModelConfig,
    mmprojPickedUri: String?,
    draftPickedUri: String?,
    onMmprojConsumed: () -> Unit,
    onDraftConsumed: () -> Unit,
    launchMmprojPicker: () -> Unit,
    launchDraftPicker: () -> Unit,
    deleteFilesAsync: (Array<out String?>) -> Unit,
    onDismissed: () -> Unit,
) {
    var editModelId by remember { mutableStateOf(model.modelId) }; var editAlias by remember { mutableStateOf(model.alias) }
    var editMmprojPath by remember { mutableStateOf(model.mmprojPath) }; var editDraftPath by remember { mutableStateOf(model.draftModelPath) }
    var editNCtx by remember { mutableStateOf(model.nCtx.toString()) }; var editTemp by remember { mutableStateOf(model.temperature.toString()) }; var editTopP by remember { mutableStateOf(model.topP.toString()) }; var editMaxTokens by remember { mutableStateOf(model.maxTokens.toString()) }
    var editIdError by remember { mutableStateOf<String?>(null) }; var editFormError by remember { mutableStateOf<String?>(null) }
    val idRegex = remember { Regex("^[a-z0-9._-]+\$") }
    LaunchedEffect(mmprojPickedUri) {
        mmprojPickedUri?.let { newPath ->
            if (editMmprojPath != model.mmprojPath) deleteFilesAsync(arrayOf(editMmprojPath))
            editMmprojPath = newPath
            onMmprojConsumed()
        }
    }
    LaunchedEffect(draftPickedUri) {
        draftPickedUri?.let { newPath ->
            if (editDraftPath != model.draftModelPath) deleteFilesAsync(arrayOf(editDraftPath))
            editDraftPath = newPath
            onDraftConsumed()
        }
    }
    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = {
            if (editMmprojPath != model.mmprojPath) deleteFilesAsync(arrayOf(editMmprojPath))
            if (editDraftPath != model.draftModelPath) deleteFilesAsync(arrayOf(editDraftPath))
            onDismissed()
        },
        title = { Text(stringResource(R.string.edit), fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = editModelId, onValueChange = { editModelId = it; editIdError = null }, label = { Text(stringResource(R.string.model_id_label)) }, supportingText = if (editIdError != null) {{ Text(editIdError!!, color = MaterialTheme.colorScheme.error) }} else null, isError = editIdError != null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = editAlias, onValueChange = { editAlias = it }, label = { Text(stringResource(R.string.model_alias_label)) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = editNCtx, onValueChange = { editNCtx = it }, label = { Text(stringResource(R.string.local_ctx_size)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            ModelFilePickerRow(
                label = stringResource(R.string.local_mmproj_path_label),
                path = editMmprojPath,
                onPick = launchMmprojPicker,
                onRemove = {
                    val removedPath = editMmprojPath
                    editMmprojPath = ""
                    if (removedPath != model.mmprojPath) deleteFilesAsync(arrayOf(removedPath))
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            ModelFilePickerRow(
                label = stringResource(R.string.local_draft_model_path_label),
                path = editDraftPath,
                onPick = launchDraftPicker,
                onRemove = {
                    val removedPath = editDraftPath
                    editDraftPath = ""
                    if (removedPath != model.draftModelPath) deleteFilesAsync(arrayOf(removedPath))
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = editTemp, onValueChange = { editTemp = it }, label = { Text(stringResource(R.string.local_temperature)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = editTopP, onValueChange = { editTopP = it }, label = { Text(stringResource(R.string.local_top_p)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = editMaxTokens, onValueChange = { editMaxTokens = it }, label = { Text(stringResource(R.string.local_max_tokens)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            editFormError?.let { Spacer(modifier = Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }},
        confirmButton = { TextButton(onClick = {
            val id = editModelId.trim(); editIdError = null; editFormError = null
            if (id.isBlank()) { editIdError = "ID is required"; return@TextButton }
            if (!idRegex.matches(id)) { editIdError = "Only a-z, 0-9, . _ - allowed"; return@TextButton }
            if (viewModel.modelManager.isLocalModelIdTaken(id, excludeId = model.id)) { editIdError = "Already in use"; return@TextButton }
            val n = editNCtx.toIntOrNull()?.takeIf { it > 0 } ?: run { editFormError = "Context size must be positive"; return@TextButton }
            val t = editTemp.toFloatOrNull()?.takeIf { it in 0f..2f } ?: run { editFormError = "Temperature must be 0–2"; return@TextButton }
            val p = editTopP.toFloatOrNull()?.takeIf { it in 0f..1f } ?: run { editFormError = "Top P must be 0–1"; return@TextButton }
            val m = editMaxTokens.toIntOrNull()?.takeIf { it > 0 } ?: run { editFormError = "Max tokens must be positive"; return@TextButton }
            if (m > n) { editFormError = "Max tokens must not exceed context size"; return@TextButton }
            viewModel.modelManager.updateLocalChatModel(model.id, id, editAlias.ifBlank { id }, n, t, p, m, mmprojPath = editMmprojPath.trim(), draftModelPath = editDraftPath.trim())
            onDismissed()
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = {
            if (editMmprojPath != model.mmprojPath) deleteFilesAsync(arrayOf(editMmprojPath))
            if (editDraftPath != model.draftModelPath) deleteFilesAsync(arrayOf(editDraftPath))
            onDismissed()
        }) { Text(stringResource(R.string.cancel)) } }
    )
}
