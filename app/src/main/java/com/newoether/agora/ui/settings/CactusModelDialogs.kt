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
 * The catalog lists model families that publish prebuilt "-cqN" bundles for the
 * vendored runtime revision; downloads stream from HuggingFace with checksum
 * verification, and bundles can also be imported from a .zip archive or a
 * folder produced by `cactus convert` on a computer. On 32-bit builds — where
 * the Cactus library is not packaged — the whole surface explains the
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
    val engineAvailable = remember {
        runCatching {
            CactusEngine.isAvailable(context.applicationInfo.nativeLibraryDir)
        }.getOrDefault(false)
    }

    var installedDirs by remember { mutableStateOf(manager.installedBundleDirNames()) }
    var activeDownload by remember { mutableStateOf<ActiveDownload?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pendingBundle by remember { mutableStateOf<PendingCactusBundle?>(null) }

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
                    pendingBundle = PendingCactusBundle.fromDirectory(dir)
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

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && activeDownload == null) {
            activeDownload = ActiveDownload(label = "import")
            startTransfer {
                try {
                    val dir = manager.importDirectory(uri)
                    pendingBundle = PendingCactusBundle.fromDirectory(dir)
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
            bundle = pending,
            onDismissed = {
                if (it) {
                    manager.deleteBundle(pending.directory.absolutePath)
                }
                pendingBundle = null
                installedDirs = manager.installedBundleDirNames()
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
                }
                CactusModelCatalog.entries.forEach { entry ->
                    CactusCatalogEntryRow(
                        entry = entry,
                        engineAvailable = engineAvailable,
                        installedDirs = installedDirs,
                        activeDownload = activeDownload,
                        onDownload = { variant ->
                            if (activeDownload == null) {
                                val dirName = CactusModelCatalog.bundleDirName(entry, variant)
                                activeDownload = ActiveDownload(
                                    key = dirName,
                                    totalBytes = variant.sizeBytes,
                                )
                                startTransfer {
                                    try {
                                        val dir = manager.download(entry, variant) { bytes, _ ->
                                            activeDownload = activeDownload?.copy(bytes = bytes)
                                        }
                                        pendingBundle = PendingCactusBundle(
                                            directory = dir,
                                            suggestedModelId =
                                                CactusModelCatalog.suggestedModelId(entry, variant),
                                            suggestedAlias = suggestedAlias(entry, variant),
                                        )
                                    } catch (e: Exception) {
                                        if (e !is kotlinx.coroutines.CancellationException) {
                                            DebugLog.e("CactusCatalog", "download failed", e)
                                            errorText = e.message
                                        }
                                    } finally {
                                        activeDownload = null
                                        installedDirs = manager.installedBundleDirNames()
                                    }
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
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

internal data class PendingCactusBundle(
    val directory: java.io.File,
    val suggestedModelId: String,
    val suggestedAlias: String,
) {
    companion object {
        fun fromDirectory(dir: java.io.File): PendingCactusBundle = PendingCactusBundle(
            directory = dir,
            suggestedModelId = dir.name,
            suggestedAlias = dir.name,
        )
    }
}

@Composable
private fun CactusCatalogEntryRow(
    entry: CactusModelCatalog.Entry,
    engineAvailable: Boolean,
    installedDirs: Set<String>,
    activeDownload: ActiveDownload?,
    onDownload: (CactusModelCatalog.Variant) -> Unit,
) {
    var selectedVariant by remember(entry) { mutableStateOf(entry.defaultVariant) }
    val displayName = if (entry.slug.startsWith("gemma")) "Gemma 4 E2B (it)" else "Needle"
    val description = if (entry.slug.startsWith("gemma")) {
        stringResource(R.string.cactus_catalog_gemma_desc)
    } else {
        stringResource(R.string.cactus_catalog_needle_desc)
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
                    val dirName = CactusModelCatalog.bundleDirName(entry, variant)
                    val installed = dirName in installedDirs
                    val downloading = activeDownload?.key == dirName
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
                            "CQ${variant.bits} · ${formatBytes(variant.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (installed) {
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
                CactusModelCatalog.bundleDirName(entry, selectedVariant)
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
    bundle: PendingCactusBundle,
    onDismissed: (deleteFiles: Boolean) -> Unit,
) {
    var modelId by remember { mutableStateOf(bundle.suggestedModelId) }
    var modelAlias by remember { mutableStateOf(bundle.suggestedAlias) }
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
                    stringResource(R.string.cactus_bundle_ready, bundle.directory.name),
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
                        localFilePath = bundle.directory.absolutePath,
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
