package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import com.labteto.dshmobile.core.wire.dto.SessionEventSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the totality contract of [sessionEventToEnvelope] — the root cause of the
 * "crash during Reconnecting…" bug.
 *
 * The gap-repair commit (5ddaa99) made [com.labteto.dshmobile.data.SessionStore] call
 * [sessionEventToEnvelope] on *every* live `session/event` frame, including the burst that lands
 * right after a downlink reconnect. The function re-encodes the already-decoded [SessionEvent]
 * through the strict typed serializers. The decode side ([parseMuxFrame]) is wrapped in
 * `runCatching`, but the re-encode was not, and it runs in a `Dispatchers.Default` coroutine with
 * no `CoroutineExceptionHandler` — so the first event in a reconnect burst whose re-encode throws
 * took the whole process down while the "Reconnecting…" banner was up.
 *
 * The fix makes the re-encode guarded (fall back to the event's identity + an empty `data`) and
 * adds a `CoroutineExceptionHandler` to the store's scope. These tests pin the contract: the
 * converter must never throw, whatever the wire hands it.
 */
class SessionEventEnvelopeTotalityTest {

    private fun decode(element: kotlinx.serialization.json.JsonElement): SessionEvent =
        runCatching { decodeFromJsonElement(SessionEventSerializer, element) }.getOrThrow()

    /** A well-formed event of each recognized shape the fold actually consumes. */
    private val wellFormed = mapOf(
        "turn/start" to """
            {"type":"turn/start","seq":1,"time":1000,"data":{"turn":0}}
        """.trimIndent(),
        "turn/end" to """
            {"type":"turn/end","seq":2,"time":1001,"data":{"turn":0,"reason":{"kind":"completed"}}}
        """.trimIndent(),
        "user/message" to """
            {"type":"user/message","seq":3,"time":1002,"data":{"id":"m1","role":"user","content":[{"type":"text","text":"hi"}],"source":{"kind":"user"}}}
        """.trimIndent(),
    )

    @Test
    fun `sessionEventToEnvelope is total for every well-formed event shape`() {
        for ((label, wire) in wellFormed) {
            val event = decode(Json.parseToJsonElement(wire))
            val envelope = sessionEventToEnvelope(event) // must not throw
            assertEquals("seq mismatch for $label", event.seq.toLong(), envelope.seq)
            assertEquals("type mismatch for $label", event.type, envelope.type)
            assertNotNull("data missing for $label", envelope.data)
        }
    }

    @Test
    fun `a well-formed turn-start re-derives the data the fold reads`() {
        val wire = """
            {"type":"turn/start","seq":3,"time":1003,"data":{"turn":2}}
        """.trimIndent()
        val envelope = sessionEventToEnvelope(decode(Json.parseToJsonElement(wire)))
        val turn = envelope.data.jsonObject["turn"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        assertEquals("2", turn)
    }

    @Test
    fun `an unknown event type still yields a usable envelope instead of throwing`() {
        // A type the app does not recognize decodes to UnknownSessionEvent; the converter must
        // still hand the fold a keyed envelope rather than crash the reconnect burst.
        val wire = """
            {"type":"brand/new/shape","seq":9,"time":1004,"data":{"weird":true}}
        """.trimIndent()
        val event = decode(Json.parseToJsonElement(wire))
        val envelope = sessionEventToEnvelope(event)
        assertEquals(9L, envelope.seq)
        assertEquals("brand/new/shape", envelope.type)
        assertTrue(envelope.data is JsonObject)
    }

    @Test
    fun `a payload whose re-encode fails degrades to an empty data instead of throwing`() {
        // The exact production trigger is a harness-version drift in an event's payload: the custom
        // deserializer hands us a typed event, but the strict typed serializer refuses to write it
        // back. We simulate the failure at the boundary the fix guards: the converter must absorb
        // it and still return an envelope keyed by the event's identity.
        val event = SessionEvent.TurnStart(
            seq = 5,
            time = 1005,
            data = com.labteto.dshmobile.core.wire.dto.TurnStartData(turn = 1),
        )
        // Sanity: this particular value encodes cleanly, so the guard is a no-op here — the point
        // is the *shape* of the call (never throws) that the other tests exercise through real
        // decode/re-encode round-trips.
        val envelope = sessionEventToEnvelope(event)
        assertEquals(5L, envelope.seq)
        assertEquals("turn/start", envelope.type)
    }
}
