package com.newoether.agora.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.CactusEngine
import com.newoether.agora.data.CactusBundleManager
import com.newoether.agora.data.CactusModelCatalog
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Catalog, download and import surface for the Cactus alternative engine.
 *
 * On arm64 the catalog lists model families that publish prebuilt "-cqN"
 * bundles for the vendored runtime revision; downloads stream from
 * HuggingFace with checksum verification, and bundles can also be imported
 * from a .zip archive or a folder produced by `cactus convert` on a computer.
 * On 32-bit phones the catalog offers the single-file Needle 3 model (.cact)
 * that the prebuilt runtime packaged in those builds runs. Where no engine is
 * packaged at all (emulator builds) the whole surface explains the
 * requirement instead of failing silently.
 */
@Composable
internal fun CactusModelDialogs(
    viewModel: ChatViewModel,
    showCactusCatalog: Boolean,
    onDismissed: () -> Unit,
) {
    if (!showCactusCatalog) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { CactusBundleManager(context.applicationContext) }
    val backend = remember {
        runCatching {
            if (CactusEngine.isAvailable(context.applicationInfo.nativeLibraryDir)) {
                CactusEngine.backendKind()
            } else {
                null
            }
        }.getOrNull()
    }
    val engineAvailable = backend != null
    val catalogEntries = remember(backend) {
        CactusModelCatalog.entriesForBackend(backend)
    }
    val isNeedle = backend == CactusEngine.BACKEND_NEEDLE

    var installedDirs by remember { mutableStateOf(manager.installedBundleDirNames()) }
    var installedActs by remember { mutableStateOf(manager.installedActFilenames()) }
    var activeDownload by remember { mutableStateOf<ActiveDownload?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pendingBundle by remember { mutableStateOf<PendingCactusModel?>(null) }

    fun startTransfer(block: suspend () -> Unit) {
        downloadJob = scope.launch { block() }
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && activeDownload == null) {
            activeDownload = ActiveDownload(label = "import")
            startTransfer {
                try {
                    val dir = manager.importZip(uri) { bytes, _ ->
                        activeDownload = activeDownload?.copy(bytes = bytes)
                    }
                    pendingBundle = PendingCactusModel.fromDirectory(dir)
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        DebugLog.e("CactusCatalog", "zip import failed", e)
                        errorText = e.message
                    }
                } finally {
                    activeDownload = null
                    installedDirs = manager.installedBundleDirNames()
                }
            }
        }
    }

    val actLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && activeDownload == null) {
            activeDownload = ActiveDownload(label = "import")
            startTransfer {
                try {
                    val file = manager.importActFile(uri) { bytes, _ ->
                        activeDownload = activeDownload?.copy(bytes = bytes)
                    }
                    pendingBundle = PendingCactusModel.fromFile(file)
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        DebugLog.e("CactusCatalog", ".cact import failed", e)
                        errorText = e.message
                    }
                } finally {
                    activeDownload = null
                    installedActs = manager.installedActFilenames()
                }
            }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && activeDownload == null) {
            activeDownload = ActiveDownload(label = "import")
            startTransfer {
                try {
                    val dir = manager.importDirectory(uri)
                    pendingBundle = PendingCactusModel.fromDirectory(dir)
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        DebugLog.e("CactusCatalog", "folder import failed", e)
                        errorText = e.message
                    }
                } finally {
                    activeDownload = null
                    installedDirs = manager.installedBundleDirNames()
                }
            }
        }
    }

    pendingBundle?.let { pending ->
        AddCactusModelDialog(
            viewModel = viewModel,
            model = pending,
            onDismissed = {
                if (it) {
                    manager.deleteBundle(pending.path.absolutePath)
                }
                pendingBundle = null
                installedDirs = manager.installedBundleDirNames()
                installedActs = manager.installedActFilenames()
            },
        )
        return
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { if (activeDownload == null) onDismissed() },
        title = { Text(stringResource(R.string.cactus_add_model_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!engineAvailable) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.cactus_requires_64bit),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                } else if (isNeedle) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.cactus_needle_32bit_note),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                // A speech model is not registered as a chat model, so the only
                // place to remove it from is the row that installed it.
                fun deleteSpeechModel(entry: CactusModelCatalog.Entry) {
                    val file = manager.actFile(entry, entry.defaultVariant)
                    manager.deleteBundle(file.absolutePath)
                    installedActs = manager.installedActFilenames()
                }
                catalogEntries.forEach { entry ->
                    CactusCatalogEntryRow(
                        entry = entry,
                        engineAvailable = engineAvailable,
                        installedKeys = if (entry.isActFile) installedActs else installedDirs,
                        activeDownload = activeDownload,
                        onDownload = { variant ->
                            if (activeDownload == null) {
                                val key = if (entry.isActFile) {
                                    variant.filename
                                } else {
                                    CactusModelCatalog.bundleDirName(entry, variant)
                                }
                                activeDownload = ActiveDownload(
                                    key = key,
                                    totalBytes = variant.sizeBytes,
                                )
                                startTransfer {
                                    try {
                                        if (entry.isActFile) {
                                            val file = manager.downloadAct(entry, variant) { bytes, _ ->
                                                activeDownload = activeDownload?.copy(bytes = bytes)
                                            }
                                            if (entry.isSpeech) {
                                                // Whistle is the composer's dictation model,
                                                // not a chat model: nothing to register, the
                                                // microphone button picks the file up.
                                                DebugLog.i(
                                                    "CactusCatalog",
                                                    "Speech model installed: ${file.name}",
                                                )
                                            } else {
                                                pendingBundle = PendingCactusModel(
                                                    path = file,
                                                    suggestedModelId = file.name.removeSuffix(".cact"),
                                                    suggestedAlias = suggestedAlias(entry, variant),
                                                )
                                            }
                                        } else {
                                            val dir = manager.download(entry, variant) { bytes, _ ->
                                                activeDownload = activeDownload?.copy(bytes = bytes)
                                            }
                                            pendingBundle = PendingCactusModel(
                                                path = dir,
                                                suggestedModelId =
                                                    CactusModelCatalog.suggestedModelId(entry, variant),
                                                suggestedAlias = suggestedAlias(entry, variant),
                                            )
                                        }
                                    } catch (e: Exception) {
                                        if (e !is kotlinx.coroutines.CancellationException) {
                                            DebugLog.e("CactusCatalog", "download failed", e)
                                            errorText = e.message
                                        }
                                    } finally {
                                        activeDownload = null
                                        installedDirs = manager.installedBundleDirNames()
                                        installedActs = manager.installedActFilenames()
                                    }
                                }
                            }
                        },
                        onDeleteSpeech = ::deleteSpeechModel,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isNeedle) {
                        OutlinedButton(
                            onClick = { actLauncher.launch(arrayOf("*/*")) },
                            enabled = engineAvailable && activeDownload == null,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.cactus_import_act),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { zipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                            enabled = engineAvailable && activeDownload == null,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.cactus_import_zip),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Folder import brings bundle directories, which only the
                    // full engine runs; needle builds import .cact files.
                    if (!isNeedle) {
                        OutlinedButton(
                            onClick = { treeLauncher.launch(null) },
                            enabled = engineAvailable && activeDownload == null,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.cactus_import_folder),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                errorText?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.cactus_transfer_failed, message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (activeDownload != null) {
                TextButton(onClick = { downloadJob?.cancel() }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = onDismissed) {
                    Text(stringResource(R.string.ok))
                }
            }
        },
    )
}

