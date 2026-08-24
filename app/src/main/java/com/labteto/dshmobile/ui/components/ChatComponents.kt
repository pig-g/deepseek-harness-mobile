package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import java.util.Locale

/**
 * Right-aligned user message bubble (r22, userBubble fill, hairline border, max 320dp wide).
 *
 * The fill alone cannot draw the shape: the transcript is painted on `bgBase`, which is pure white
 * in the light theme, and `userBubble` is `#EDF3FE` — a 1.06:1 ratio against it. The bubble was
 * being drawn and read as plain text. `borderL2` gives it an edge in both themes without moving off
 * the harness's own fill token.
 *
 * The text is selectable (long-press) and any bare `http(s)` URL is tappable.
 */
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val linkStyle = SpanStyle(color = colors.accent, textDecoration = TextDecoration.LineThrough)
    val result = remember(text, linkStyle) { linkifyUrls(text, linkStyle) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        SelectionContainer {
            SelectableText(
                result.text,
                result.links,
                style = DsType.bubbleText.copy(color = colors.labelPrimary),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(colors.userBubble, DsShapes.bubble)
                    .border(1.dp, colors.borderL2, DsShapes.bubble)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** 24dp disclosure row for collapsible thinking blocks; muted, never italic. */
@Composable
fun ThinkingRow(
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    streaming: Boolean = false,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .clip(DsShapes.row)
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (streaming) {
            StateDot(StateDotState.Running, size = 8.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            summary ?: "Thinking…",
            style = DsType.mdSmall.copy(color = colors.labelTertiary),
            color = colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = DsAnimations.chevron,
            label = "thinkingChevron",
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(R.string.chat_thinking),
            tint = colors.labelTertiary,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/** Full-width top strip announcing a connection problem. */
@Composable
fun ConnectionBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DsTheme.colors.error)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            message,
            style = DsType.small13,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Centered run statistics with tabular numerals. */
@Composable
fun StatsLine(
    turns: Int,
    steps: Int,
    llmMs: Long? = null,
    ttftMs: Long? = null,
    tokPerSec: Double? = null,
) {
    val colors = DsTheme.colors
    val text = buildString {
        append("Turns $turns")
        append(" · Steps $steps")
        append(" · LLM ${formatDuration(llmMs)}")
        append(" · TTFT ${formatDuration(ttftMs)}")
        append(" · ${formatRate(tokPerSec)} tok/s")
    }
    Text(
        text,
        style = DsType.statsText.copy(color = colors.labelCaption, fontFeatureSettings = "tnum"),
        color = colors.labelCaption,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatDuration(ms: Long?): String = when {
    ms == null -> "—"
    ms >= 1000 -> "%.1fs".format(Locale.US, ms / 1000.0)
    else -> "${ms}ms"
}

private fun formatRate(perSec: Double?): String =
    perSec?.let { "%.1f".format(Locale.US, it) } ?: "—"

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ChatComponentsPreview() {
    DshTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            UserBubble("Build the shared component library.")
            ThinkingRow("Working through the diff…", expanded = false, onToggle = {}, streaming = true)
            ConnectionBanner("Connection lost — retrying…")
            StatsLine(turns = 3, steps = 12, llmMs = 2100, ttftMs = 420, tokPerSec = 18.4)
        }
    }
}
