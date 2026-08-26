package com.labteto.dshmobile.ui.screens.harness

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.PluginFiberPhase
import com.labteto.dshmobile.core.wire.dto.PluginInventoryEntry
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.data.SettingsPlane
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.screens.main.dialogTextFieldColors
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The Plugins section, in two sub-tabs:
 *
 * - **Configurable**: the plugin namespaces the web GUI's plugins page edits (shell, agent
 *   loop, web search) plus every other served namespace as a read-only row.
 * - **Inventory**: the host's composed-plugin entries (read-only, non-privileged).
 */
@Composable
fun PluginsTab(viewModel: HarnessSettingsViewModel) {
    val planeUi by viewModel.planeUi.collectAsStateWithLifecycle()
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()
    val editable = planeUi.editable
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    var subTab by remember { mutableStateOf(SubTab.Configurable) }
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            GeneralChip(
                label = stringResource(R.string.hset_plugins_configurable),
                selected = subTab == SubTab.Configurable,
                enabled = true,
                onClick = { subTab = SubTab.Configurable },
            )
            GeneralChip(
                label = stringResource(R.string.hset_plugins_inventory),
                selected = subTab == SubTab.Inventory,
                enabled = true,
                onClick = { subTab = SubTab.Inventory },
            )
        }

        when (subTab) {
            SubTab.Configurable -> {
                // The plane must have answered for any of this sub-tab's rows to exist.
                if (planeUi.status is SettingsPlane.PlaneStatus.Ready) {
                    planeUi.namespace(SHELL_NS)?.let { view ->
                        ShellCard(view, editable) { ns, key, text ->
                            scope.launch {
                                ToastFunnel.report(viewModel.setIntField(ns, key, text), R.string.hset_saved)
                            }
                        }
                    }
                    planeUi.namespace(AGENT_LOOP_NS)?.let { view ->
                        AgentLoopCard(view, editable) { ns, key, text ->
                            scope.launch {
                                ToastFunnel.report(viewModel.setIntField(ns, key, text), R.string.hset_saved)
                            }
                        }
                    }
                    planeUi.namespace(WEB_SEARCH_NS)?.let { view ->
                        WebSearchCard(
                            view = view,
                            editable = editable,
                            onField = { ns, key, text ->
                                scope.launch {
                                    ToastFunnel.report(viewModel.setStringField(ns, key, text), R.string.hset_saved)
                                }
                            },
                            onKey = { key ->
                                scope.launch {
                                    ToastFunnel.report(
                                        viewModel.setSectionKey(WEB_SEARCH_NS, DEFAULT_WEB_SEARCH_KEY, key),
                                        R.string.hset_saved,
                                    )
                                }
                            },
                        )
                    }

                    // Everything else the plane serves: read-only rows, a bonus over the web page.
                    val known = setOf(SHELL_NS, AGENT_LOOP_NS, WEB_SEARCH_NS,
                        "llm-deepseek", "llm-pi-ai", "agent-presets", "permission", "locale",
                        "ui-theme", "ui-conversation")
                    val others = planeUi.namespaces.filter { it.ns !in known }
                    if (others.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.hset_plugins_other))
                        DsCard {
                            others.forEach { view ->
                                Row(Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall)) {
                                    Text(
                                        view.ns,
                                        style = DsType.small13,
                                        color = colors.labelSecondary,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        view.applies,
                                        style = DsType.caption11,
                                        color = colors.labelTertiary,
                                    )
                                }
                            }
                        }
                    }
                    if (!editable) {
                        Text(
                            stringResource(R.string.hset_readonly_banner),
                            style = DsType.caption11,
                            color = colors.labelTertiary,
                        )
                    }
                }
            }

            SubTab.Inventory -> {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.hset_plugins_inventory_search), style = DsType.small13) },
                    colors = dialogTextFieldColors(),
                )
                val entries = inventory?.entries.orEmpty()
                val filtered = if (query.isBlank()) entries
                else entries.filter { entry ->
                    val needle = query.trim().lowercase()
                    entry.moduleName.contains(needle, ignoreCase = true) ||
                        entry.entryId.contains(needle, ignoreCase = true)
                }
                if (inventory == null) {
                    Text(
                        stringResource(R.string.common_loading),
                        style = DsType.small13,
                        color = colors.labelTertiary,
                    )
                } else if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.hset_plugins_inventory_empty),
                        style = DsType.small13,
                        color = colors.labelTertiary,
                    )
                } else {
                    DsCard {
                        filtered.forEach { entry ->
                            InventoryRow(entry)
                            Spacer(Modifier.height(DsSpacing.xsmall))
                        }
                    }
                }
            }
        }
    }
}

