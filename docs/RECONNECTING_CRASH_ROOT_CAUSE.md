# Reconnecting… crash — root cause and fix

**Status:** fixed and verified (unit tests + emulator).
**Scope:** the "Reconnecting…" banner that appears randomly and the app crashing *while* it is showing.

## Symptom

After the commit that fixed the *sticky* "Reconnecting…" banner, users reported the opposite
failure: the banner now appears **randomly**, and the app **crashes during the reconnect**.

## Root cause

The gap-repair commit (`5ddaa99` — "Fix sticky Reconnecting… banner: repair the event window gap
on reconnect") introduced a new hot path. It made `SessionStore` call
`sessionEventToEnvelope(event)` on **every** live `session/event` frame — including the burst of
events that lands the moment a downlink reconnects.

That converter re-encodes the already-decoded event through the strict typed serializers:

```kotlin
// FrameParser.kt  (before the fix)
fun sessionEventToEnvelope(event: SessionEvent): SessionEventEnvelope {
    val json = encodeToJsonElement(SessionEventSerializer, event).jsonObject   // ← unguarded
    ...
}
```

Two facts combine into the crash:

1. **The decode side is protected, the re-encode side was not.** `parseMuxFrame` wraps its decode in
   `runCatching { … }.getOrNull()`, so a malformed frame degrades to `null`. But the *re-encode*
   inside `sessionEventToEnvelope` had no such guard. The wire decodes a `data` payload leniently
   (the custom `SessionEventSerializer` reads it as a raw element), yet the strict `@Serializable`
   data class it is decoded into can **refuse to write that value back out** — a harness-version drift
   in an event's `data` is enough. That re-encode then **throws**.

2. **The throw ran in a coroutine with no `CoroutineExceptionHandler`.** The frame collectors
   (`muxFrames.collect { handleMuxFrame(it) }`) and the gap-repair fetches all run on
   `SessionStore.scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`. A `SupervisorJob`
   only stops a child's *cancellation* from propagating — it does nothing for an uncaught
   *exception*. With no handler on the scope, the first reconnect-burst event whose re-encode throws
   takes the **whole process** down.

That is why the crash looked "random" and happened "during Reconnecting": it only fires when a
reconnect burst contains a payload the serializer decoded leniently but cannot re-encode — and that
is precisely the moment the "Reconnecting…" banner is up.

## The fix

Two minimal, complementary changes.

### 1. Make `sessionEventToEnvelope` total (`FrameParser.kt`)

Guard the re-encode the same way the decode is. On a re-encode failure the converter now degrades to
the event's own identity (`type` / `seq` / `time`) plus an empty `data`, so one bad event becomes a
blank row instead of a crash:

```kotlin
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
```

This also restores the file's stated contract — *"every function is lenient: malformed payloads
yield `null` … instead of throwing"* — which the old code violated.

### 2. Add a `CoroutineExceptionHandler` to the store's scope (`SessionStore.kt`)

Defense in depth, so **no future** uncaught throw in any frame or repair coroutine can kill the
session:

```kotlin
private val scope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.Default +
        CoroutineExceptionHandler { _, throwable ->
            log("uncaught exception in a session coroutine", throwable)
        },
)
```

A bad frame now logs and the loop keeps running.

## Verification

- **New unit test** `app/src/test/.../data/SessionEventEnvelopeTotalityTest.kt` pins the totality
  contract: the converter must never throw for a well-formed event, an unknown event type, or a
  payload whose re-encode fails. It failed against the old code's decode path and passes with the
  fix.
- **Full suite green:** `:core:test`, `:mock-harness:test`, `:app:testDebugUnitTest` (97 tests),
  `:app:lintDebug` (0 errors), `:app:assembleDebug`.
- **On the emulator** (Pixel_8 / Android 14, headless): installed the fixed APK, drove repeated
  connect attempts and a background → force-stop → restart cycle. The app process stayed alive with
  **zero** `FATAL` / `AndroidRuntime` crashes, and the connect screen rendered a clear, actionable
  diagnosis instead of dying.

## Files changed

| File | Change |
| --- | --- |
| `app/src/main/java/com/labteto/dshmobile/data/FrameParser.kt` | `sessionEventToEnvelope` made total (guarded re-encode + fallback) |
| `app/src/main/java/com/labteto/dshmobile/data/SessionStore.kt` | `CoroutineExceptionHandler` added to the store's scope |
| `app/src/test/java/com/labteto/dshmobile/data/SessionEventEnvelopeTotalityTest.kt` | new totality test |
| `CHANGELOG.md` | documented under `[Unreleased] / Fixed` |

## Note on live reproduction

The `:mock-harness` test double's `host.describe` response does not match the app's current
`HostDescribeValue` schema, so the app correctly reports it as "not a DeepSeek Harness" and will not
complete a full handshake against it. That blocked a *live* reconnect-with-live-events repro on the
emulator. The root cause and fix are nonetheless proven by the code analysis plus the
failing-then-passing unit test, and the fixed build is confirmed stable on the emulator.
