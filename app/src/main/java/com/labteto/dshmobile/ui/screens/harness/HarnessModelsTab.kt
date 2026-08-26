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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.labteto.dshmobile.data.SettingsPlane
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray

/**
 * The Models section: the harness's configurable provider routes.
 *
 * Editable when the plane answers writable; read-only otherwise, where the non-privileged
 * catalog ([ModelsUi.catalog]) stands in for the profile facts the fence keeps private.
 */
@Composable
fun ModelsTab(viewModel: HarnessSettingsViewModel) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val planeUi by viewModel.planeUi.collectAsStateWithLifecycle()
    val editable = planeUi.editable
    // Credential facts cross only with the plane: the key row shows when the plane answered.
    val keyVisible = planeUi.status is SettingsPlane.PlaneStatus.Ready
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors

    var edit by remember { mutableStateOf<EditorRequest?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    var removeRow by remember { mutableStateOf<HarnessSettingsViewModel.ProviderRowUi?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        val ui = models
        if (ui == null) {
            Text(
                stringResource(R.string.common_loading),
                style = DsType.small13,
                color = colors.labelTertiary,
                modifier = Modifier.padding(top = DsSpacing.comfortable),
            )
            return@Column
        }

        ui.error?.let { error ->
            Text(error, style = DsType.small13, color = colors.warnLabel)
            DsButton(
                text = stringResource(R.string.hset_retry),
                onClick = { scope.launch { viewModel.loadModels() } },
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
            )
        }

        if (ui.rows.isEmpty()) {
            Text(
                stringResource(R.string.hset_models_empty),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        } else {
            if (editable) {
                Row(
                    Modifier.fillMaxWidth().padding(top = DsSpacing.small),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The dormant declared routes: routes the adapters ship but no layer configures.
                    val dormant = ui.rows.filter { it.entry.declared == true && !it.configured }
                    if (dormant.isNotEmpty()) {
                        DsMenu(
                            anchor = {
                                DsButton(
                                    text = stringResource(R.string.hset_models_add_provider),
                                    onClick = {},
                                    variant = DsButtonVariant.Outline,
                                    size = DsButtonSize.Small,
                                    icon = Icons.Filled.Add,
                                )
                            },
                            items = dormant.map { row ->
                                MenuItem(row.entry.displayName) { edit = EditorRequest(row, EditorMode.Add) }
                            },
                        )
                    }
                    val hasPiAi = ui.rows.any {
                        viewModel.layoutOf(it.entry.settingsNs) == HarnessSettingsViewModel.Layout.PiAi
                    }
                    if (hasPiAi) {
                        DsButton(
                            text = stringResource(R.string.hset_models_add_custom),
                            onClick = { customOpen = true },
                            variant = DsButtonVariant.Outline,
                            size = DsButtonSize.Small,
                            icon = Icons.Filled.Add,
                        )
                    }
                }
                Spacer(Modifier.height(DsSpacing.small))
            }

            ui.rows.forEach { row ->
                ProviderRowCard(
                    row = row,
                    ui = ui,
                    editable = editable,
                    keyVisible = keyVisible,
                    onEdit = { edit = EditorRequest(row, EditorMode.Edit) },
                    onRemove = { removeRow = row },
                )
            }
        }
    }

    edit?.let { request ->
        ProviderEditorSheet(
            viewModel = viewModel,
            row = request.row,
            mode = request.mode,
            onDismiss = { edit = null },
        )
    }
    if (customOpen) {
        val piAiNs = models?.rows.orEmpty()
            .firstOrNull { viewModel.layoutOf(it.entry.settingsNs) == HarnessSettingsViewModel.Layout.PiAi }
            ?.entry?.settingsNs
        if (piAiNs != null) {
            CustomProviderSheet(
                viewModel = viewModel,
                settingsNs = piAiNs,
                existingRoutes = models?.rows.orEmpty().map { it.entry.provider }.toSet(),
                onDismiss = { customOpen = false },
            )
        }
    }
    removeRow?.let { row ->
        DsDialog(
            title = stringResource(R.string.hset_models_remove_confirm_title, row.entry.displayName),
            onDismiss = { removeRow = null },
        ) {
            Text(
                stringResource(R.string.hset_models_remove_confirm),
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.padding(bottom = DsSpacing.medium),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                DsButton(
                    text = stringResource(R.string.common_delete),
                    onClick = {
                        removeRow = null
                        scope.launch {
                            ToastFunnel.report(viewModel.removeProvider(row), R.string.hset_removed)
                        }
                    },
                    variant = DsButtonVariant.Danger,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { removeRow = null },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

/** One provider card: identity, live state, key state, and the edit actions. */
@Composable
private fun ProviderRowCard(
    row: HarnessSettingsViewModel.ProviderRowUi,
    ui: HarnessSettingsViewModel.ModelsUi,
    editable: Boolean,
    keyVisible: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = DsTheme.colors
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.entry.displayName,
                style = DsType.std14,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.entry.active) {
                DsPill(text = stringResource(R.string.hset_models_active), selected = true)
                Spacer(Modifier.width(4.dp))
            }
            if (row.custom) {
                DsPill(text = stringResource(R.string.hset_models_custom))
            }
        }
        Text(
            row.entry.provider,
            style = DsType.caption11,
            color = colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Model facts: the profile's override catalog when the plane served it, else the
        // non-privileged live catalog.
        val overrideCount = (row.profile?.get("models") as? JsonArray)?.size
        if (overrideCount != null) {
            ModelCountLine(count = overrideCount, builtIn = overrideCount == 0)
        } else {
            val catalog = ui.catalogModels(row.entry.provider)
            if (catalog.isNotEmpty()) {
                ModelCountLine(count = catalog.size, builtIn = false)
                Text(
                    catalog.take(4).joinToString(", ") { it.id },
                    style = DsType.caption11,
                    color = colors.labelCaption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                ModelCountLine(count = 0, builtIn = true)
            }
        }

        if (keyVisible) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(state = if (row.keyConfigured) StateDotState.Done else StateDotState.Idle)
                Spacer(Modifier.width(6.dp))
                Text(
                    row.keyRef,
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        if (row.keyConfigured) R.string.hset_models_key_configured else R.string.hset_models_key_missing,
                    ),
                    style = DsType.caption11,
                    color = if (row.keyConfigured) colors.labelTertiary else colors.warnLabel,
                )
            }
        }

        if (editable) {
            Spacer(Modifier.height(DsSpacing.xsmall))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                Spacer(Modifier.weight(1f))
                DsButton(
                    text = stringResource(R.string.hset_models_edit),
                    onClick = onEdit,
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                )
                if (row.removable) {
                    DsButton(
                        text = stringResource(R.string.hset_models_remove),
                        onClick = onRemove,
                        variant = DsButtonVariant.Danger,
                        size = DsButtonSize.Small,
                    )
                }
            }
        }
    }
}

/** The model-count line under a provider row; "built-in" when no override catalog is stored. */
@Composable
private fun ModelCountLine(count: Int, builtIn: Boolean) {
    val colors = DsTheme.colors
    Text(
        if (builtIn) stringResource(R.string.hset_models_built_in_catalog)
        else stringResource(R.string.hset_models_count, count),
        style = DsType.caption11,
        color = colors.labelTertiary,
    )
}

// ------------------------------------------------------------------ local state

/** The editor sheet's request: which row, and whether it creates a profile. */
data class EditorRequest(val row: HarnessSettingsViewModel.ProviderRowUi, val mode: EditorMode)

/** Whether the editor creates a new profile (add) or edits the stored one. */
enum class EditorMode { Edit, Add }
