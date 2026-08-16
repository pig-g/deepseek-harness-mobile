package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

// ---------------------------------------------------------------------------
// Interaction takeovers: sandbox-approval, plan review, and ask_user_question.
// These mirror the harness composer-takeover panels (amber strip, floating
// capsule, outline/primary pill actions).
// ---------------------------------------------------------------------------

/** Sandbox escalation / approval request panel. */
@Composable
fun ApprovalPanel(
    toolName: String,
    reason: String?,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.approvalCard,
        color = colors.composerCard,
        border = BorderStroke(1.dp, colors.warnSecondary),
        shadowElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.warnTertiary)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = colors.warn,
                    modifier = Modifier.width(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.approval_title),
                    style = DsType.small13Strong,
                    color = colors.warnLabel,
                )
            }
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.approval_reason, reason ?: toolName),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DsButton(
                        text = stringResource(R.string.approval_allow_once),
                        onClick = onAllow,
                        variant = DsButtonVariant.Info,
                        size = DsButtonSize.Small,
                    )
                    DsButton(
                        text = stringResource(R.string.approval_reject),
                        onClick = onReject,
                        variant = DsButtonVariant.Outline,
                        size = DsButtonSize.Small,
                    )
                }
            }
        }
    }
}

/** Plan-review takeover: plan markdown + Approve / Decline / Discuss. */
@Composable
fun PlanReviewPanel(
    planMarkdown: String,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDiscuss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.approvalCard,
        color = colors.composerCard,
        border = BorderStroke(1.dp, colors.warnSecondary),
        shadowElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.warnTertiary)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.plan_review_title),
                    style = DsType.small13Strong,
                    color = colors.warnLabel,
                )
            }
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(planMarkdown)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DsButton(
                        text = stringResource(R.string.plan_review_approve),
                        onClick = onApprove,
                        variant = DsButtonVariant.Info,
                        size = DsButtonSize.Small,
                    )
                    DsButton(
                        text = stringResource(R.string.plan_review_decline),
                        onClick = onDecline,
                        variant = DsButtonVariant.Outline,
                        size = DsButtonSize.Small,
                    )
                    DsButton(
                        text = stringResource(R.string.plan_review_discuss),
                        onClick = onDiscuss,
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
            }
        }
    }
}

/** One option of one ask_user_question item. */
data class QuestionOption(val label: String, val description: String? = null)

/** One ask_user_question item (mirrors the harness AskUserQuestionItem). */
data class QuestionItem(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

/** One answer submitted back to the harness. */
data class QuestionAnswer(val id: String, val selected: List<String>, val custom: String? = null)

/**
 * Paged ask_user_question composer: radio/checkbox options, an "Other…"
 * custom input, skip, prev/next and a batch submit on the last page.
 */
@Composable
fun QuestionsPanel(
    questions: List<QuestionItem>,
    onSubmit: (List<QuestionAnswer>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    var page by remember { mutableIntStateOf(0) }
    val current = questions.getOrNull(page) ?: return
    val isLast = page >= questions.lastIndex
    var selected by remember(questions, page) { mutableStateOf<List<String>>(emptyList()) }
    var custom by remember(questions, page) { mutableStateOf("") }
    val answers = remember(questions) { mutableMapOf<String, QuestionAnswer>() }

    fun storeAnswer(answer: QuestionAnswer) {
        answers[answer.id] = answer
    }

    fun submitAll() {
        // Every question gets an answer; unanswered ones submit empty selections.
        val complete = questions.map { q ->
            answers[q.id] ?: QuestionAnswer(q.id, emptyList(), null)
        }
        onSubmit(complete)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.approvalCard,
        color = colors.composerCard,
        border = BorderStroke(1.dp, colors.borderL2),
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The takeover dock sits at the bottom of the chat under the composer on a phone, so it
            // shares the screen with the keyboard and has no room of its own. Unbounded, a tall set
            // of questions would push the action row below the fold and leave the user unable to
            // select, submit or cancel. Bounding the scrolling body keeps those actions reachable.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.questions_title) + " ${page + 1}/${questions.size}",
                    style = DsType.small13Strong,
                    color = colors.labelSecondary,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                current.header?.let {
                    Text(it, style = DsType.std14Strong, color = colors.labelPrimary)
                }
                Text(current.question, style = DsType.std14, color = colors.labelPrimary)
                current.detail?.let {
                    Text(it, style = DsType.small13, color = colors.labelTertiary)
                }

                current.options.forEach { option ->
                    val isSelected = option.label in selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (current.multiSelect) {
                                    if (isSelected) selected - option.label else selected + option.label
                                } else {
                                    listOf(option.label)
                                }
                            },
                        shape = DsShapes.menu,
                        color = if (isSelected) colors.accentTertiary else colors.bgModulePlatform,
                        border = if (isSelected) BorderStroke(1.dp, colors.accent) else null,
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                option.label,
                                style = DsType.std14,
                                color = if (isSelected) colors.accent else colors.labelPrimary,
                            )
                            option.description?.let {
                                Text(it, style = DsType.caption11, color = colors.labelTertiary)
                            }
                        }
                    }
                }

                TextField(
                    value = custom,
                    onValueChange = { custom = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.questions_other), style = DsType.std14) },
                    singleLine = false,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.bgLayer1,
                        unfocusedContainerColor = colors.bgLayer1,
                        focusedIndicatorColor = colors.accent,
                        unfocusedIndicatorColor = colors.borderL2,
                    ),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DsButton(
                    text = stringResource(R.string.questions_previous),
                    onClick = { if (page > 0) page-- },
                    enabled = page > 0,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
                Spacer(Modifier.weight(1f))
                if (!isLast) {
                    DsButton(
                        text = stringResource(R.string.questions_skip),
                        onClick = { page++ },
                        variant = DsButtonVariant.Outline,
                        size = DsButtonSize.Small,
                    )
                    DsButton(
                        text = stringResource(R.string.questions_next),
                        onClick = {
                            currentAnswer(current, selected, custom)?.let { storeAnswer(it) }
                            page++
                        },
                        variant = DsButtonVariant.Info,
                        size = DsButtonSize.Small,
                    )
                } else {
                    DsButton(
                        text = stringResource(R.string.questions_submit),
                        onClick = {
                            currentAnswer(current, selected, custom)?.let { storeAnswer(it) }
                            submitAll()
                        },
                        variant = DsButtonVariant.Info,
                        size = DsButtonSize.Small,
                    )
                    DsButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = onCancel,
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
            }
        }
    }
}

private fun currentAnswer(
    item: QuestionItem,
    selected: List<String>,
    custom: String,
): QuestionAnswer {
    val text = custom.trim()
    // The harness rejects a single-select answer that carries both a chosen option and a custom
    // reply (and an empty `custom`), so a single-select "Other…" reply IS the answer: its option
    // selection is cleared. On a multi-select question the custom text may accompany the chosen
    // options. Custom text is omitted entirely when blank.
    val effectiveSelected =
        if (item.multiSelect || text.isEmpty()) selected else emptyList()
    return QuestionAnswer(
        id = item.id,
        selected = effectiveSelected,
        custom = text.takeIf { it.isNotBlank() },
    )
}
