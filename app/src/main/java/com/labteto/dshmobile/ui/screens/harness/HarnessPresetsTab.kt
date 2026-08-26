package com.labteto.dshmobile.ui.screens.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.AgentPresetEntry
import com.labteto.dshmobile.core.wire.dto.AgentPresetReadValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetTrust
import com.labteto.dshmobile.data.SettingsPlane
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The Agent presets section: the roster with the authoring actions the web GUI's presets page
 * offers — set default, view source, copy, open on host, and delete (user presets only).
 *
 * The roster itself is non-privileged (a refused plane still lists it); the authoring calls are
 * privileged and sit behind [SettingsPlane.PlaneUi.editable].
 */
@Composable
fun PresetsTab(viewModel: HarnessSettingsViewModel) {
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val planeUi by viewModel.planeUi.collectAsStateWithLifecycle()
    val editable = planeUi.editable
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors

    var viewId by remember { mutableStateOf<String?>(null) }
    var copyFrom by remember { mutableStateOf<AgentPresetEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<AgentPresetEntry?>(null) }

    val rows = presets?.presets.orEmpty()
    val knownIds = rows.map { it.id }.toSet()
    val authoring = presets?.authorable == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(
            stringResource(R.string.hset_presets_hint),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.hset_preset_empty),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        }
        rows.forEach { entry ->
            DsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name ?: entry.id,
                        style = DsType.std14,
                        color = colors.labelPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.isDefault) {
                        DsPill(text = stringResource(R.string.hset_preset_default), selected = true)
                        Spacer(Modifier.width(4.dp))
                    }
                    DsPill(
                        text = stringResource(
                            if (entry.trust == AgentPresetTrust.USER) R.string.hset_preset_trust_user
                            else R.string.hset_preset_trust_system,
                        ),
                    )
                }
                val description = entry.description
                if (description?.isNotBlank() == true) {
                    Text(
                        description,
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val broken = entry.broken
                if (broken?.isNotBlank() == true) {
                    Text(
                        stringResource(R.string.hset_preset_broken, broken),
                        style = DsType.caption11,
                        color = colors.warnLabel,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (editable) {
                    Spacer(Modifier.height(DsSpacing.xsmall))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                    ) {
                        DsButton(
                            text = stringResource(R.string.hset_preset_view),
                            onClick = { viewId = entry.id },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                        if (!entry.isDefault) {
                            DsButton(
                                text = stringResource(R.string.hset_preset_set_default),
                                onClick = {
                                    scope.launch {
                                        ToastFunnel.report(
                                            viewModel.setDefaultPreset(entry.id),
                                            R.string.hset_preset_default_saved,
                                        )
                                    }
                                },
                                variant = DsButtonVariant.Ghost,
                                size = DsButtonSize.Small,
                            )
                        }
                        if (authoring) {
                            DsButton(
                                text = stringResource(R.string.hset_preset_copy),
                                onClick = { copyFrom = entry },
                                variant = DsButtonVariant.Ghost,
                                size = DsButtonSize.Small,
                            )
                        }
                        if (entry.trust == AgentPresetTrust.USER) {
                            DsButton(
                                text = stringResource(R.string.common_delete),
                                onClick = { deleteEntry = entry },
                                variant = DsButtonVariant.Danger,
                                size = DsButtonSize.Small,
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------ source viewer
    val openViewId = viewId
    if (openViewId != null) {
        PresetSourceSheet(
            id = openViewId,
            viewModel = viewModel,
            onDismiss = { viewId = null },
        )
    }

    // ------------------------------------------------ copy dialog
    copyFrom?.let { source ->
        CopyPresetDialog(
            source = source,
            knownIds = knownIds,
            onDismiss = { copyFrom = null },
            onCopy = { id, name ->
                copyFrom = null
                scope.launch {
                    ToastFunnel.report(
                        viewModel.presetCopy(source.id, id, name),
                        R.string.hset_preset_copied,
                    )
                }
            },
        )
    }

    // ------------------------------------------------ delete dialog
    deleteEntry?.let { entry ->
        DsDialog(
            title = stringResource(R.string.hset_preset_delete_title, entry.name ?: entry.id),
            onDismiss = { deleteEntry = null },
        ) {
            Text(
                stringResource(R.string.hset_preset_delete_confirm),
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.padding(bottom = DsSpacing.medium),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                DsButton(
                    text = stringResource(R.string.common_delete),
                    onClick = {
                        deleteEntry = null
                        scope.launch {
                            ToastFunnel.report(
                                viewModel.presetRemove(entry.id),
                                R.string.hset_preset_deleted,
                            )
                        }
                    },
                    variant = DsButtonVariant.Danger,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { deleteEntry = null },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

/** The preset's source document in a scrollable mono block. */
@Composable
private fun PresetSourceSheet(
    id: String,
    viewModel: HarnessSettingsViewModel,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var content by remember { mutableStateOf<AgentPresetReadValue?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        val result = viewModel.presetRead(id)
        if (result == null) failed = true else content = result
    }
    val loaded = content
    DsBottomSheet(title = id, onDismiss = onDismiss) {
        if (failed) {
            Text(
                stringResource(R.string.hset_save_failed, "agentPreset.read"),
                style = DsType.small13,
                color = colors.error,
            )
        } else if (loaded == null) {
            Text(
                stringResource(R.string.common_loading),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        } else {
            Text(
                loaded.content,
                style = DsType.caption11.copy(fontFamily = DsType.codeFont, color = colors.labelSecondary),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = DsSpacing.small),
            )
        }
    }
}

/** The copy dialog: a new id (validated) and an optional display name. */
@Composable
private fun CopyPresetDialog(
    source: AgentPresetEntry,
    knownIds: Set<String>,
    onDismiss: () -> Unit,
    onCopy: (String, String?) -> Unit,
) {
    val colors = DsTheme.colors
    var id by remember { mutableStateOf("${source.id}-copy") }
    var name by remember { mutableStateOf("") }
    val invalid = id.isBlank() || !PRESET_ID_PATTERN.matches(id)
    val taken = id.isNotBlank() && id in knownIds

    DsDialog(
        title = stringResource(R.string.hset_preset_copy_title),
        onDismiss = onDismiss,
    ) {
        Text(
            stringResource(R.string.hset_preset_copy_hint),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        HarnessField(
            label = stringResource(R.string.hset_preset_copy_id),
            value = id,
            onValueChange = { id = it },
            hint = when {
                invalid -> stringResource(R.string.hset_preset_id_invalid)
                taken -> stringResource(R.string.hset_val_route_taken)
                else -> null
            },
        )
        Spacer(Modifier.height(DsSpacing.small))
        HarnessField(
            label = stringResource(R.string.hset_preset_copy_name),
            value = name,
            onValueChange = { name = it },
        )
        Spacer(Modifier.height(DsSpacing.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(R.string.common_ok),
                enabled = !invalid && !taken,
                onClick = { onCopy(id, name.trim().takeIf { s -> s.isNotEmpty() }) },
            )
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
        }
    }
}
