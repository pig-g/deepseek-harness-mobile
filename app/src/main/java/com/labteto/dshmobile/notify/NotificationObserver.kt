package com.labteto.dshmobile.notify

import android.content.Context
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.notify.CompletionClassifier
import com.labteto.dshmobile.core.notify.CompletionEvent
import com.labteto.dshmobile.core.wire.ServerRequest
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.data.parseSessionEventEnvelope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns completion/needs-action wire signals into local notifications.
 *
 * `start()` launches collectors on the connection manager's downlink frames; call it once from
 * [android.app.Application.onCreate] after the store is initialized. Each notification id is
 * stable per session (hash % 1000 + a per-channel offset) so re-notifying the same session
 * replaces the previous one instead of stacking.
 */
@Singleton
class NotificationObserver @Inject constructor(
    private val store: SessionStore,
    private val connectionManager: ConnectionManager,
    private val notifications: DshNotifications,
    private val hostsStore: HostsStore,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val classifier = CompletionClassifier()
    private val seen = LinkedHashSet<String>()

    @Volatile
    private var settings = AppSettings()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        notifications.ensureChannels()
        scope.launch {
            hostsStore.settings.collect { settings = it }
        }
        scope.launch {
            connectionManager.muxFrames.collect { handleMuxFrame(it) }
        }
        scope.launch {
            connectionManager.hostFrames.collect { handleHostFrame(it) }
        }
    }

    private fun handleMuxFrame(frame: ServerRequest) {
        val payload = frame.payload as? JsonObject ?: return
        // approval/requested and question/requested classify straight off the method. The
        // envelope rpcId rides along: the question payload has no seq of its own, and the rpcId
        // is what keeps each question instance from deduping onto the previous one.
        classifier.classifyMux(frame.method, payload, frame.rpcId)?.let { maybeNotify(it) }

        if (frame.method == "session/event") {
            val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
            val eventJson = payload["event"] ?: return
            val envelope = parseSessionEventEnvelope(eventJson) ?: return
            when (envelope.type) {
                "turn/start" -> classifier.markSessionRunning(sessionId)
                "turn/end", "goal/change" ->
                    classifier.classifyEvent(sessionId, envelope)?.let { maybeNotify(it) }
            }
        }
    }

    private fun handleHostFrame(frame: ServerRequest) {
        val payload = frame.payload as? JsonObject ?: return
        classifier.classifyHost(frame.method, payload)?.let { maybeNotify(it) }
    }

    private fun maybeNotify(event: CompletionEvent) {
        if (!notifications.canPost()) return
        if (store.isSessionOpen(event.sessionId)) return

        val spec = when (event) {
            is CompletionEvent.TurnComplete -> {
                if (!settings.notifyTurnComplete) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_turn_complete, sessionTitle(event.sessionId).orEmpty()),
                )
            }
            is CompletionEvent.GoalComplete -> {
                if (!settings.notifyGoal) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_goal_complete, event.objective.orEmpty()),
                )
            }
            is CompletionEvent.GoalBlocked -> {
                if (!settings.notifyGoal) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_goal_blocked, event.reason.orEmpty()),
                )
            }
            is CompletionEvent.ReviewRequested -> {
                if (!settings.notifyNeedsAction) return
                Spec(
                    channel = DshNotifications.CHANNEL_ACTION,
                    title = context.getString(R.string.notif_review_requested, event.toolName),
                    actionLabel = context.getString(R.string.notif_open),
                )
            }
            is CompletionEvent.QuestionRequested -> {
                if (!settings.notifyNeedsAction) return
                Spec(
                    channel = DshNotifications.CHANNEL_ACTION,
                    title = context.getString(R.string.notif_question, event.firstQuestion.orEmpty()),
                )
            }
            is CompletionEvent.SessionIdle -> {
                if (!settings.notifyTurnComplete) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_session_idle, sessionTitle(event.sessionId).orEmpty()),
                )
            }
        }

        if (isDuplicate(event.dedupKey)) return

        val text = context.getString(R.string.notif_open)
        val id = notificationId(event.sessionId, spec.channel)
        notifications.postSession(spec.channel, id, spec.title, text, event.sessionId, spec.actionLabel)
    }

    private data class Spec(
        val channel: String,
        val title: String,
        val actionLabel: String? = null,
    )

    private fun sessionTitle(sessionId: String): String? =
        store.sessions.value.firstOrNull { it.sessionId == sessionId }?.title

    private fun notificationId(sessionId: String, channel: String): Int {
        val offset = when (channel) {
            DshNotifications.CHANNEL_ACTION -> CHANNEL_ACTION_OFFSET
            DshNotifications.CHANNEL_CONNECTION -> CHANNEL_CONNECTION_OFFSET
            else -> CHANNEL_COMPLETIONS_OFFSET
        }
        val base = ((sessionId.hashCode() % 1000) + 1000) % 1000
        return base + offset
    }

    private fun isDuplicate(key: String): Boolean {
        synchronized(seen) {
            if (key in seen) return true
            seen.add(key)
            while (seen.size > MAX_DEDUP_KEYS) {
                val it = seen.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                } else {
                    break
                }
            }
            return false
        }
    }

    private companion object {
        const val MAX_DEDUP_KEYS = 1024
        const val CHANNEL_COMPLETIONS_OFFSET = 1000
        const val CHANNEL_ACTION_OFFSET = 2000
        const val CHANNEL_CONNECTION_OFFSET = 3000
    }
}