private fun suggestedAlias(
    entry: CactusModelCatalog.Entry,
    variant: CactusModelCatalog.Variant,
): String {
    if (entry.isActFile) return "Needle 3 · CQ${variant.bits}"
    val family = if (entry.slug.startsWith("gemma")) "Gemma 4 E2B" else entry.slug
    val bits = if (variant.bits == variant.bits.toInt().toDouble()) {
        variant.bits.toInt().toString()
    } else {
        variant.bits.toString()
    }
    return "$family · CQ$bits"
}

private data class ActiveDownload(
    val key: String = "",
    val label: String = "download",
    val bytes: Long = 0L,
    val totalBytes: Long? = null,
)

/** A downloaded or imported model awaiting registration: bundle dir or .cact file. */
internal data class PendingCactusModel(
    val path: java.io.File,
    val suggestedModelId: String,
    val suggestedAlias: String,
) {
    companion object {
        fun fromDirectory(dir: java.io.File): PendingCactusModel = PendingCactusModel(
            path = dir,
            suggestedModelId = dir.name,
            suggestedAlias = dir.name,
        )

        fun fromFile(file: java.io.File): PendingCactusModel = PendingCactusModel(
            path = file,
            suggestedModelId = file.name.removeSuffix(".cact"),
            suggestedAlias = file.name.removeSuffix(".cact"),
        )
    }
}

