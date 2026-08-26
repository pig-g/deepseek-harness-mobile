package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import com.labteto.dshmobile.core.wire.dto.ContentBlock
import com.labteto.dshmobile.core.wire.dto.HostFrame
import com.labteto.dshmobile.core.wire.dto.HostFrameSerializer
import com.labteto.dshmobile.core.wire.dto.MessageData
import com.labteto.dshmobile.core.wire.dto.MuxFrame
import com.labteto.dshmobile.core.wire.dto.MuxFrameSerializer
import com.labteto.dshmobile.core.wire.dto.QueuedInboxItem
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import com.labteto.dshmobile.core.wire.dto.SessionEventSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Pure, unit-testable frame-decode helpers shared by [SessionStore] and the notification
 * observer. Every function is lenient: malformed payloads yield `null` (or an empty snapshot
 * field) instead of throwing, because stream frames are merge-extensible and may drift.
 */

/** Decode a mux-stream frame payload (`session/event`, `approval/requested`, …) or null. */
fun parseMuxFrame(payload: JsonElement): MuxFrame? =
    runCatching { decodeFromJsonElement(MuxFrameSerializer, payload) }.getOrNull()

/** Decode a host-stream frame payload (`host/session-added`, …) or null. */
fun parseHostFrame(payload: JsonElement): HostFrame? =
    runCatching { decodeFromJsonElement(HostFrameSerializer, payload) }.getOrNull()

/**
 * Convert a typed [SessionEvent] into the raw-envelope shape the fold consumes. `data` is the
 * raw JSON of the event's payload (re-derived from the serializer so it stays a [JsonElement]
 * exactly as the fold expects); `surfaceOp` is stringified for the envelope.
 *
 * **Total, by contract.** This is called on the hot path for *every* live `session/event` frame —
 * including the burst that lands right after a downlink reconnect — from a `Dispatchers.Default`
 * coroutine that has no `CoroutineExceptionHandler`. The decode side ([parseMuxFrame]) is wrapped
 * in `runCatching`, but the re-encode below was not: a `data` payload the strict typed serializer
 * decodes leniently yet refuses to write back (a harness-version drift in an event's payload)
 * threw here and, with nothing to catch it, took the whole process down while the "Reconnecting…"
 * banner was up. The re-encode is now guarded the same way the decode is: on a re-encode failure we
 * fall back to the event's own identity plus an empty `data`, so a single bad event degrades to a
 * blank row instead of a crash.
 */
fun sessionEventToEnvelope(event: SessionEvent): SessionEventEnvelope {
    val json = runCatching { encodeToJsonElement(SessionEventSerializer, event) }
        .getOrNull()?.jsonObject
    val data = json?.get("data") ?: JsonObject(emptyMap())
    val surfaceOp = json?.get("surfaceOp")?.let { raw ->
        (raw as? JsonPrimitive)?.contentOrNull ?: raw.toString()
    }
    return SessionEventEnvelope(
        type = event.type,
        seq = event.seq.toLong(),
        time = event.time,
        data = data,
        surfaceOp = surfaceOp,
    )
}

/** Decode a raw `session/event` event object into a [SessionEventEnvelope], or null on drift. */
fun parseSessionEventEnvelope(eventJson: JsonElement): SessionEventEnvelope? =
    runCatching { sessionEventToEnvelope(decodeFromJsonElement(SessionEventSerializer, eventJson)) }
        .getOrNull()

/** Convert one authoritative queue snapshot item into the renderer-facing [QueueItem]. */
fun queuedInboxItemToQueueItem(item: QueuedInboxItem): QueueItem {
    val preview = item.message.content
        .filterIsInstance<ContentBlock.Text>()
        .firstOrNull()
        ?.text
        ?.take(120)
        .orEmpty()
    val content = encodeToJsonElement(MessageData.serializer(), item.message)
    return QueueItem(
        id = item.id,
        placement = item.placement,
        previewText = preview,
        content = content,
    )
}
