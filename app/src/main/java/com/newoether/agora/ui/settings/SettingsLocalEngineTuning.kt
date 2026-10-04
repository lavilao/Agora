package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.LocalEngineTuning
import com.newoether.agora.data.LOCAL_FLASH_ATTENTION_MODES
import com.newoether.agora.data.LOCAL_KV_CACHE_TYPES
import com.newoether.agora.data.LOCAL_THREAD_COUNTS
import com.newoether.agora.data.repository.SettingsRepository

/**
 * "Engine Tuning" group for the local provider page: the koboldcpp-derived capability
 * switches of the embedded llama.cpp runtime. Every choice becomes part of the resident
 * model's identity, so it applies from the next model load while the current one keeps
 * running unchanged.
 */
@Composable
internal fun LocalEngineTuningGroup(
    tuning: LocalEngineTuning,
    settings: SettingsRepository,
) {
    SettingsGroup(
        title = stringResource(R.string.local_engine_tuning_title),
        items = localEngineTuningItems(
            tuning = tuning,
            onSelectFlashAttention = settings::setLocalFlashAttention,
            onToggleMmap = settings::setLocalMmap,
            onSelectCacheTypeK = settings::setLocalCacheTypeK,
            onSelectCacheTypeV = settings::setLocalCacheTypeV,
            onToggleSwaFull = settings::setLocalSwaFull,
            onSelectThreads = settings::setLocalThreads,
        ),
    )
}

/**
 * Koboldcpp-derived engine capability switches for the local llama.cpp runtime. Every choice
 * becomes part of the resident model's identity, so it applies from the next model load; the
 * shared description on each row explains the effect while the model keeps running unchanged.
 */
internal fun localEngineTuningItems(
    tuning: LocalEngineTuning,
    onSelectFlashAttention: (String) -> Unit,
    onToggleMmap: (Boolean) -> Unit,
    onSelectCacheTypeK: (String) -> Unit,
    onSelectCacheTypeV: (String) -> Unit,
    onToggleSwaFull: (Boolean) -> Unit,
    onSelectThreads: (Int) -> Unit,
): List<@Composable () -> Unit> = listOf(
    {
        EngineTuningChoiceItem(
            title = stringResource(R.string.local_flash_attention_title),
            description = stringResource(R.string.local_flash_attention_desc),
            icon = { Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            entries = LOCAL_FLASH_ATTENTION_MODES,
            labelOf = ::flashAttentionLabel,
            selected = tuning.flashAttention,
            onSelect = onSelectFlashAttention,
        )
    },
    {
        EngineTuningToggleItem(
            title = stringResource(R.string.local_mmap_title),
            description = stringResource(R.string.local_mmap_desc),
            icon = { Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            checked = tuning.useMmap,
            onCheckedChange = onToggleMmap,
        )
    },
    {
        EngineTuningChoiceItem(
            title = stringResource(R.string.local_kv_cache_k_title),
            description = stringResource(R.string.local_kv_cache_desc),
            icon = { Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            entries = LOCAL_KV_CACHE_TYPES,
            labelOf = { it },
            selected = tuning.cacheTypeK,
            onSelect = onSelectCacheTypeK,
        )
    },
    {
        EngineTuningChoiceItem(
            title = stringResource(R.string.local_kv_cache_v_title),
            description = stringResource(R.string.local_kv_cache_v_desc),
            icon = { Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            entries = LOCAL_KV_CACHE_TYPES,
            labelOf = { it },
            selected = tuning.cacheTypeV,
            onSelect = onSelectCacheTypeV,
        )
    },
    {
        EngineTuningToggleItem(
            title = stringResource(R.string.local_swa_full_title),
            description = stringResource(R.string.local_swa_full_desc),
            icon = { Icon(Icons.Default.Layers, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            checked = tuning.swaFull,
            onCheckedChange = onToggleSwaFull,
        )
    },
    {
        EngineTuningChoiceItem(
            title = stringResource(R.string.local_threads_title),
            description = stringResource(R.string.local_threads_desc),
            icon = { Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            entries = LOCAL_THREAD_COUNTS,
            labelOf = { count ->
                if (count == 0) stringResource(R.string.local_runtime_auto) else count.toString()
            },
            selected = tuning.threads,
            onSelect = onSelectThreads,
        )
    },
)

@Composable
private fun flashAttentionLabel(mode: String): String = when (mode) {
    "on" -> stringResource(R.string.local_engine_on)
    "off" -> stringResource(R.string.local_engine_off)
    else -> stringResource(R.string.local_runtime_auto)
}

@Composable
private fun <T> EngineTuningChoiceItem(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    entries: List<T>,
    labelOf: @Composable (T) -> String,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = icon,
        trailingContent = {
            Box {
                Text(
                    labelOf(selected),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    entries.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(labelOf(entry)) },
                            leadingIcon = {
                                if (entry == selected) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            onClick = {
                                onSelect(entry)
                                expanded = false
                            },
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable { expanded = true },
    )
}

@Composable
private fun EngineTuningToggleItem(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsItem(
        modifier = Modifier.toggleable(
            value = checked,
            role = androidx.compose.ui.semantics.Role.Switch,
            onValueChange = onCheckedChange,
        ),
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = icon,
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}
