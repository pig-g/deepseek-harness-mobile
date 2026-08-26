package com.labteto.dshmobile.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.core.DshCore
import com.labteto.dshmobile.data.SettingsPlane
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.screens.harness.HarnessSettingsScreen
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * App settings, grouped into cards.
 *
 * Only the top group is genuinely the app's own; connection, harness facts and data are all about
 * the *link* to a harness. Keeping the read-only notice scoped to the harness group matters —
 * blanket-labelling the whole screen read-only, as it used to, tells users their own preferences
 * cannot be changed when they plainly can.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val planeUi by viewModel.planeUi.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val toast = rememberDsToast()
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showHarness by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    val hostsCleared = stringResource(R.string.settings_forget_hosts_done)
    val sessionsCleared = stringResource(R.string.settings_clear_last_sessions_done)

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DsIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = onClose,
                    )
                    Text(
                        stringResource(R.string.settings_title),
                        style = DsType.large20,
                        color = colors.labelPrimary,
                    )
                }

                SettingsCard(stringResource(R.string.settings_general)) {
                    LanguageRow(settings) { tag -> viewModel.set { it.copy(localeOverride = tag) } }
                    AppearanceRow(settings) { mode -> viewModel.set { it.copy(themePreference = mode) } }
                }

                SettingsCard(stringResource(R.string.settings_connection)) {
                    ConnectionSection(connectionState, onDisconnect = { showDisconnectDialog = true })
                    ToggleRow(
                        stringResource(R.string.connect_auto_last),
                        settings.autoConnectLast,
                    ) { viewModel.set { it.copy(autoConnectLast = !it.autoConnectLast) } }
                    ToggleRow(
                        stringResource(R.string.connect_auto_lan),
                        settings.autoConnectLan,
                    ) { viewModel.set { it.copy(autoConnectLan = !it.autoConnectLan) } }
                    ToggleRow(
                        stringResource(R.string.connect_auto_loopback),
                        settings.autoConnectLoopback,
                    ) { viewModel.set { it.copy(autoConnectLoopback = !it.autoConnectLoopback) } }
                }

                SettingsCard(stringResource(R.string.settings_notifications)) {
                    ToggleRow(
                        stringResource(R.string.settings_notifications_turn),
                        settings.notifyTurnComplete,
                        stringResource(R.string.settings_notifications_turn_hint),
                    ) { viewModel.set { it.copy(notifyTurnComplete = !it.notifyTurnComplete) } }
                    ToggleRow(
                        stringResource(R.string.settings_notifications_goal),
                        settings.notifyGoal,
                        stringResource(R.string.settings_notifications_goal_hint),
                    ) { viewModel.set { it.copy(notifyGoal = !it.notifyGoal) } }
                    ToggleRow(
                        stringResource(R.string.settings_notifications_action),
                        settings.notifyNeedsAction,
                        stringResource(R.string.settings_notifications_action_hint),
                    ) { viewModel.set { it.copy(notifyNeedsAction = !it.notifyNeedsAction) } }
                    ToggleRow(
                        stringResource(R.string.settings_background),
                        settings.keepConnectedInBackground,
                        stringResource(R.string.settings_background_hint),
                    ) { viewModel.set { it.copy(keepConnectedInBackground = !it.keepConnectedInBackground) } }
                }

                SettingsCard(stringResource(R.string.settings_harness)) {
                    connectionState.description?.let { host ->
                        LabelledValue(
                            stringResource(R.string.settings_host_info),
                            stringResource(R.string.connect_harness_version, host.version, host.cwd),
                        )
                        LabelledValue(
                            stringResource(R.string.subagents_title),
                            stringResource(R.string.connect_attached_sessions, host.attachedSessions),
                        )
                        // The harness's own settings, one screen deep: the web GUI's tabs, native.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showHarness = true }
                                .padding(vertical = DsSpacing.xsmall),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.hset_entry),
                                style = DsType.std14,
                                color = colors.labelSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = colors.labelCaption,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    HarnessPlaneBanner(planeUi)
                }

                SettingsCard(stringResource(R.string.settings_data)) {
                    DsButton(
                        text = stringResource(R.string.settings_forget_hosts),
                        onClick = { viewModel.forgetHosts { toast.second(hostsCleared) } },
                        variant = DsButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DsButton(
                        text = stringResource(R.string.settings_clear_last_sessions),
                        onClick = { viewModel.clearLastSessions { toast.second(sessionsCleared) } },
                        variant = DsButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SettingsCard(stringResource(R.string.settings_about)) {
                    Text(
                        stringResource(
                            R.string.settings_about_version,
                            BuildConfig.VERSION_NAME,
                            DshCore.PROTOCOL_BASELINE,
                        ),
                        style = DsType.small13,
                        color = colors.labelTertiary,
                    )
                }

                Spacer(Modifier.height(DsSpacing.xlarge))
            }
            DsToastHost(toast, modifier = Modifier.fillMaxWidth())
        }
    }

    if (showHarness) {
        HarnessSettingsScreen(onClose = { showHarness = false })
    }

    if (showDisconnectDialog) {
        DsDialog(
            title = stringResource(R.string.settings_connection_disconnect_confirm),
            onDismiss = { showDisconnectDialog = false },
        ) {
            Text(
                stringResource(R.string.settings_connection_disconnect_message),
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.padding(bottom = DsSpacing.medium),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                DsButton(
                    text = stringResource(R.string.settings_connection_disconnect),
                    onClick = {
                        viewModel.disconnect()
                        showDisconnectDialog = false
                        onClose()
                    },
                    variant = DsButtonVariant.Danger,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { showDisconnectDialog = false },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

/** One settings group as a raised card, so groups read as blocks rather than a running list. */
/**
 * The harness card's plane status line: what the link can do to the harness's own settings.
 * The wording follows the mirror's answer, not the app's own preferences.
 */
@Composable
private fun HarnessPlaneBanner(planeUi: SettingsPlane.PlaneUi) {
    val colors = DsTheme.colors
    val (text, warn) = when (planeUi.status) {
        is SettingsPlane.PlaneStatus.Ready ->
            if (planeUi.writable) {
                stringResource(R.string.hset_edit_available) to false
            } else {
                stringResource(R.string.settings_readonly_banner) to true
            }
        is SettingsPlane.PlaneStatus.Refused ->
            if (planeUi.refusal == SettingsPlane.Refusal.Unsupported) {
                stringResource(R.string.hset_unsupported_banner) to true
            } else {
                stringResource(R.string.hset_refused_banner) to true
            }
        else -> stringResource(R.string.settings_readonly_banner) to true
    }
    Text(
        text,
        style = DsType.caption11,
        color = if (warn) colors.warnLabel else colors.labelTertiary,
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        SectionHeader(title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.block)
                .background(colors.bgLayer1)
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            content()
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DsType.std14, color = colors.labelSecondary, modifier = Modifier.weight(1f))
        Text(
            value,
            style = DsType.caption11,
            color = colors.labelTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f),
        )
    }
}

@Composable
private fun ConnectionSection(connectionState: ConnectionUiState, onDisconnect: () -> Unit) {
    val colors = DsTheme.colors
    val isConnected = connectionState.phase == ConnectionPhase.CONNECTED ||
        connectionState.phase == ConnectionPhase.RECONNECTING

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.settings_connection_status),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(
                when (connectionState.phase) {
                    ConnectionPhase.CONNECTED -> StateDotState.Done
                    ConnectionPhase.RECONNECTING, ConnectionPhase.CONNECTING -> StateDotState.Running
                    else -> StateDotState.Idle
                },
            )
            Spacer(Modifier.width(DsSpacing.small))
            Text(
                when (connectionState.phase) {
                    ConnectionPhase.CONNECTED -> stringResource(R.string.common_connected)
                    ConnectionPhase.RECONNECTING -> stringResource(R.string.common_reconnecting)
                    ConnectionPhase.CONNECTING -> stringResource(R.string.common_loading)
                    else -> stringResource(R.string.common_offline)
                },
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        }
    }

    if (isConnected && connectionState.host != null) {
        LabelledValue(
            stringResource(R.string.settings_connection_host),
            connectionState.host.authority,
        )
        DsButton(
            text = stringResource(R.string.settings_connection_disconnect),
            onClick = onDisconnect,
            variant = DsButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, hint: String? = null, onChange: () -> Unit) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .clickable(onClick = onChange)
            .padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = DsType.std14, color = colors.labelSecondary)
            if (hint != null) {
                Text(hint, style = DsType.caption11, color = colors.labelCaption)
            }
        }
        Switch(checked = checked, onCheckedChange = { onChange() })
    }
}