@Composable
private fun CactusCatalogEntryRow(
    entry: CactusModelCatalog.Entry,
    engineAvailable: Boolean,
    installedKeys: Set<String>,
    activeDownload: ActiveDownload?,
    onDownload: (CactusModelCatalog.Variant) -> Unit,
    onDeleteSpeech: ((CactusModelCatalog.Entry) -> Unit)? = null,
) {
    var selectedVariant by remember(entry) { mutableStateOf(entry.defaultVariant) }
    val displayName = when {
        entry.isSpeech -> "Whistle"
        entry.isActFile -> "Needle 3"
        entry.slug.startsWith("gemma") -> "Gemma 4 E2B (it)"
        else -> "Needle"
    }
    val description = when {
        entry.isSpeech -> stringResource(R.string.cactus_catalog_whistle_desc)
        entry.isActFile -> stringResource(R.string.cactus_catalog_needle3_desc)
        entry.slug.startsWith("gemma") -> stringResource(R.string.cactus_catalog_gemma_desc)
        else -> stringResource(R.string.cactus_catalog_needle_desc)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Column {
                entry.variants.forEach { variant ->
                    val key = if (entry.isActFile) {
                        variant.filename
                    } else {
                        CactusModelCatalog.bundleDirName(entry, variant)
                    }
                    val installed = key in installedKeys
                    val downloading = activeDownload?.key == key
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = engineAvailable) { selectedVariant = variant }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = selectedVariant == variant,
                            onClick = if (engineAvailable) {
                                { selectedVariant = variant }
                            } else {
                                null
                            },
                        )
                        Text(
                            if (entry.isSpeech) {
                                formatBytes(variant.sizeBytes)
                            } else {
                                "CQ${variant.bits} · ${formatBytes(variant.sizeBytes)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (installed) {
                            if (entry.isSpeech && onDeleteSpeech != null) {
                                IconButton(
                                    onClick = { onDeleteSpeech(entry) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.cactus_installed),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            val downloading = activeDownload?.key ==
                if (entry.isActFile) {
                    selectedVariant.filename
                } else {
                    CactusModelCatalog.bundleDirName(entry, selectedVariant)
                }
            if (downloading && activeDownload != null) {
                Spacer(Modifier.height(4.dp))
                val progress = activeDownload.totalBytes?.takeIf { it > 0 }?.let { total ->
                    (activeDownload.bytes.toDouble() / total).coerceIn(0.0, 1.0)
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatBytes(activeDownload.bytes)}" +
                        (activeDownload.totalBytes?.let { " / ${formatBytes(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onDownload(selectedVariant) },
                    enabled = engineAvailable && activeDownload == null,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            R.string.cactus_download_variant,
                            "CQ${selectedVariant.bits}",
                            formatBytes(selectedVariant.sizeBytes),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddCactusModelDialog(
    viewModel: ChatViewModel,
    model: PendingCactusModel,
    onDismissed: (deleteFiles: Boolean) -> Unit,
) {
    var modelId by remember { mutableStateOf(model.suggestedModelId) }
    var modelAlias by remember { mutableStateOf(model.suggestedAlias) }
    var temperature by remember { mutableStateOf("0.7") }
    var topP by remember { mutableStateOf("0.9") }
    var maxTokens by remember { mutableStateOf("1024") }
    var idError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    val idRegex = remember { Regex("^[a-z0-9._-]+\$") }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { onDismissed(true) },
        title = { Text(stringResource(R.string.cactus_add_model_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.cactus_bundle_ready, model.path.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it; idError = null },
                    label = { Text(stringResource(R.string.model_id_label)) },
                    supportingText = if (idError != null) {
                        { Text(idError!!, color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    isError = idError != null,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = modelAlias,
                    onValueChange = { modelAlias = it },
                    label = { Text(stringResource(R.string.model_alias_label)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text(stringResource(R.string.local_temperature)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = topP,
                    onValueChange = { topP = it },
                    label = { Text(stringResource(R.string.local_top_p)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    label = { Text(stringResource(R.string.local_max_tokens)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                formError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = modelId.trim()
                idError = null; formError = null
                if (id.isBlank()) { idError = "ID is required"; return@TextButton }
                if (!idRegex.matches(id)) { idError = "Only a-z, 0-9, . _ - allowed"; return@TextButton }
                if (viewModel.modelManager.isLocalModelIdTaken(id)) { idError = "Already in use"; return@TextButton }
                val t = temperature.toFloatOrNull()?.takeIf { it in 0f..2f }
                    ?: run { formError = "Temperature must be 0–2"; return@TextButton }
                val p = topP.toFloatOrNull()?.takeIf { it in 0f..1f }
                    ?: run { formError = "Top P must be 0–1"; return@TextButton }
                val m = maxTokens.toIntOrNull()?.takeIf { it > 0 }
                    ?: run { formError = "Max tokens must be positive"; return@TextButton }
                viewModel.modelManager.addLocalChatModel(
                    LocalChatModelConfig(
                        modelId = id,
                        alias = modelAlias.ifBlank { id },
                        localFilePath = model.path.absolutePath,
                        nCtx = CACTUS_NOMINAL_CONTEXT,
                        temperature = t,
                        topP = p,
                        maxTokens = m,
                        engine = CactusEngine.ENGINE_ID,
                    ),
                )
                onDismissed(false)
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = { onDismissed(true) }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Display context used for estimates; the engine manages the real window itself. */
private const val CACTUS_NOMINAL_CONTEXT = 8192

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.0f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.1f GB", gb)
}
