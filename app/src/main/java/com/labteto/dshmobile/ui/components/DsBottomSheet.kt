package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * Ceiling on a sheet's height as a fraction of the viewport. Leaves a scrim gap above the sheet
 * (so the dismiss-by-tap-outside affordance stays obvious) and keeps the title row clear of the
 * status bar.
 */
private const val MAX_HEIGHT_FRACTION = 0.92f

/**
 * The app's sheet surface, themed to the harness tokens.
 *
 * Sheets rather than dialogs for pickers: they arrive from the thumb's end of the screen, size
 * themselves to their content, and let a long list scroll without fighting a fixed-height plate.
 * [trailing] holds an optional action aligned with the title.
 *
 * The body scrolls and is capped at [MAX_HEIGHT_FRACTION] of the viewport, so a form taller than
 * the screen no longer clips its own tail. Pass the primary action through [footer] rather than
 * ending the content with it: the footer is pinned below the scroll region and stays reachable
 * however long the form grows (an "Add custom provider" sheet with several model rows would
 * otherwise push its Save button off-screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsBottomSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = modifier,
        shape = DsShapes.dialog,
        containerColor = colors.bgLayer2,
        scrimColor = colors.overlayMask,
        dragHandle = null,
        // Navigation bar plus the keyboard: these sheets are text-heavy, so a focused field at the
        // bottom of the scroll region must not end up behind the IME.
        contentWindowInsets = { WindowInsets.navigationBars.union(WindowInsets.ime) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(MAX_HEIGHT_FRACTION)
                .padding(horizontal = DsSpacing.large, vertical = DsSpacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            // A short grabber stands in for the platform drag handle so the sheet still reads as
            // draggable without the default's heavy vertical padding.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Spacer(
                    Modifier
                        .fillMaxWidth(0.12f)
                        .height(4.dp)
                        .clip(DsShapes.pillFull)
                        .background(colors.borderL3),
                )
            }
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = DsType.large20, color = colors.labelPrimary)
                        if (subtitle != null) {
                            Text(subtitle, style = DsType.caption11, color = colors.labelTertiary)
                        }
                    }
                    trailing?.invoke()
                }
            }
            // `fill = false` keeps a short sheet sized to its content (no dead space below the last
            // row) while a tall one takes the remaining height and scrolls inside it.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                content()
            }

            if (footer != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    HorizontalDivider(color = colors.borderL2)
                    footer()
                }
            }
            Spacer(Modifier.height(DsSpacing.small))
        }
    }
}
