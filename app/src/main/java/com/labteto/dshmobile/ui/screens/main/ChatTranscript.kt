package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay

/**
 * Scroll offset that pins the newest content to the bottom edge of the viewport rather than just
 * the top of the last message. Used as the `scrollOffset` in LazyListState scrolls — the framework
 * clamps it to the end of the list, so the tail (not the head) of a long reply is what's shown.
 */
private const val BOTTOM_ANCHOR_OFFSET = Int.MAX_VALUE

/**
 * How close to the top a reader must get before the next page is fetched.
 *
 * Deliberately not zero. Item keys are stable, so the list re-anchors the viewport on the key that
 * was first visible when older items are prepended — which only holds a reader's place if that
 * anchor is a message row that moves down with the rest. The paging row sits at index 0 and stays
 * there, so if it is the anchor the prepended page pushes everything being read below the fold.
 * Firing a couple of rows early keeps a `seq`-keyed message as the anchor.
 */
private const val LOAD_OLDER_THRESHOLD = 2

/**
 * The conversation itself.
 *
 * Auto-scroll only follows the tail when the reader is already there — scrolling back through a
 * long transcript while a turn streams should not keep yanking the view down. Scrolling the other
 * way pages history in without a button.
 */
@Composable
internal fun ChatTranscript(
    conversation: ConversationSnapshot?,
    loading: Boolean,
    loadingOlder: Boolean,
    loadOlderFailed: Boolean,
    openFailed: Boolean = false,
    onRetryOpen: () -> Unit = {},
    context: ChatNodeContext,
    listState: LazyListState,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    followHint: Int = 0,
) {
    // Only the nodes that draw something: a zero-height item still costs its 4dp gap, and a turn's
    // worth of structural events stacks those gaps into a blank band under the chrome.
    val nodes = remember(conversation?.nodes) {
        conversation?.nodes.orEmpty().filter { it.rendersContent() }
    }
    val hasMore = conversation?.hasMore == true
    val itemCount = nodes.size + if (hasMore) 1 else 0
    val sessionId = conversation?.sessionId

    // Both keyed on the session so a freshly opened one starts from a clean assumption rather than
    // inheriting the previous transcript's position — and so the collector always writes to the
    // state the composition is currently reading.
    var wasNearBottom by remember(sessionId) { mutableStateOf(true) }
    LaunchedEffect(listState, sessionId) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            total == 0 || last >= total - 2
        }.collect { wasNearBottom = it }
    }

    var lastSession by remember { mutableStateOf<String?>(null) }
    var lastFollowHint by remember { mutableIntStateOf(0) }
    LaunchedEffect(itemCount, sessionId, followHint) {
        if (itemCount == 0) return@LaunchedEffect
        val switched = sessionId != lastSession
        val hinted = followHint != lastFollowHint
        lastSession = sessionId
        lastFollowHint = followHint
        // A freshly opened session lands on its tail without animating the whole list there.
        // A just-dismissed interaction dock (user answered a question / approved) also snaps back
        // to the tail unconditionally so the agent's reply is revealed even if the reader had
        // scrolled up to study the question. Otherwise we only follow when already at the bottom.
        // The large scrollOffset anchors the newest content at the bottom edge so a long reply's
        // tail — not just its start — is what's on screen.
        if (switched) listState.scrollToItem(itemCount - 1, scrollOffset = BOTTOM_ANCHOR_OFFSET)
        else if (hinted) listState.scrollToItem(itemCount - 1, scrollOffset = BOTTOM_ANCHOR_OFFSET)
        else if (wasNearBottom) listState.animateScrollToItem(itemCount - 1, scrollOffset = BOTTOM_ANCHOR_OFFSET)
    }

    // While the agent is streaming and the reader is at the tail, keep the newest content in
    // view. The effect above only fires when a whole new node is added; an existing node growing
    // (a long reply streaming in) would otherwise shove the live edge below the fold. This cheap
    // polling loop re-anchors the tail without fighting an intentional scroll-away.
    val running = conversation?.running == true
    LaunchedEffect(running, sessionId) {
        if (!running || itemCount == 0) return@LaunchedEffect
        while (true) {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (total > 0 && wasNearBottom && lastVisible < total - 2) {
                listState.animateScrollToItem(total - 1, scrollOffset = BOTTOM_ANCHOR_OFFSET)
            }
            delay(400)
        }
    }

    // Reaching the top pulls the next page. The guard matters: this effect sits above the `loading`
    // early return, so without it the trigger would fire against an empty list and race the initial
    // history fetch. It also re-arms once a page lands, which is what fills the first screen when a
    // session opens on fewer messages than the viewport holds.
    val canPage = hasMore && !loading && !loadingOlder && !loadOlderFailed
    LaunchedEffect(listState, sessionId, canPage) {
        if (!canPage) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { if (it <= LOAD_OLDER_THRESHOLD) onLoadOlder() }
    }

    if (loading) {
        if (openFailed) {
            TranscriptRefresh(onRetry = onRetryOpen, modifier = modifier)
        } else {
            TranscriptSkeleton(modifier)
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        // Bottom-anchored: a transcript shorter than the viewport belongs above the composer, not
        // pinned under the tab strip with the empty half below it.
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
    ) {
        if (hasMore) {
            // Present whenever there is more to fetch, so index 0 stays stable across pages.
            item(key = "load-older") {
                LoadOlderRow(
                    loading = loadingOlder,
                    failed = loadOlderFailed,
                    onRetry = onLoadOlder,
                )
            }
        }
        if (nodes.isEmpty()) {
            item(key = "empty") {
                EmptyHero(
                    headline = stringResource(R.string.chat_empty_title),
                    subtitle = stringResource(R.string.chat_empty_hint),
                )
            }
        } else {
            items(nodes, key = { it.seq }) { node ->
                Column(Modifier.animateItem()) {
                    ChatNodeItem(node = node, context = context)
                }
            }
        }
    }
}

/**
 * Head of the transcript while more history exists.
 *
 * Silent by default — paging is automatic, so an affordance would only invite a tap that does
 * nothing. It speaks up while fetching, and offers a retry when a page failed, because the scroll
 * trigger will not fire again on its own until the reader moves.
 */
@Composable
private fun LoadOlderRow(loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    val colors = DsTheme.colors
    when {
        failed -> DsButton(
            text = stringResource(R.string.chat_load_older_retry),
            onClick = onRetry,
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )

        loading -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.chat_loading_older),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }

        else -> Spacer(Modifier.height(1.dp))
    }
}

/** The current session's history failed to load: offer a retry instead of an endless "initializing" skeleton. */
@Composable
private fun TranscriptRefresh(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.chat_open_failed),
            style = DsType.std14,
            color = colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
        DsButton(
            text = stringResource(R.string.chat_open_retry),
            onClick = onRetry,
            variant = DsButtonVariant.Primary,
            size = DsButtonSize.Small,
        )
    }
}

/** Placeholder bubbles while a session's history loads, instead of an empty white screen. */
@Composable
private fun TranscriptSkeleton(modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(0.55f, 0.9f, 0.75f, 0.4f).forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .skeleton(colors.bgLayer2, colors.hover),
            )
        }
    }
}
