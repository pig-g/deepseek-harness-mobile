package com.labteto.dshmobile.core.notify

import com.labteto.dshmobile.core.session.SessionEventEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Notification-worthy completions/requests derived from the wire. */
sealed interface CompletionEvent {
    val sessionId: String
    val seq: Long
    val dedupKey: String

    /** A turn ended with reason kind `completed` (code writing finished). */
    data class TurnComplete(override val sessionId: String, override val seq: Long, val turn: Int) : CompletionEvent {
        override val dedupKey: String get() = "turn:$sessionId:$seq"
    }

    /** The goal moved to phase `complete`. */
    data class GoalComplete(override val sessionId: String, override val seq: Long, val objective: String?) : CompletionEvent {
        override val dedupKey: String get() = "goal:$sessionId:$seq"
    }

    /** The goal moved to phase `blocked`. */
    data class GoalBlocked(override val sessionId: String, override val seq: Long, val reason: String?) : CompletionEvent {
        override val dedupKey: String get() = "goal:$sessionId:$seq"
    }

    /** A sandbox escalation or plan review waits for the user. */
    data class ReviewRequested(
        override val sessionId: String,
        override val seq: Long,
        val approvalId: String,
        val toolName: String,
        val reason: String?,
    ) : CompletionEvent {
        override val dedupKey: String get() = "review:$sessionId:$approvalId"
    }

    /** ask_user_question waits for the user. */
    data class QuestionRequested(
        override val sessionId: String,
        override val seq: Long,
        val firstQuestion: String?,
        val rpcId: String? = null,
    ) : CompletionEvent {
        // The `question/requested` frame payload carries no seq, so the frame's server-request
        // id — unique per question instance, stable across reconnect replays — is the dedup unit.
        // Keying on seq (always 0) used to silence every question after the first per session.
        override val dedupKey: String get() = "question:$sessionId:${rpcId ?: seq}"
    }

    /** A session that was running stopped (fallback completion signal). */
    data class SessionIdle(override val sessionId: String, override val seq: Long) : CompletionEvent {
        override val dedupKey: String get() = "idle:$sessionId:$seq"
    }
}

/**
 * Classifies session events and stream frames into [CompletionEvent]s.
 * Tracks per-session running state so `host/session-status(running:false)`
 * only fires once per run.
 */
class CompletionClassifier {
    private val running = mutableSetOf<String>()

    fun classifyEvent(sessionId: String, event: SessionEventEnvelope): CompletionEvent? {
        val data = event.data as? JsonObject
        return when (event.type) {
            "turn/end" -> {
                val kind = data?.get("reason")?.jsonObject?.get("kind")?.jsonPrimitive?.contentOrNull
                if (kind == "completed") {
                    val turn = data["turn"]?.jsonPrimitive?.let { runCatching { it.content.toInt() }.getOrNull() } ?: 0
                    CompletionEvent.TurnComplete(sessionId, event.seq, turn)
                } else null
            }

            "goal/change" -> {
                val goal = data?.get("goal")?.jsonObject ?: return null
                when (goal["phase"]?.jsonPrimitive?.contentOrNull) {
                    "complete" -> CompletionEvent.GoalComplete(
                        sessionId,
                        event.seq,
                        goal["objective"]?.jsonPrimitive?.contentOrNull,
                    )
                    "blocked" -> CompletionEvent.GoalBlocked(
                        sessionId,
                        event.seq,
                        goal["blockedReason"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull,
                    )
                    else -> null
                }
            }

            else -> null
        }
    }

    /**
     * Classify a mux-frame (method + payload object, sessionId inside).
     *
     * [rpcId] is the envelope's server-request id: the `question/requested` payload has no seq,
     * so the rpcId is what makes each question instance a distinct notification.
     */
    fun classifyMux(method: String, payload: JsonObject, rpcId: String? = null): CompletionEvent? {
        val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return null
        val seq = payload["seq"]?.jsonPrimitive?.let { runCatching { it.content.toLong() }.getOrNull() } ?: 0L
        return when (method) {
            "approval/requested" -> CompletionEvent.ReviewRequested(
                sessionId = sessionId,
                seq = seq,
                approvalId = payload["approvalId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                toolName = payload["toolName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reason = payload["reason"]?.jsonPrimitive?.contentOrNull,
            )

            "question/requested" -> CompletionEvent.QuestionRequested(
                sessionId = sessionId,
                seq = seq,
                firstQuestion = (payload["questions"] as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull()?.jsonObject?.get("question")?.jsonPrimitive?.contentOrNull,
                rpcId = rpcId,
            )

            else -> null
        }
    }

    /** Classify a host-frame (method + payload object). */
    fun classifyHost(method: String, payload: JsonObject): CompletionEvent? {
        if (method != "host/session-status") return null
        val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return null
        val isRunning = payload["running"]?.jsonPrimitive?.let { runCatching { it.content.toBoolean() }.getOrNull() } ?: false
        val wasRunning = running.contains(sessionId)
        if (isRunning) {
            running.add(sessionId)
            return null
        }
        running.remove(sessionId)
        return if (wasRunning) CompletionEvent.SessionIdle(sessionId, 0L) else null
    }

    fun markSessionRunning(sessionId: String) {
        running.add(sessionId)
    }
}
