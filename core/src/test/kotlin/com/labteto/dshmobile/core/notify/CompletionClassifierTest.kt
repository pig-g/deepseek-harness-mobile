package com.labteto.dshmobile.core.notify

import com.labteto.dshmobile.core.session.SessionEventEnvelope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionClassifierTest {

    private val classifier = CompletionClassifier()

    private fun event(type: String, seq: Long, data: kotlinx.serialization.json.JsonObject): SessionEventEnvelope =
        SessionEventEnvelope(type, seq, seq, data)

    @Test
    fun turnCompletedFires() {
        val result = classifier.classifyEvent("s1", event("turn/end", 4, buildJsonObject {
            put("turn", 1)
            putJsonObject("reason") { put("kind", "completed") }
        }))
        assertTrue(result is CompletionEvent.TurnComplete)
        assertEquals("turn:s1:4", result!!.dedupKey)
    }

    @Test
    fun abortedTurnDoesNotFire() {
        val result = classifier.classifyEvent("s1", event("turn/end", 4, buildJsonObject {
            put("turn", 1)
            putJsonObject("reason") { put("kind", "aborted") }
        }))
        assertNull(result)
    }

    @Test
    fun goalCompleteFires() {
        val result = classifier.classifyEvent("s1", event("goal/change", 9, buildJsonObject {
            put("kind", "goal/change")
            putJsonObject("goal") {
                put("id", "g1"); put("revision", 2); put("objective", "ship it"); put("phase", "complete")
            }
        }))
        assertTrue(result is CompletionEvent.GoalComplete)
        assertEquals("ship it", (result as CompletionEvent.GoalComplete).objective)
    }

    @Test
    fun goalBlockedCarriesReason() {
        val result = classifier.classifyEvent("s1", event("goal/change", 9, buildJsonObject {
            put("kind", "goal/change")
            putJsonObject("goal") {
                put("id", "g1"); put("revision", 2); put("phase", "blocked")
                putJsonObject("blockedReason") { put("message", "no network") }
            }
        }))
        assertTrue(result is CompletionEvent.GoalBlocked)
        assertEquals("no network", (result as CompletionEvent.GoalBlocked).reason)
    }

    @Test
    fun approvalRequestedFires() {
        val frame = buildJsonObject {
            put("sessionId", "s1")
            put("approvalId", "appr-1")
            put("toolName", "bash")
            put("reason", "justification")
        }
        val result = classifier.classifyMux("approval/requested", frame)
        assertTrue(result is CompletionEvent.ReviewRequested)
        assertEquals("review:s1:appr-1", result!!.dedupKey)
    }

    @Test
    fun questionRequestedFires() {
        val frame = buildJsonObject {
            put("sessionId", "s1")
            putJsonArray("questions") {
                add(buildJsonObject { put("id", "q1"); put("question", "which one?") })
            }
        }
        val result = classifier.classifyMux("question/requested", frame, "rpc-1")
        assertTrue(result is CompletionEvent.QuestionRequested)
        assertEquals("which one?", (result as CompletionEvent.QuestionRequested).firstQuestion)
        assertEquals("question:s1:rpc-1", result!!.dedupKey)
    }

    /**
     * The `question/requested` payload carries no seq, so the dedup unit is the envelope rpcId:
     * the second question of the same session must not dedup onto the first one's notification.
     */
    @Test
    fun secondQuestionOfSameSessionKeepsADistinctDedupKey() {
        val frame = buildJsonObject {
            put("sessionId", "s1")
            putJsonArray("questions") {
                add(buildJsonObject { put("id", "q1"); put("question", "which one?") })
            }
        }
        val first = classifier.classifyMux("question/requested", frame, "rpc-1")!!
        val second = classifier.classifyMux("question/requested", frame, "rpc-2")!!
        assertTrue(first.dedupKey != second.dedupKey)
    }

    @Test
    fun sessionIdleOnlyAfterRunning() {
        val stopped = buildJsonObject { put("sessionId", "s1"); put("running", false) }
        val started = buildJsonObject { put("sessionId", "s1"); put("running", true) }
        assertNull(classifier.classifyHost("host/session-status", stopped))
        classifier.classifyHost("host/session-status", started)
        assertTrue(classifier.classifyHost("host/session-status", stopped) is CompletionEvent.SessionIdle)
        // Second stop does not refire.
        assertNull(classifier.classifyHost("host/session-status", stopped))
    }
}
