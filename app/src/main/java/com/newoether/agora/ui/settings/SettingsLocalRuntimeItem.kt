package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.LocalModelRuntime

/**
 * "Local Runtime" selector shown in the Advanced group of the local provider
 * settings page. Lets the user pin local llama.cpp models to Auto, CPU or the
 * Vulkan GPU backend; the choice takes effect from the next model load.
 *
 * @param localRuntimePreference persisted preference: "auto" | "cpu" | "vulkan".
 * @param isLocal whether the settings page currently shows the Local provider.
 * @param onSelect persists a new preference value.
 */
@Composable
internal fun LocalRuntimeSettingItem(
    localRuntimePreference: String,
    isLocal: Boolean,
    onSelect: (String) -> Unit,
) {
    // Backend devices registered by ggml for this APK + device (Vulkan presence, GPU name).
    // The app normally registers backends during startup, but this item can be composed
    // before that finishes (fresh install, slow database gate) — a premature query would
    // see a CPU-only registry and wrongly grey out Vulkan. Initializing here is
    // idempotent and cheap once the startup path has already run.
    val context = LocalContext.current
    val backendDevices = remember(isLocal, context) {
        if (isLocal) {
            runCatching {
                LocalModelRuntime.initialize(context.applicationInfo.nativeLibraryDir)
            }
            LlamaEngine.listBackendDevices()
        } else {
            emptyList()
        }
    }
    // Loader version queried independently of the backend registry: when no GPU device
    // registers, this distinguishes "driver too old" from "backend failed to init".
    val vulkanLoaderVersion = remember(isLocal) {
        if (isLocal) LlamaEngine.vulkanInstanceVersion() else null
    }
    val vulkanDevice = backendDevices.firstOrNull { it.isGpu }
    val vulkanStatus = if (isLocal && vulkanDevice == null) {
        when {
            vulkanLoaderVersion == null ->
                stringResource(R.string.local_runtime_vulkan_status_no_loader)
            vulkanLoaderVersion < LlamaEngine.VULKAN_MIN_REQUIRED ->
                stringResource(
                    R.string.local_runtime_vulkan_status_old,
                    LlamaEngine.formatVulkanVersion(vulkanLoaderVersion),
                )
            else ->
                stringResource(
                    R.string.local_runtime_vulkan_status_init_failed,
                    LlamaEngine.formatVulkanVersion(vulkanLoaderVersion),
                )
        }
    } else {
        null
    }
    var runtimeMenuExpanded by remember { mutableStateOf(false) }
    val runtimeLabel = when (localRuntimePreference) {
        "cpu" -> stringResource(R.string.local_runtime_cpu)
        "vulkan" -> stringResource(R.string.local_runtime_vulkan)
        else -> stringResource(R.string.local_runtime_auto)
    }
    SettingsItem(
        headlineContent = {
            Text(stringResource(R.string.local_runtime_title))
        },
        supportingContent = {
            Column {
                Text(stringResource(R.string.local_runtime_desc))
                if (vulkanStatus != null) {
                    Text(
                        vulkanStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        },
        trailingContent = {
            Box {
                Text(
                    runtimeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
                DropdownMenu(
                    expanded = runtimeMenuExpanded,
                    onDismissRequest = { runtimeMenuExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.local_runtime_auto)) },
                        leadingIcon = {
                            if (localRuntimePreference == "auto") {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        onClick = {
                            onSelect("auto")
                            runtimeMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.local_runtime_cpu)) },
                        leadingIcon = {
                            if (localRuntimePreference == "cpu") {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        onClick = {
                            onSelect("cpu")
                            runtimeMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (vulkanDevice != null &&
                                    vulkanDevice.description.isNotBlank() &&
                                    vulkanDevice.description != vulkanDevice.name
                                ) {
                                    stringResource(
                                        R.string.local_runtime_vulkan_with_device,
                                        vulkanDevice.description,
                                    )
                                } else {
                                    stringResource(R.string.local_runtime_vulkan)
                                }
                            )
                        },
                        leadingIcon = {
                            if (localRuntimePreference == "vulkan") {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        enabled = backendDevices.isEmpty() || vulkanDevice != null,
                        onClick = {
                            onSelect("vulkan")
                            runtimeMenuExpanded = false
                        },
                    )
                }
            }
        },
        modifier = Modifier.clickable { runtimeMenuExpanded = true },
    )
}
