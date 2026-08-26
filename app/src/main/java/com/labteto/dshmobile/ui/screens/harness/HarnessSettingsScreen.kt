package com.labteto.dshmobile.ui.screens.harness

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.SettingsPlane
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.screens.main.dialogTextFieldColors
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The harness's own settings, mirroring the web GUI's settings tabs: General, Models, Plugins,
 * and Agent presets.
 *
 * The screen degrades by status: a refused plane (the harness started without
 * `--allow-privileged-remote`) shows the fence banner and read-only rows; a writable plane
 * offers the same edits the browser does, through the [SettingsPlane] mirror.
 */
@Composable
fun HarnessSettingsScreen(onClose: () -> Unit, viewModel: HarnessSettingsViewModel = hiltViewModel()) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val planeUi by viewModel.planeUi.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val toast = rememberDsToast()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BackHandler(onBack = onClose)

    // Seed the mirror once the screen is up; the plane itself is the idempotency guard.
    LaunchedEffect(Unit) { viewModel.loadPlane() }

    // A single toast funnel: the tabs hand write outcomes (or raw messages) in here, so the
    // wording lives once.
    ToastFunnel.current = { outcome, success ->
        scope.launch {
            val text = when {
                outcome.ok -> context.getString(success)
                outcome.code == "not-connected" -> context.getString(R.string.hset_not_connected)
                outcome.code == "settings-conflict" -> context.getString(R.string.hset_conflict)
                else -> context.getString(R.string.hset_save_failed, outcome.message ?: outcome.code.orEmpty())
            }
            toast.second(text)
        }
    }
    ToastFunnel.raw = { text -> scope.launch { toast.second(text) } }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DsIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = onClose,
                    )
                    Text(
                        stringResource(R.string.hset_title),
                        style = DsType.large20,
                        color = colors.labelPrimary,
                    )
                }

                // The plane's refusal, when it has answered one: edits are gone, reads remain.
                when (planeUi.status) {
                    is SettingsPlane.PlaneStatus.Refused -> PlaneNotice(
                        text = stringResource(
                            if (planeUi.refusal == SettingsPlane.Refusal.Unsupported) {
                                R.string.hset_unsupported_banner
                            } else {
                                R.string.hset_refused_banner
                            },
                        ),
                    )
                    is SettingsPlane.PlaneStatus.Unavailable -> PlaneNotice(
                        text = stringResource(R.string.hset_load_error, planeUi.loadError.orEmpty()),
                        retry = { viewModel.refreshPlane() },
                    )
                    else -> Unit
                }

                // Read-only provider: describe answered but writes are refused.
                if (planeUi.status is SettingsPlane.PlaneStatus.Ready && !planeUi.writable) {
                    PlaneNotice(text = stringResource(R.string.hset_readonly_banner))
                }

                HarnessTabRow(selected = tab, onSelect = { viewModel.selectTab(it) })

                Spacer(Modifier.height(DsSpacing.medium))

                Box(Modifier.fillMaxSize()) {
                    when (tab) {
                        HarnessSettingsViewModel.Tab.General -> GeneralTab(viewModel)
                        HarnessSettingsViewModel.Tab.Models -> ModelsTab(viewModel)
                        HarnessSettingsViewModel.Tab.Plugins -> PluginsTab(viewModel)
                        HarnessSettingsViewModel.Tab.Presets -> PresetsTab(viewModel)
                    }
                }
            }
            DsToastHost(toast, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
    }
}

/**
 * The section's toast funnel. The tab composables report write outcomes here rather than
 * threading a toast callback through every sheet; [raw] carries messages with no outcome
 * (document opens, discovery results).
 */
object ToastFunnel {
    var current: (SettingsPlane.WriteOutcome, success: Int) -> Unit = { _, _ -> }
    var raw: (String) -> Unit = {}

    /** Report one write outcome with its success string. */
    fun report(outcome: SettingsPlane.WriteOutcome, success: Int) = current(outcome, success)

    /** Show one pre-resolved message. */
    fun toast(text: String) = raw(text)
}

/** A full-width status notice under the tab row: the fence, a failure with retry, or read-only. */
@Composable
private fun PlaneNotice(text: String, retry: (() -> Unit)? = null) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DsSpacing.medium)
            .clip(DsShapes.block)
            .background(colors.warnTertiary)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = DsType.caption11,
            color = colors.warnLabel,
            modifier = Modifier.weight(1f),
        )
        if (retry != null) {
            Text(
                stringResource(R.string.hset_retry),
                style = DsType.small13,
                color = colors.accent,
                modifier = Modifier
                    .clip(DsShapes.pill)
                    .clickable { retry() }
                    .padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
            )
        }
    }
}

/** The four section pills, in the web GUI's order. */
@Composable
private fun HarnessTabRow(
    selected: HarnessSettingsViewModel.Tab,
    onSelect: (HarnessSettingsViewModel.Tab) -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = DsSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        listOf(
            HarnessSettingsViewModel.Tab.General to R.string.hset_tab_general,
            HarnessSettingsViewModel.Tab.Models to R.string.hset_tab_models,
            HarnessSettingsViewModel.Tab.Plugins to R.string.hset_tab_plugins,
            HarnessSettingsViewModel.Tab.Presets to R.string.hset_tab_presets,
        ).forEach { (value, res) ->
            val chosen = value == selected
            Row(
                modifier = Modifier
                    .clip(DsShapes.cube)
                    .background(if (chosen) colors.accentTertiary else colors.bgModulePlatform)
                    .clickable { onSelect(value) }
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            ) {
                Text(
                    stringResource(res),
                    style = DsType.small13,
                    color = if (chosen) colors.accent else colors.labelSecondary,
                )
            }
        }
    }
}

/**
 * One labelled single-line field for the harness forms, matching the app's dialog field styling.
 */
@Composable
fun HarnessField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = DsTheme.colors
    Column(modifier = modifier) {
        Text(label, style = DsType.small13, color = colors.labelSecondary)
        Spacer(Modifier.height(DsSpacing.xsmall))
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = dialogTextFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
        if (hint != null) {
            Text(hint, style = DsType.caption11, color = colors.labelCaption)
        }
    }
}