enum class SubTab { Configurable, Inventory }

// ------------------------------------------------------------------ plugin cards

/**
 * One plugin card: the card's fields, a base-value hint when the user layer overrides, and a
 * Save button that writes only the fields the user actually moved.
 */
@Composable
private fun PluginCard(
    title: String,
    view: SettingsNamespaceView,
    editable: Boolean,
    fields: List<PluginField>,
    onSave: (Int, String, String) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = DsTheme.colors
    var texts by remember(view.revision) {
        mutableStateOf(fields.map { intFieldText(jsonAt(view.value, it.path)) })
    }
    var baseHints by remember(view.revision) {
        mutableStateOf(fields.map { field ->
            val base = intFieldText(jsonAt(view.base, field.path))
            if (base.isNotEmpty() && base != intFieldText(jsonAt(view.value, field.path))) base else null
        })
    }
    val dirty = fields.indices.any { it ->
        texts[it].isNotBlank() && texts[it] != intFieldText(jsonAt(view.value, fields[it].path)) ||
            (texts[it].isBlank() && intFieldText(jsonAt(view.value, fields[it].path)).isNotBlank())
    }

    DsCard {
        Text(title, style = DsType.std14, color = colors.labelPrimary)
        fields.forEachIndexed { index, field ->
            Column(Modifier.padding(vertical = DsSpacing.xsmall)) {
                Text(
                    stringResource(field.labelRes),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
                Spacer(Modifier.height(DsSpacing.xsmall))
                TextField(
                    value = texts[index],
                    onValueChange = { next ->
                        val updated = texts.toMutableList()
                        updated[index] = next
                        texts = updated
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = editable,
                    colors = dialogTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                val base = baseHints[index]
                if (base != null) {
                    Text(
                        stringResource(R.string.hset_plugins_base_value, base),
                        style = DsType.caption11,
                        color = colors.labelCaption,
                    )
                }
            }
        }
        extra?.invoke()
        if (editable) {
            Spacer(Modifier.height(DsSpacing.xsmall))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DsButton(
                    text = stringResource(R.string.common_save),
                    enabled = dirty,
                    onClick = {
                        fields.forEachIndexed { index, field ->
                            val current = intFieldText(jsonAt(view.value, field.path))
                            if (texts[index].isNotBlank() && texts[index] != current) {
                                onSave(index, field.path.first(), texts[index])
                            } else if (texts[index].isBlank() && current.isNotBlank()) {
                                onSave(index, field.path.first(), "")
                            }
                        }
                    },
                    size = DsButtonSize.Small,
                )
            }
        }
    }
}

/** One field a plugin card owns: its label and the path from the section root. */
data class PluginField(val labelRes: Int, val path: List<String>)

private val SHELL_NS = "shell"
private val AGENT_LOOP_NS = "agent-loop"
private val WEB_SEARCH_NS = "web-search-deepseek"
private val DEFAULT_WEB_SEARCH_KEY = "DEEPSEEK_API_KEY"

@Composable
private fun ShellCard(view: SettingsNamespaceView, editable: Boolean, onSave: (String, String, String) -> Unit) {
    PluginCard(
        title = stringResource(R.string.hset_plugins_card_shell),
        view = view,
        editable = editable,
        fields = listOf(
            PluginField(R.string.hset_plugins_shell_timeout, listOf("timeoutMs")),
            PluginField(R.string.hset_plugins_shell_max_output, listOf("maxOutputBytes")),
        ),
        onSave = { _, key, text -> onSave(view.ns, key, text) },
    )
}

@Composable
private fun AgentLoopCard(view: SettingsNamespaceView, editable: Boolean, onSave: (String, String, String) -> Unit) {
    PluginCard(
        title = stringResource(R.string.hset_plugins_card_agent_loop),
        view = view,
        editable = editable,
        fields = listOf(PluginField(R.string.hset_plugins_agent_loop_max_parallel, listOf("maxParallelToolCalls"))),
        onSave = { _, key, text -> onSave(view.ns, key, text) },
    )
}

@Composable
private fun WebSearchCard(
    view: SettingsNamespaceView,
    editable: Boolean,
    onField: (String, String, String) -> Unit,
    onKey: (String) -> Unit,
) {
    val colors = DsTheme.colors
    var baseURL by remember(view.revision) { mutableStateOf(stringFieldText(jsonAt(view.value, listOf("baseURL")))) }
    var maxUses by remember(view.revision) { mutableStateOf(intFieldText(jsonAt(view.value, listOf("maxUses")))) }
    var keyDraft by remember { mutableStateOf("") }
    val keyRef = keyRefFor("deepseek-official", view.value as? kotlinx.serialization.json.JsonObject)
    val baseURLDirty = baseURL.isNotBlank() && baseURL.trim() != stringFieldText(jsonAt(view.value, listOf("baseURL")))
    val maxUsesDirty = (maxUses.isNotBlank() && maxUses.trim() != intFieldText(jsonAt(view.value, listOf("maxUses")))) ||
        (maxUses.isBlank() && intFieldText(jsonAt(view.value, listOf("maxUses"))).isNotBlank())
    DsCard {
        Text(stringResource(R.string.hset_plugins_card_web_search), style = DsType.std14, color = colors.labelPrimary)
        Column(Modifier.padding(vertical = DsSpacing.xsmall)) {
            Text(stringResource(R.string.hset_plugins_web_search_base_url), style = DsType.small13, color = colors.labelSecondary)
            Spacer(Modifier.height(DsSpacing.xsmall))
            TextField(
                value = baseURL,
                onValueChange = { baseURL = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = editable,
                colors = dialogTextFieldColors(),
            )
        }
        Column(Modifier.padding(vertical = DsSpacing.xsmall)) {
            Text(stringResource(R.string.hset_plugins_web_search_max_uses), style = DsType.small13, color = colors.labelSecondary)
            Spacer(Modifier.height(DsSpacing.xsmall))
            TextField(
                value = maxUses,
                onValueChange = { maxUses = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = editable,
                colors = dialogTextFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        KeyField(
            keyDraft = keyDraft,
            onKeyChange = { keyDraft = it },
            keyRef = keyRef,
            configured = false,
        )
        if (editable) {
            Spacer(Modifier.height(DsSpacing.xsmall))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DsButton(
                    text = stringResource(R.string.common_save),
                    enabled = baseURLDirty || maxUsesDirty || keyDraft.isNotBlank(),
                    onClick = {
                        // Only the fields the user actually moved cross the wire; a blank sent
                        // for an untouched field would clear its override.
                        if (baseURLDirty) onField(view.ns, "baseURL", baseURL)
                        if (maxUsesDirty) onField(view.ns, "maxUses", maxUses)
                        if (keyDraft.isNotBlank()) onKey(keyDraft)
                        keyDraft = ""
                    },
                    size = DsButtonSize.Small,
                )
            }
        }
    }
}

/** One composed-plugin entry row: phase dot, module, entry id, enablement. */
@Composable
private fun InventoryRow(entry: PluginInventoryEntry) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StateDot(state = when (entry.fiberPhase) {
            PluginFiberPhase.Active -> StateDotState.Done
            PluginFiberPhase.Loading, PluginFiberPhase.Pending, PluginFiberPhase.Unloading -> StateDotState.Running
            PluginFiberPhase.Failed -> StateDotState.Error
            PluginFiberPhase.Unknown -> StateDotState.Warning
            null -> StateDotState.Idle
        })
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.moduleName,
                style = DsType.small13,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.entryId,
                style = DsType.caption11.copy(fontFamily = DsType.codeFont, color = colors.labelCaption),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!entry.enabled) {
            DsPill(text = stringResource(R.string.hset_plugins_disabled), warn = true)
        }
    }
}
