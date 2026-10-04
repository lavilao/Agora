package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cache
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
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
import com.newoether.agora.data.LOCAL_BATCH_SIZES
import com.newoether.agora.data.LOCAL_DRAFT_AMOUNTS
import com.newoether.agora.data.LOCAL_FLASH_ATTENTION_MODES
import com.newoether.agora.data.LOCAL_KV_CACHE_TYPES
import com.newoether.agora.data.LOCAL_NGRAM_MATCHES
import com.newoether.agora.data.LOCAL_SMART_CACHE_SLOTS
import com.newoether.agora.data.LOCAL_SPECULATIVE_TYPES
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
            onSelectSpeculativeType = settings::setLocalSpeculativeType,
            onSelectDraftAmount = settings::setLocalSpecDraftAmount,
            onSelectNgramMatch = settings::setLocalNgramMatch,
            onToggleSmartCache = settings::setLocalSmartCache,
            onSelectSmartCacheSlots = settings::setLocalSmartCacheSlots,
            onToggleSmartContext = settings::setLocalSmartContext,
            onToggleContextShift = settings::setLocalContextShift,
            onToggleFastForward = settings::setLocalFastForward,
            onToggleDirectIo = settings::setLocalDirectIo,
            onSelectNBatch = settings::setLocalNBatch,
            onSelectNUbatch = settings::setLocalNUbatch,
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
    onSelectSpeculativeType: (String) -> Unit,
    onSelectDraftAmount: (Int) -> Unit,
    onSelectNgramMatch: (Int) -> Unit,
    onToggleSmartCache: (Boolean) -> Unit,
    onSelectSmartCacheSlots: (Int) -> Unit,
    onToggleSmartContext: (Boolean) -> Unit,
    onToggleContextShift: (Boolean) -> Unit,
    onToggleFastForward: (Boolean) -> Unit,
    onToggleDirectIo: (Boolean) -> Unit,
    onSelectNBatch: (Int) -> Unit,
    onSelectNUbatch: (Int) -> Unit,
): List<@Composable () -> Unit> = listOf(
    {
        EngineTuningChoiceItem(
            title = stringResource(R.string.local_speculative_title),
            description = stringResource(R.string.local_speculative_desc),
            icon = { Icon(Icons.Default.RocketLaunch, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            entries = LOCAL_SPECULATIVE_TYPES,
            labelOf = ::speculativeTypeLabel,
            selected = tuning.speculativeType,
            onSelect = onSelectSpeculativeType,
        )
    },
    {
        EngineTuningChoiceItem(
            title = stringResource(R.string.local_draft_amount_title),
            description = stringResource(R.string.local_draft_amount_desc),
            icon = { Icon(Icons.Default.RocketLaunch, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            entries = LOCAL_DRAFT_AMOUNTS,
            labelOf = { it.toString() },
            selected = tuning.specDraftAmount,
            onSelect = onSelectDraftAmount,
        )
    },
    {
        EngineTuningChoiceItem(
            title = stringResource(R.string.local_ngram_match_title),
            description = stringResource(R.string.local_ngram_match_desc),
            icon = { Icon(Icons.Default.RocketLaunch, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            entries = LOCAL_NGRAM_MATCHES,
            labelOf = { it.toString() },
            selected = tuning.ngramMatch,
            onSelect = onSelectNgramMatch,
        )
    },
    {
        EngineTuningToggleItem(
            title = stringResource(R.string.local_smart_cache_title),
            description = stringResource(R.string.local_smart_cache_desc),
            icon = { Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            checked = tuning.smartCache,
            onCheckedChange = onToggleSmartCache,
        )
    },
    {
        EngineTuningChoiceItem(
            title = stringResource(R.string.local_smart_cache_slots_title),
            description = stringResource(R.string.local_smart_cache_slots_desc),
            icon = { Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            entries = LOCAL_SMART_CACHE_SLOTS,
            labelOf = { it.toString() },
            selected = tuning.smartCacheSlots,
            onSelect = onSelectSmartCacheSlots,
        )
    },
    {
        EngineTuningToggleItem(
            title = stringResource(R.string.local_smart_context_title),
            description = stringResource(R.string.local_smart_context_desc),
            icon = { Icon(Icons.Default.Compress, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            checked = tuning.smartContext,
            onCheckedChange = onToggleSmartContext,
        )
    },
    {
        EngineTuningToggleItem(
            title = stringResource(R.string.local_context_shift_title),
            description = stringResource(R.string.local_context_shift_desc),
            icon = { Icon(Icons.Default.Layers, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            checked = tuning.contextShift,
            onCheckedChange = onToggleContextShift,
        )
    },
    {
        EngineTuningToggleItem(
            title = stringResource(R.string.local_fast_forward_title),
            description = stringResource(R.string.local_fast_forward_desc),
            icon = { Icon(Icons.Default.FastForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            checked = tuning.fastForward,
            onCheckedChange = onToggleFastForward,
        )
    },
    {
        EngineTuningToggleItem(
            title = stringResource(R.string.local_direct_io_title),
            description = stringResource(R.string.local_direct_io_desc),
            icon = { Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            checked = tuning.directIo,
            onCheckedChange = onToggleDirectIo,
        )
    },
    {
        EngineTuningChoiceItem(
            title = stringResource(R.string.local_batch_size_title),
            description = stringResource(R.string.local_batch_size_desc),
            icon = { Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            entries = LOCAL_BATCH_SIZES,
            labelOf = { size ->
                if (size == 0) stringResource(R.string.local_runtime_auto) else size.toString()
            },
            selected = tuning.nBatch,
            onSelect = onSelectNBatch,
        )
    },
    {
        EngineTuningChoiceItem(
            title = stringResource(R.string.local_ubatch_size_title),
            description = stringResource(R.string.local_ubatch_size_desc),
            icon = { Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
            entries = LOCAL_BATCH_SIZES,
            labelOf = { size ->
                if (size == 0) stringResource(R.string.local_runtime_auto) else size.toString()
            },
            selected = tuning.nUbatch,
            onSelect = onSelectNUbatch,
        )
    },
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
            icon = { Icon(Icons.Default.Cache, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
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
private fun speculativeTypeLabel(type: String): String = when (type) {
    "ngram_simple" -> stringResource(R.string.local_spec_type_ngram_simple)
    "ngram_map_k" -> stringResource(R.string.local_spec_type_ngram_map_k)
    "ngram_map_k4v" -> stringResource(R.string.local_spec_type_ngram_map_k4v)
    "ngram_mod" -> stringResource(R.string.local_spec_type_ngram_mod)
    "draft" -> stringResource(R.string.local_spec_type_draft)
    else -> stringResource(R.string.local_engine_off)
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
