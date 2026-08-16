package com.labteto.dshmobile.ui.screens.main

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.media.sampleSizeFor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionIntent
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.ApprovalPanel
import com.labteto.dshmobile.ui.components.ConnectionBanner
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.PlanReviewPanel
import com.labteto.dshmobile.ui.components.QuestionAnswer
import com.labteto.dshmobile.ui.components.QuestionItem
import com.labteto.dshmobile.ui.components.QuestionOption
import com.labteto.dshmobile.ui.components.QuestionsPanel
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsTheme
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import kotlinx.coroutines.launch

/**
 * The chat surface: chrome, transcript or trajectory, the persistent docks, and the composer.
 *
 * Everything below the tabs stays outside the tab swap on purpose — you can keep typing, and keep
 * answering an approval, while reading the trajectory, and the keyboard-attached surface never
 * animates out from under the cursor.
 */
@Composable
fun ChatScreen(
    onOpenDetails: () -> Unit,
    onOpenDrawer: () -> Unit,
    detailsOpen: Boolean,
) {
    val store = rememberSessionStore()
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val context = LocalContext.current
    val toast = rememberDsToast()

    val conversation by store.currentConversation.collectAsStateWithLifecycle()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val toolViews by store.toolViews.collectAsStateWithLifecycle()
    val models by store.models.collectAsStateWithLifecycle()
    val skills by store.skills.collectAsStateWithLifecycle()
    val commands by store.commands.collectAsStateWithLifecycle()
    val commandsAvailable by store.commandsAvailable.collectAsStateWithLifecycle()
    val subagents by store.subagents.collectAsStateWithLifecycle()
    val subagentConversation by store.subagentConversation.collectAsStateWithLifecycle()
    val subagentMode by store.subagentMode.collectAsStateWithLifecycle()
    val connectionError by store.connectionError.collectAsStateWithLifecycle()
    val loadingOlder by store.loadingOlder.collectAsStateWithLifecycle()
    val openFailed by store.openFailed.collectAsStateWithLifecycle()
    val loadOlderFailed by store.loadOlderFailed.collectAsStateWithLifecycle()
    val pendingApproval by store.pendingApproval.collectAsStateWithLifecycle()
    val pendingQuestions by store.pendingQuestions.collectAsStateWithLifecycle()
    val permissions by store.permissions.collectAsStateWithLifecycle()
    val pendingPermission by store.pendingPermission.collectAsStateWithLifecycle()
    val agentPresets by store.agentPresets.collectAsStateWithLifecycle()
    val sessionStats by store.sessionStats.collectAsStateWithLifecycle()
    val tokenUsage by store.tokenUsage.collectAsStateWithLifecycle()
    val contextBreakdown by store.contextBreakdown.collectAsStateWithLifecycle()
    val contextPressure by store.contextPressure.collectAsStateWithLifecycle()
    val imageLimits by store.imageLimits.collectAsStateWithLifecycle()

    val currentSession = sessions.firstOrNull { it.sessionId == currentSessionId }
    val title = currentSession?.title
        ?: currentSession?.cwd?.let { basename(it) }
        ?: currentSessionId.orEmpty()

    var draft by rememberSaveable(currentSessionId) { mutableStateOf("") }
    var mode by rememberSaveable(currentSessionId) { mutableStateOf("queue") }
    var tab by rememberSaveable { mutableStateOf(ChatTab.Chat) }
    val attachments = remember(currentSessionId) { mutableStateListOf<PendingAttachment>() }

    var sheet by remember { mutableStateOf<ChatSheet?>(null) }

    // Hoisted above the tab swap so each view keeps its own scroll position across switches.
    val chatListState = rememberLazyListState()
    val trajectoryListState = rememberLazyListState()

    val commandFailed = stringResource(R.string.err_command_failed)
    val unknownCommand = stringResource(R.string.err_command_unknown)

    fun report(outcome: com.labteto.dshmobile.data.CommandOutcome) {
        when (outcome) {
            is com.labteto.dshmobile.data.CommandOutcome.Ok ->
                outcome.text?.takeIf { it.isNotBlank() }?.let { toast.second(it) }
            is com.labteto.dshmobile.data.CommandOutcome.Unknown ->
                toast.second(unknownCommand.format(outcome.line))
            is com.labteto.dshmobile.data.CommandOutcome.Failed ->
                toast.second(commandFailed.format(outcome.message))
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val resolver = context.contentResolver
            val mediaType = resolver.getType(uri)
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val limits = imageLimits
            // The host publishes its own attachment bounds; checking them here means a rejection
            // surfaces before the round trip rather than as a failed turn.
            if (bytes == null || bytes.isEmpty() || mediaType == null ||
                (limits != null && !limits.accepts(mediaType, bytes.size)) ||
                (limits == null && bytes.size > 5_242_880)
            ) {
                toast.second(context.getString(R.string.err_attachment_failed))
                return@launch
            }
            attachments.add(
                PendingAttachment(
                    mediaType = mediaType,
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    preview = decodePreview(bytes),
                ),
            )
        }
    }

    fun send() {
        val text = draft
        val pending = attachments.toList()
        if (text.isBlank() && pending.isEmpty()) {
            android.util.Log.d("DSHSend", "send blocked: blank text (draft len=${text.length}) and no attachments")
            return
        }
        // With no open session the prompt path silently drops the text; keep the draft and say so
        // instead of letting Send look like a no-op.
        if (currentSessionId == null) {
            android.util.Log.d("DSHSend", "send blocked: no session (currentSessionId null)")
            toast.second("Open or create a conversation first to send messages.")
            return
        }
        // The downlink (events.mux) is what makes a send show up: without it the prompt still goes
        // out over HTTP but the user bubble / running / answer never stream back, so Send looks
        // dead for a long stretch. Keep the draft, say so, and ask the downlink to reconnect now
        // instead of letting the user wait out the reconnect backoff in silence.
        if (!store.isConnectionReady()) {
            android.util.Log.d("DSHSend", "send blocked: connection not ready phase=${store.connectionPhase()}")
            toast.second("Reconnecting to the harness — your message is kept; try again in a moment.")
            store.kickConnection()
            return
        }
        // A slash line that names a registered command is not a message: `session.prompt` would
        // hand it to the model verbatim, so it has to be recognised here and written through the
        // command gateway. A miss falls through to the prompt path — that is how skills work.
        val submission = adjudicate(text, commands, pending.isNotEmpty())
        draft = ""
        attachments.clear()
        if (submission is Submission.Command) {
            scope.launch { report(store.runCommand(submission.line)) }
            return
        }
        scope.launch {
            if (pending.isEmpty()) {
                store.prompt(text, mode)
            } else {
                // The prompt API takes one image per call; the text rides the first so the message
                // stays a single turn rather than fragmenting across attachments.
                pending.forEachIndexed { index, attachment ->
                    store.promptWithImage(
                        text = if (index == 0) text else "",
                        mode = mode,
                        mediaType = attachment.mediaType,
                        base64Data = attachment.base64,
                        name = attachment.name,
                    )
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        // The activity draws edge to edge, so every top-level surface has to consume the insets
        // itself or the chrome ends up underneath the status bar. safeDrawing covers the status
        // bar, the gesture area and the keyboard in one modifier.
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            ChatTopBar(
                title = title,
                running = conversation?.running == true,
                models = models,
                agentPresetLabel = currentSession?.agentPreset?.let { id ->
                    agentPresets?.presets?.firstOrNull { it.id == id }?.displayName() ?: id
                },
                subagentCount = subagents.size,
                detailsOpen = detailsOpen,
                tab = tab,
                onOpenDrawer = onOpenDrawer,
                onOpenModels = { sheet = ChatSheet.Models },
                onOpenPresets = {
                    scope.launch { store.refreshAgentPresets() }
                    sheet = ChatSheet.Presets
                },
                onOpenSubagents = { sheet = ChatSheet.Subagents },
                onOpenDetails = onOpenDetails,
                onTabChange = { tab = it },
            )

            connectionError?.let { ConnectionBanner(it) }
            if (conversation?.gap == true) {
                ConnectionBanner(stringResource(R.string.common_reconnecting))
            }

            val nodeContext = ChatNodeContext(
                nodes = conversation?.nodes ?: emptyList(),
                toolViews = toolViews,
                running = conversation?.running == true,
                cwd = currentSession?.cwd,
                onOpenSubagent = { childId ->
                    scope.launch { store.openSubagentTranscript(childId) }
                    sheet = ChatSheet.Subagents
                },
                onBranchFrom = { seq -> scope.launch { currentSessionId?.let { store.forkSession(it, seq) } } },
                onFeedback = { _, positive ->
                    scope.launch { report(store.runCommand(if (positive) "/feedback +1" else "/feedback -1")) }
                },
            )

            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (
                        slideInHorizontally { width -> if (forward) width / 6 else -width / 6 } +
                            fadeIn(DsAnimations.fade)
                        )
                        .togetherWith(fadeOut(DsAnimations.fade)) using SizeTransform(clip = false)
                },
                modifier = Modifier.weight(1f),
                label = "chatTab",
            ) { current ->
                when (current) {
                    ChatTab.Chat -> ChatTranscript(
                        conversation = conversation,
                        loading = conversation == null && currentSessionId != null,
                        loadingOlder = loadingOlder,
                        loadOlderFailed = loadOlderFailed,
                        openFailed = openFailed,
                        onRetryOpen = { scope.launch { store.retryOpenCurrentSession() } },
                        context = nodeContext,
                        listState = chatListState,
                        onLoadOlder = { scope.launch { store.loadOlder() } },
                    )
                    ChatTab.Trajectory -> TrajectoryTab(
                        conversation = conversation,
                        stats = sessionStats,
                        usage = tokenUsage,
                        cwd = currentSession?.cwd,
                        listState = trajectoryListState,
                    )
                }
            }

            conversation?.let { conv ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    parseTodos(conv.projections["todos"])?.let { TodoDock(it) }
                    parseGoal(conv.projections["goal"])?.let { GoalBar(it, store) }
                    QueueDock(conv.queue, store)
                }
            }

            // Server-initiated requests take over the bottom of the screen: they block the turn,
            // so burying them behind a scroll would strand the session.
            val approval = pendingApproval
            if (approval != null && approval.sessionId == currentSessionId) {
                ApprovalPanel(
                    toolName = approval.toolName,
                    reason = approval.reason,
                    onAllow = {
                        scope.launch { store.respondApproval(approval.sessionId, approval.approvalId, true) }
                    },
                    onReject = {
                        scope.launch { store.respondApproval(approval.sessionId, approval.approvalId, false) }
                    },
                )
            }
            val questions = pendingQuestions
            if (questions != null && questions.sessionId == currentSessionId) {
                val planReview = questions.items.firstOrNull {
                    it.intent is AskUserQuestionIntent.PlanReview
                }
                val planIntent = planReview?.intent as? AskUserQuestionIntent.PlanReview
                if (planReview != null && planIntent != null) {
                    // A plan review rides the question channel but is a different decision, so it
                    // gets the panel built for it rather than a generic multiple choice. The intent
                    // names the option label that approves; every other option declines.
                    val declineLabel = planReview.options
                        ?.map { it.label }
                        ?.firstOrNull { it != planIntent.approve }
                    fun answer(label: String?) {
                        scope.launch {
                            store.answerQuestions(
                                questions.sessionId,
                                listOf(planReview.id to listOfNotNull(label)),
                            )
                        }
                    }
                    PlanReviewPanel(
                        planMarkdown = planReview.detail ?: planReview.question,
                        onApprove = { answer(planIntent.approve) },
                        onDecline = { answer(declineLabel) },
                        onDiscuss = {
                            answer(declineLabel)
                            draft = ""
                        },
                    )
                } else {
                    QuestionsPanel(
                        questions = questions.items.map { item ->
                            QuestionItem(
                                id = item.id,
                                question = item.question,
                                detail = item.detail,
                                header = item.header,
                                options = item.options?.map { QuestionOption(it.label, it.description) }
                                    ?: emptyList(),
                                multiSelect = item.multiSelect ?: false,
                            )
                        },
                        onSubmit = { answers -> submitAnswers(scope, store, questions.sessionId, answers) },
                        onCancel = {
                            scope.launch {
                                store.answerQuestions(
                                    questions.sessionId,
                                    questions.items.map { it.id to emptyList<String>() },
                                    null,
                                )
                            }
                        },
                    )
                }
            }

            // Force the Composer (TextField + send button) to be fully recreated when the session
            // changes, not just recomposed. A recompose leaves the TextField's internal state
            // (IME connection, cursor, composition) stale — the send button appears but taps don't
            // register until a rotation recreates the Activity. key() does what rotation does,
            // but automatically on every session switch.
            key(currentSessionId) {
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    attachments = attachments,
                    onRemoveAttachment = { index -> attachments.removeAt(index) },
                    permissions = permissions,
                    pendingPermission = pendingPermission,
                    onPermissionPick = { value -> scope.launch { report(store.setPermissionPreset(value)) } },
                    contextBreakdown = contextBreakdown,
                    contextPressure = contextPressure,
                    running = conversation?.running == true,
                    enabled = currentSessionId != null,
                    onOpenSheet = { sheet = ChatSheet.Commands },
                    onSend = ::send,
                    onStop = { scope.launch { store.cancelTurn() } },
                )
            }

            StatsFooter(stats = sessionStats, usage = tokenUsage)
        }
        DsToastHost(toast, modifier = Modifier.fillMaxWidth())
    }

    when (sheet) {
        ChatSheet.Commands -> CommandSheet(
            commands = commands,
            commandsAvailable = commandsAvailable,
            skills = skills,
            mode = mode,
            running = conversation?.running == true,
            canAttach = currentSessionId != null,
            onModeChange = { mode = it },
            onAttach = { imagePicker.launch("image/*") },
            onRunCommand = { line -> scope.launch { report(store.runCommand(line)) } },
            onPrefillDraft = { prefix -> draft = prefix },
            onDismiss = { sheet = null },
        )
        ChatSheet.Models -> ModelsSheet(models = models, store = store, onDismiss = { sheet = null })
        ChatSheet.Presets -> PresetsSheet(
            presets = agentPresets,
            currentPreset = currentSession?.agentPreset,
            sessionBlank = currentSession?.blank ?: false,
            store = store,
            onDismiss = { sheet = null },
        )
        ChatSheet.Subagents -> SubagentsSheet(
            store = store,
            entries = subagents,
            conversation = subagentConversation,
            mode = subagentMode,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

/** Which sheet, if any, is open over the chat surface. */
private enum class ChatSheet { Commands, Models, Presets, Subagents }

private fun submitAnswers(
    scope: kotlinx.coroutines.CoroutineScope,
    store: SessionStore,
    sessionId: String,
    answers: List<QuestionAnswer>,
) {
    scope.launch {
        store.answerQuestions(
            sessionId,
            answers.map { it.id to it.selected },
            answers.firstOrNull { it.custom != null }?.custom,
        )
    }
}

/**
 * A thumbnail for the composer strip, decoded straight off the picked bytes.
 *
 * This one *does* need a bounds pass: the picker hands over raw bytes with no intrinsic size
 * attached, unlike an attachment reference that already carries its dimensions.
 */
private fun decodePreview(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, PREVIEW_WIDTH_PX)
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

/** The composer thumbnail is 56dp; decoding much past that is wasted memory. */
private const val PREVIEW_WIDTH_PX = 224
