package com.labteto.dshmobile.ui.screens.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.AgentPresetEntry
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.data.SettingsPlane
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The General tab: the harness's own defaults — which agent preset new sessions start with, the
 * default permission mode, and the web GUI's language and appearance (the same settings the web
 * GUI's General section edits, written to the same namespaces).
 *
 * The preset roster and the permission options come from the non-privileged reads the
 * [com.labteto.dshmobile.data.SessionStore] already keeps, so the tab renders even when the
 * plane is refused; the writes sit behind [SettingsPlane.PlaneUi.editable].
 */
@Composable
fun GeneralTab(viewModel: HarnessSettingsViewModel) {
    val planeUi by viewModel.planeUi.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val editable = planeUi.editable
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = DsTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
    ) {
        // ------------------------------------------------ agent preset
        val presetRows = presets?.presets.orEmpty()
        val storedDefault = ((planeUi.namespace("agent-presets")?.value as? JsonObject)?.get("default") as? JsonPrimitive)?.content
        val selected = storedDefault ?: presetRows.firstOrNull { it.isDefault }?.id

        SectionHeader(stringResource(R.string.hset_general_preset_title))
        Text(
            stringResource(R.string.hset_general_preset_hint),
            style = DsType.caption11,
            color = colors.labelTertiary,
            modifier = Modifier.padding(horizontal = DsSpacing.medium),
        )
        if (presetRows.isEmpty()) {
            Text(
                stringResource(R.string.hset_preset_empty),
                style = DsType.small13,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = DsSpacing.medium),
            )
        } else {
            PresetChipRow(
                rows = presetRows,
                selectedId = selected,
                enabled = editable,
                onPick = { id ->
                    scope.launch { ToastFunnel.report(viewModel.setDefaultPreset(id), R.string.hset_preset_default_saved) }
                },
            )
        }

        // ------------------------------------------------ permission
        val permissionDefault = ((planeUi.namespace("permission")?.value as? JsonObject)?.get("defaultPreset") as? JsonPrimitive)?.content
        val options = permissions?.selectable ?: permissions?.options.orEmpty()
        val selectedPermission = permissionDefault ?: permissions?.currentValue

        SectionHeader(stringResource(R.string.hset_general_permission_title))
        Text(
            stringResource(R.string.hset_general_permission_hint),
            style = DsType.caption11,
            color = colors.labelTertiary,
            modifier = Modifier.padding(horizontal = DsSpacing.medium),
        )
        if (options.isNotEmpty()) {
            GeneralChipRow(
                options = options.map { it.name to it.value },
                selectedValue = selectedPermission,
                enabled = editable,
                onPick = { value ->
                    value?.let { v ->
                        scope.launch { ToastFunnel.report(viewModel.setPermissionPreset(v), R.string.hset_saved) }
                    }
                },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    permissionDefault ?: stringResource(R.string.hset_general_permission_unknown),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.hset_general_permission_no_session),
                    style = DsType.caption11,
                    color = colors.labelCaption,
                )
            }
        }

        // ------------------------------------------------ language (web GUI)
        val locale = ((planeUi.namespace("locale")?.value as? JsonObject)?.get("preference") as? JsonPrimitive)?.content
        SectionHeader(stringResource(R.string.hset_general_locale_title))
        GeneralChipRow(
            options = listOf(
                stringResource(R.string.hset_general_locale_system) to null,
                stringResource(R.string.hset_general_locale_en) to "en",
                stringResource(R.string.hset_general_locale_zh) to "zh",
            ),
            selectedValue = when (locale) {
                "en" -> stringResource(R.string.hset_general_locale_en)
                "zh" -> stringResource(R.string.hset_general_locale_zh)
                else -> stringResource(R.string.hset_general_locale_system)
            },
            enabled = editable,
            onPick = { value ->
                scope.launch { ToastFunnel.report(viewModel.setLocale(value), R.string.hset_saved) }
            },
        )

        // ------------------------------------------------ appearance (web GUI)
        val theme = ((planeUi.namespace("ui-theme")?.value as? JsonObject)?.get("preference") as? JsonPrimitive)?.content
        SectionHeader(stringResource(R.string.hset_general_theme_title))
        GeneralChipRow(
            options = listOf(
                stringResource(R.string.hset_general_theme_light) to "light",
                stringResource(R.string.hset_general_theme_dark) to "dark",
                stringResource(R.string.hset_general_theme_system) to "system",
            ),
            selectedValue = when (theme) {
                "light" -> stringResource(R.string.hset_general_theme_light)
                "dark" -> stringResource(R.string.hset_general_theme_dark)
                else -> stringResource(R.string.hset_general_theme_system)
            },
            enabled = editable,
            onPick = { value ->
                value?.let { v ->
                    scope.launch { ToastFunnel.report(viewModel.setTheme(v), R.string.hset_saved) }
                }
            },
        )

        // ------------------------------------------------ settings document
        if (planeUi.status is SettingsPlane.PlaneStatus.Ready && planeUi.hasDocument) {
            DsButton(
                text = stringResource(R.string.hset_general_open_document),
                onClick = {
                    scope.launch {
                        val opened = viewModel.openSettingsDocument()
                        ToastFunnel.toast(
                            when (opened) {
                                true -> context.getString(R.string.hset_general_document_opened)
                                false -> context.getString(R.string.hset_general_document_unavailable)
                                null -> context.getString(R.string.hset_save_failed, "settings.openDocument")
                            },
                        )
                    }
                },
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.hset_general_open_document_hint),
                style = DsType.caption11,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = DsSpacing.medium),
            )
        }
    }
}

/** The preset roster as a wrapping chip row; the selected one is the stored default. */
@Composable
private fun PresetChipRow(
    rows: List<AgentPresetEntry>,
    selectedId: String?,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        rows.forEach { row ->
            DsPill(
                text = row.name ?: row.id,
                selected = row.id == selectedId,
                onClick = { if (enabled) onPick(row.id) },
            )
        }
    }
}

/**
 * A row of selectable chips. [options] are label-to-value pairs; [selectedValue] is the label of
 * the selected option (compared by label so the caller need not thread values through). [onPick]
 * receives the option's *value*.
 */
@Composable
fun GeneralChipRow(
    options: List<Pair<String, String?>>,
    selectedValue: String?,
    enabled: Boolean,
    onPick: (String?) -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        options.forEach { (label, value) ->
            DsPill(
                text = label,
                selected = label == selectedValue,
                onClick = { if (enabled) onPick(value) },
            )
        }
    }
}

/**
 * A single selectable chip, for inline option rows (the provider editor's protocol picker, the
 * plugins tab's sub-tabs). [enabled] false renders the chip but swallows the click.
 */
@Composable
fun GeneralChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DsPill(
        text = label,
        selected = selected,
        onClick = { if (enabled) onClick() },
    )
}
