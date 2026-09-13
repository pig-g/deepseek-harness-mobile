package com.labteto.dshmobile.ui.screens.harness

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.DiscoveredModelView
import com.labteto.dshmobile.core.wire.dto.LlmDiscoverModelsRequest
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.screens.main.dialogTextFieldColors
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The provider editor sheet: one provider route's profile fields, its API key, and its model
 * catalog. The write recipe is the web's `ProviderEditor.applyOnce` — the draft carries every
 * stored key so fields outside the card pass through untouched, and only the changed ones
 * become ops.
 */
@Composable
fun ProviderEditorSheet(
    viewModel: HarnessSettingsViewModel,
    row: HarnessSettingsViewModel.ProviderRowUi,
    mode: EditorMode,
    onDismiss: () -> Unit,
) {
    val layout = viewModel.layoutOf(row.entry.settingsNs)
    val entry = row.entry
    val before = row.profile
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors

    // The card's fields own these keys; everything else in the stored profile passes through.
    val managedKeys = remember(layout) {
        when (layout) {
            HarnessSettingsViewModel.Layout.DeepSeek -> listOf("baseURL", "models")
            HarnessSettingsViewModel.Layout.PiAi ->
                listOf("api", "baseURL", "models", "displayName", "apiKeyEnv")
            HarnessSettingsViewModel.Layout.Unknown -> emptyList()
        }
    }
    var passThrough by remember {
        mutableStateOf(draftOf(before).also { it.keys.removeAll { key -> key in managedKeys } })
    }
    var baseURL by remember { mutableStateOf(stringFieldText(before?.get("baseURL"))) }
    var api by remember { mutableStateOf(stringFieldText(before?.get("api"))) }
    var displayName by remember { mutableStateOf(stringFieldText(before?.get("displayName"))) }
    var modelRows by remember { mutableStateOf(modelRowsOf(before?.get("models"))) }
    var keyDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var discoverOpen by remember { mutableStateOf(false) }

    // The protocol choices: the shipped list, plus the stored value when it names one the
    // list does not (a forward-compatible harness), so nothing the host has can be lost.
    val protocolChoices = remember(before) {
        val stored = stringFieldText(before?.get("api"))
        val base = PIAI_PROTOCOLS.toMutableList()
        if (stored.isNotEmpty() && stored !in base) base.add(0, stored)
        base
    }
    val showDisplayName = entry.declared == true
    val discoverable = layout != HarnessSettingsViewModel.Layout.PiAi ||
        api in listOf("openai-completions", "openai-responses")

    // Computed here rather than inside the content lambda: the validation line and the Save
    // button both live in the pinned footer, which is a sibling of the scrolling body.
    val keyFailure = apiKeyFailure(keyDraft)
    val modelFailure = validateModelRows(modelRows)
    val apiMissing = layout == HarnessSettingsViewModel.Layout.PiAi && api.isBlank()

    DsBottomSheet(
        title = entry.displayName,
        subtitle = entry.provider,
        onDismiss = { if (!busy) onDismiss() },
        footer = {
            val failureText = failure
            if (failureText != null) {
                Text(failureText, style = DsType.small13, color = colors.error)
            } else if (keyFailure != null) {
                Text(
                    stringResource(
                        if (keyFailure == KeyFailure.Blank) R.string.hset_val_api_key_blank
                        else R.string.hset_val_api_key_format,
                    ),
                    style = DsType.small13,
                    color = colors.error,
                )
            } else if (modelFailure != null) {
                ModelFailureLine(modelFailure)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    variant = DsButtonVariant.Ghost,
                )
                Spacer(Modifier.weight(1f))
                DsButton(
                    text = stringResource(R.string.common_save),
                    enabled = !busy && layout != HarnessSettingsViewModel.Layout.Unknown
                        && keyFailure == null && modelFailure == null && !apiMissing,
                    onClick = {
                        busy = true
                        failure = null
                        scope.launch {
                            val draft = LinkedHashMap<String, JsonElement?>(passThrough)
                            applyManagedFields(
                                draft = draft,
                                layout = layout,
                                showDisplayName = showDisplayName,
                                api = api,
                                displayName = displayName,
                                baseURL = baseURL,
                                modelRows = modelRows,
                            )
                            val out = viewModel.saveProvider(
                                layout = layout,
                                settingsNs = entry.settingsNs,
                                settingsPath = entry.settingsPath,
                                provider = entry.provider,
                                before = before,
                                draft = draft,
                                key = keyDraft,
                            )
                            busy = false
                            if (out.ok) onDismiss() else failure = out.message ?: out.code ?: "error"
                        }
                    },
                )
            }
        },
    ) {
        when (layout) {
            HarnessSettingsViewModel.Layout.Unknown -> {
                Text(
                    stringResource(R.string.hset_models_unknown_ns, entry.settingsNs),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
                AdvancedFields(passThrough)
            }

            else -> {
                // ------------------------------------------------ API key
                KeyField(
                    keyDraft = keyDraft,
                    onKeyChange = { keyDraft = it },
                    keyRef = row.keyRef,
                    configured = row.keyConfigured,
                )

                // ------------------------------------------------ family fields
                if (layout == HarnessSettingsViewModel.Layout.PiAi) {
                    Column {
                        Text(
                            stringResource(R.string.hset_models_api_protocol),
                            style = DsType.small13,
                            color = colors.labelSecondary,
                        )
                        Spacer(Modifier.height(DsSpacing.xsmall))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                        ) {
                            protocolChoices.forEach { choice ->
                                GeneralChip(
                                    label = choice,
                                    selected = api == choice,
                                    enabled = true,
                                    onClick = { api = choice },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(DsSpacing.small))
                    if (showDisplayName) {
                        HarnessField(
                            label = stringResource(R.string.hset_models_display_name),
                            value = displayName,
                            onValueChange = { displayName = it },
                        )
                        Spacer(Modifier.height(DsSpacing.small))
                    }
                }
                HarnessField(
                    label = stringResource(R.string.hset_models_base_url),
                    value = baseURL,
                    onValueChange = { baseURL = it },
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(DsSpacing.small))

                // ------------------------------------------------ model catalog
                Text(
                    stringResource(R.string.hset_models_models_label),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
                if (modelRows.isEmpty()) {
                    Text(
                        stringResource(R.string.hset_models_models_hint_empty),
                        style = DsType.caption11,
                        color = colors.labelCaption,
                    )
                }
                val modelFailure = validateModelRows(modelRows)
                if (modelFailure != null) {
                    ModelFailureLine(modelFailure)
                }
                ModelRowsEditor(
                    rows = modelRows,
                    onRowsChange = { modelRows = it },
                    failure = modelFailure,
                )
                DsButton(
                    text = stringResource(R.string.hset_models_add_model),
                    onClick = { modelRows = modelRows + ModelRowDraft() },
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                    icon = Icons.Filled.Add,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(DsSpacing.small))
                if (discoverable) {
                    DsButton(
                        text = stringResource(R.string.hset_models_discover),
                        onClick = { discoverOpen = true },
                        variant = DsButtonVariant.Outline,
                        size = DsButtonSize.Small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ------------------------------------------------ advanced (read-only pass-through)
                if (passThrough.isNotEmpty()) {
                    Spacer(Modifier.height(DsSpacing.small))
                    Text(
                        stringResource(R.string.hset_models_advanced),
                        style = DsType.small13,
                        color = colors.labelSecondary,
                    )
                    AdvancedFields(passThrough)
                }
            }
        }
    }

    if (discoverOpen) {
        DiscoverModelsSheet(
            viewModel = viewModel,
            settingsNs = entry.settingsNs,
            provider = entry.provider,
            initialBaseURL = baseURL,
            initialApi = if (layout == HarnessSettingsViewModel.Layout.PiAi) api else null,
            initialKey = keyDraft,
            onDismiss = { discoverOpen = false },
            onApply = { adopted ->
                modelRows = adopted
                discoverOpen = false
            },
        )
    }
}

/** The card's managed fields applied over the pass-through draft; blanks drop the key. */
fun applyManagedFields(
    draft: LinkedHashMap<String, JsonElement?>,
    layout: HarnessSettingsViewModel.Layout,
    showDisplayName: Boolean,
    api: String,
    displayName: String,
    baseURL: String,
    modelRows: List<ModelRowDraft>,
) {
    val base = baseURL.trim()
    if (base.isEmpty()) {
        draft.remove("baseURL")
    } else {
        draft["baseURL"] = JsonPrimitive(base)
    }
    if (layout == HarnessSettingsViewModel.Layout.PiAi) {
        val trimmed = api.trim()
        if (trimmed.isEmpty()) {
            draft.remove("api")
        } else {
            draft["api"] = JsonPrimitive(trimmed)
        }
        if (showDisplayName && displayName.isNotBlank()) {
            draft["displayName"] = JsonPrimitive(displayName.trim())
        } else {
            draft.remove("displayName")
        }
    }
    val models = modelRows.map { modelRowToJson(it) }
    if (models.isEmpty()) {
        draft.remove("models")
    } else {
        draft["models"] = JsonArray(models.map { it as JsonElement })
    }
}

/** The API-key field: write-only input with the stored state beside it. Shared with the plugins tab. */
@Composable
fun KeyField(
    keyDraft: String,
    onKeyChange: (String) -> Unit,
    keyRef: String,
    configured: Boolean,
) {
    val colors = DsTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.hset_models_key_label),
                style = DsType.small13,
                color = colors.labelSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(
                    if (configured) R.string.hset_models_key_configured else R.string.hset_models_key_missing,
                ),
                style = DsType.caption11,
                color = if (configured) colors.labelTertiary else colors.warnLabel,
            )
        }
        Spacer(Modifier.height(DsSpacing.xsmall))
        TextField(
            value = keyDraft,
            onValueChange = onKeyChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = dialogTextFieldColors(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        Spacer(Modifier.height(DsSpacing.xsmall))
        Text(
            stringResource(R.string.hset_models_key_hint, keyRef),
            style = DsType.caption11,
            color = colors.labelCaption,
        )
    }
}

/**
 * The model-catalog rows: four fields per row, a remove action, and the add button's target
 * list. Blank id/name/capacities are legal (the host applies its defaults); the validator
 * names the first bad row and field.
 */
@Composable
fun ModelRowsEditor(
    rows: List<ModelRowDraft>,
    onRowsChange: (List<ModelRowDraft>) -> Unit,
    failure: ModelFailure?,
) {
    val colors = DsTheme.colors
    rows.forEachIndexed { index, row ->
        val failing = failure?.index == index
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = DsSpacing.xsmall),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
                HarnessField(
                    label = stringResource(R.string.hset_models_model_id),
                    value = row.id,
                    onValueChange = { id -> onRowsChange(replace(rows, index) { it.copy(id = id) }) },
                    modifier = Modifier.weight(1f),
                )
                HarnessField(
                    label = stringResource(R.string.hset_models_model_name),
                    value = row.name,
                    onValueChange = { name -> onRowsChange(replace(rows, index) { it.copy(name = name) }) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
                HarnessField(
                    label = stringResource(R.string.hset_models_model_context),
                    value = row.contextWindow,
                    onValueChange = { ctx -> onRowsChange(replace(rows, index) { it.copy(contextWindow = ctx) }) },
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number,
                )
                HarnessField(
                    label = stringResource(R.string.hset_models_model_max_tokens),
                    value = row.maxTokens,
                    onValueChange = { max -> onRowsChange(replace(rows, index) { it.copy(maxTokens = max) }) },
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (failing) {
                    ModelFailureLine(failure!!, modifier = Modifier.weight(1f))
                }
                DsIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.hset_models_remove_model),
                    onClick = { onRowsChange(rows.filterIndexed { i, _ -> i != index }) },
                )
            }
        }
    }
}

private fun replace(rows: List<ModelRowDraft>, index: Int, transform: (ModelRowDraft) -> ModelRowDraft): List<ModelRowDraft> =
    rows.mapIndexed { i, row -> if (i == index) transform(row) else row }

/** The inline failure line for a row list. */
@Composable
fun ModelFailureLine(failure: ModelFailure, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val message = when (failure.field) {
        ModelField.Id -> stringResource(
            if (failure.duplicate) R.string.hset_val_model_id_duplicate
            else R.string.hset_val_model_id_required,
        )
        ModelField.Name -> stringResource(R.string.hset_val_model_name)
        ModelField.ContextWindow -> stringResource(R.string.hset_val_model_context)
        ModelField.MaxTokens -> stringResource(R.string.hset_val_model_max_tokens)
    }
    Text(
        stringResource(R.string.hset_val_model_prefix, failure.index + 1, message),
        style = DsType.caption11,
        color = colors.error,
        modifier = modifier,
    )
}

/** One stored-but-unmanaged field, read-only: the editor will not touch it on save. */
@Composable
private fun AdvancedFields(fields: Map<String, JsonElement?>) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        fields.forEach { (key, value) ->
            Text(
                "$key: ${valueSummary(value)}",
                style = DsType.caption11.copy(fontFamily = DsType.codeFont, color = colors.labelCaption),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun valueSummary(value: JsonElement?): String = when (value) {
    is JsonPrimitive -> if (value.isString) value.content else value.toString()
    else -> value.toString().take(40)
}

// ------------------------------------------------------------------ discover sheet

/**
 * The discovery sheet: the harness interrogates the draft endpoint and hands back its model
 * list; the checked rows are adopted into the editor's catalog on Apply.
 */
@Composable
fun DiscoverModelsSheet(
    viewModel: HarnessSettingsViewModel,
    settingsNs: String,
    provider: String?,
    initialBaseURL: String,
    initialApi: String?,
    initialKey: String,
    onDismiss: () -> Unit,
    onApply: (List<ModelRowDraft>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    var baseURL by remember { mutableStateOf(initialBaseURL) }
    var api by remember { mutableStateOf(initialApi.orEmpty()) }
    var key by remember { mutableStateOf(initialKey) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<DiscoveredModelView>?>(null) }
    var checked by remember { mutableStateOf<Set<Int>>(emptySet()) }

    DsBottomSheet(
        title = stringResource(R.string.hset_models_discover_title),
        subtitle = provider,
        onDismiss = { if (!busy) onDismiss() },
        // Fetch/Apply stay pinned: the discovered list above can grow past the viewport.
        footer = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = stringResource(R.string.hset_models_discover_fetch),
                    enabled = !busy && baseURL.isNotBlank(),
                    onClick = {
                        busy = true
                        failure = null
                        scope.launch {
                            val discovery = viewModel.discover(
                                LlmDiscoverModelsRequest(
                                    settingsNs = settingsNs,
                                    provider = provider,
                                    baseURL = baseURL.trim().takeIf { it.isNotEmpty() },
                                    api = api.trim().takeIf { it.isNotEmpty() },
                                    apiKey = key.trim().takeIf { it.isNotEmpty() },
                                ),
                            )
                            busy = false
                            if (discovery.failure != null) {
                                failure = discovery.failure
                            } else {
                                results = discovery.models
                                checked = discovery.models.indices.toSet()
                            }
                        }
                    },
                    variant = DsButtonVariant.Info,
                    size = DsButtonSize.Small,
                )
                Spacer(Modifier.weight(1f))
                DsButton(
                    text = stringResource(R.string.hset_models_discover_apply, checked.size),
                    enabled = (results?.size ?: 0) > 0 && checked.isNotEmpty(),
                    onClick = {
                        val found = results.orEmpty()
                        onApply(
                            found
                                .filterIndexed { index, _ -> index in checked }
                                .map { model ->
                                    ModelRowDraft(
                                        id = model.id,
                                        name = model.name.orEmpty(),
                                        contextWindow = model.contextWindow?.toString().orEmpty(),
                                        maxTokens = model.maxTokens?.toString().orEmpty(),
                                    )
                                },
                        )
                    },
                    variant = DsButtonVariant.Primary,
                    size = DsButtonSize.Small,
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.hset_models_discover_hint),
            style = DsType.caption11,
            color = colors.labelCaption,
        )
        HarnessField(
            label = stringResource(R.string.hset_models_base_url),
            value = baseURL,
            onValueChange = { baseURL = it },
            keyboardType = KeyboardType.Uri,
        )
        if (initialApi != null) {
            HarnessField(
                label = stringResource(R.string.hset_models_api_protocol),
                value = api,
                onValueChange = { api = it },
            )
        }
        KeyField(
            keyDraft = key,
            onKeyChange = { key = it },
            keyRef = keyRefFor(provider.orEmpty(), null),
            configured = false,
        )

        val failureText = failure
        if (failureText != null) {
            Text(
                stringResource(R.string.hset_models_discover_failed, failureText),
                style = DsType.small13,
                color = colors.error,
            )
        }
        results?.let { found ->
            if (found.isEmpty()) {
                Text(
                    stringResource(R.string.hset_models_discover_none),
                    style = DsType.small13,
                    color = colors.labelTertiary,
                )
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    found.forEachIndexed { index, model ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = index in checked,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + index else checked - index
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    model.id,
                                    style = DsType.small13,
                                    color = colors.labelPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val detail = listOfNotNull(
                                    model.name,
                                    model.contextWindow?.let { "$it" },
                                ).joinToString(" · ")
                                if (detail.isNotEmpty()) {
                                    Text(
                                        detail,
                                        style = DsType.caption11,
                                        color = colors.labelTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ custom provider sheet

/**
 * The "add custom provider" sheet: a fresh pi-ai route, named and addressed by the user. The
 * write is one `set` op materializing the profile, then the key through the credentials face.
 */
@Composable
fun CustomProviderSheet(
    viewModel: HarnessSettingsViewModel,
    settingsNs: String,
    existingRoutes: Set<String>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    var route by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var api by remember { mutableStateOf(PIAI_PROTOCOLS.first()) }
    var baseURL by remember { mutableStateOf("") }
    var modelRows by remember { mutableStateOf(emptyList<ModelRowDraft>()) }
    var keyDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val routeTaken = route.isNotBlank() && route.trim() in existingRoutes
    val routeInvalid = route.isNotBlank() && !ROUTE_ID_PATTERN.matches(route.trim())
    val keyFailure = apiKeyFailure(keyDraft)
    val modelFailure = validateModelRows(modelRows)
    val canSave = !busy &&
        route.isNotBlank() && !routeInvalid && !routeTaken &&
        baseURL.isNotBlank() &&
        modelRows.isNotEmpty() && modelFailure == null &&
        keyFailure == null

    DsBottomSheet(
        title = stringResource(R.string.hset_models_add_custom),
        onDismiss = { if (!busy) onDismiss() },
        footer = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    variant = DsButtonVariant.Ghost,
                )
                Spacer(Modifier.weight(1f))
                DsButton(
                    text = stringResource(R.string.common_save),
                    enabled = canSave,
                    onClick = {
                        busy = true
                        scope.launch {
                            val out = viewModel.addCustomProvider(
                                settingsNs = settingsNs,
                                route = route.trim(),
                                displayName = displayName,
                                api = api,
                                baseURL = baseURL,
                                rows = modelRows,
                                key = keyDraft,
                            )
                            busy = false
                            if (out.ok) onDismiss() else {
                                // The write failed: surface the harness's reason through the funnel
                                // and keep the sheet open so the user can correct and retry.
                                ToastFunnel.report(out, R.string.hset_saved)
                            }
                        }
                    },
                )
            }
        },
    ) {
        HarnessField(
            label = stringResource(R.string.hset_models_custom_route),
            value = route,
            onValueChange = { route = it },
            keyboardType = KeyboardType.Ascii,
            hint = if (routeInvalid) stringResource(R.string.hset_val_route_invalid)
            else if (routeTaken) stringResource(R.string.hset_val_route_taken)
            else null,
        )
        Spacer(Modifier.height(DsSpacing.small))
        HarnessField(
            label = stringResource(R.string.hset_models_display_name),
            value = displayName,
            onValueChange = { displayName = it },
        )
        Spacer(Modifier.height(DsSpacing.small))
        Column {
            Text(
                stringResource(R.string.hset_models_api_protocol),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            Spacer(Modifier.height(DsSpacing.xsmall))
            // Horizontally scrollable: the protocol names are long ("openai-completions") and a
            // plain Row would clip the later chips off the right edge on a phone.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                PIAI_PROTOCOLS.forEach { choice ->
                    GeneralChip(
                        label = choice,
                        selected = api == choice,
                        enabled = true,
                        onClick = { api = choice },
                    )
                }
            }
        }
        Spacer(Modifier.height(DsSpacing.small))
        HarnessField(
            label = stringResource(R.string.hset_models_base_url),
            value = baseURL,
            onValueChange = { baseURL = it },
            keyboardType = KeyboardType.Uri,
            hint = if (baseURL.isBlank()) stringResource(R.string.hset_val_base_url_required) else null,
        )
        Spacer(Modifier.height(DsSpacing.small))
        KeyField(
            keyDraft = keyDraft,
            onKeyChange = { keyDraft = it },
            keyRef = deriveKeyRef(route.trim().ifEmpty { "custom" }),
            configured = false,
        )
        Spacer(Modifier.height(DsSpacing.small))
        if (modelRows.isEmpty()) {
            Text(
                stringResource(R.string.hset_val_models_required),
                style = DsType.caption11,
                color = colors.labelCaption,
            )
        }
        if (modelFailure != null) {
            ModelFailureLine(modelFailure)
        }
        ModelRowsEditor(
            rows = modelRows,
            onRowsChange = { modelRows = it },
            failure = modelFailure,
        )
        DsButton(
            text = stringResource(R.string.hset_models_add_model),
            onClick = { modelRows = modelRows + ModelRowDraft() },
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            icon = Icons.Filled.Add,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