@Composable
private fun LanguageRow(settings: AppSettings, onSelect: (String?) -> Unit) {
    val colors = DsTheme.colors
    Column(modifier = Modifier.padding(vertical = DsSpacing.small)) {
        Text(
            stringResource(R.string.settings_language),
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        LanguageOptions.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { option ->
                    val selected = settings.localeOverride == option.tag
                    val label = option.label ?: option.labelRes?.let { stringResource(it) }.orEmpty()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = DsSpacing.xsmall, bottom = DsSpacing.xsmall)
                            .clip(DsShapes.block)
                            .background(if (selected) colors.accentTertiary else colors.bgModulePlatform)
                            .clickable { onSelect(option.tag) }
                            .padding(horizontal = 10.dp, vertical = DsSpacing.small),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                style = DsType.small13,
                                color = if (selected) colors.accent else colors.labelSecondary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
                // Keep the last partial row's cells the same width as a full one.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppearanceRow(settings: AppSettings, onSelect: (String) -> Unit) {
    val colors = DsTheme.colors
    Column(modifier = Modifier.padding(vertical = DsSpacing.small)) {
        Text(
            stringResource(R.string.settings_appearance),
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            AppearanceChip(
                stringResource(R.string.settings_appearance_light),
                settings.themePreference == "light",
            ) { onSelect("light") }
            AppearanceChip(
                stringResource(R.string.settings_appearance_dark),
                settings.themePreference == "dark",
            ) { onSelect("dark") }
            AppearanceChip(
                stringResource(R.string.settings_appearance_system),
                settings.themePreference == "system",
            ) { onSelect("system") }
        }
    }
}

@Composable
private fun AppearanceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Box(
        modifier = Modifier
            .clip(DsShapes.cube)
            .background(if (selected) colors.accentTertiary else colors.bgModulePlatform)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = DsSpacing.small),
    ) {
        Text(
            label,
            style = DsType.small13,
            color = if (selected) colors.accent else colors.labelSecondary,
        )
    }
}
