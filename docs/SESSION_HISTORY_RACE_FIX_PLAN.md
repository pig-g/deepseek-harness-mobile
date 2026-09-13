# Fix plan — session-history races: false "Reconnecting…", full-history rebuilds, unbounded window

**Status:** plan (not yet implemented).
**Scope:** `app/src/main/java/com/labteto/dshmobile/data/SessionStore.kt`,
`core/src/main/kotlin/com/labteto/dshmobile/core/session/EventFold.kt`,
plus new unit tests under `app/src/test/java/com/labteto/dshmobile/data/`.
**Companion docs:** `RECONNECTING_CRASH_ROOT_CAUSE.md` (the crash fix this builds on),
`ANDROID_EMULATOR_HOWTO.md` (how to verify on the emulator).

---

## 1. Problem statement (observed on the emulator)

While a session is **running** (LLM streaming), the user:

1. scrolls up — "Loading earlier messages…" pages history in, repeatedly, to the top;
2. switches to another (dead) session;
3. returns to the hot session.

Observed costs:

- **All earlier messages are reloaded from scratch** — the paged window is discarded and the
  transcript re-anchors to the tail; paging to the top again repeats the whole walk.
- **"Reconnecting…" appears even though the network line is healthy.**
- Every such episode triggers a **full history rebuild** (refold of the entire in-memory window
  + full transcript re-render), which is slow and energy-hungry precisely while the agent runs.

## 2. Root causes (traced in code)

### RC1 — `openSession` always re-fetches the tail; the read position is forgotten

`SessionStore.openSession()` (line ~924) clears the window under the lock and then requests

```kotlin
api.sessionHistory(SessionHistoryRequest(sessionId, null, HISTORY_PAGE_SIZE))  // beforeSeq = null
```

`beforeSeq = null` means "newest page". The position the user paged to
(`currentEvents.first().seq`) is **never remembered**, so a return to the session discards the
whole paged window and re-opens at the tail.

### RC2 — `repairMissedEvents` *replaces* the window on every repair

`SessionStore.repairMissedEvents()` (line ~1039) commits with `currentEvents.clear()` + fresh
tail page + `rebuildCurrentLocked()`. A running session emits a live burst while the user is
away; on return the first live frame past the tail triggers a repair, which wipes even the
just-fetched page and re-fetches it. Two full tail refetches per return-to-hot-session.

### RC3 — the gap test cannot see the stitch buffer → false "Reconnecting…"

The banner comes from the fold's gap test (`EventFold.kt:100`):

```kotlin
if (event.seq > lastSeq + 1 && lastSeq >= 0 && !gap) gap = true
```

and the repair trigger in `handleSessionEvent` (`SessionStore.kt:557`) is the same arithmetic:
`if (seq > tail + 1) repairMissedEvents(...)`. Both assume any seq jump means "events were
committed while the downlink was down". But a jump can be **client-side**:

- **RC3a — drain re-appends below the tail.** `drainStitchBufferLocked()` (line ~789) drops a
  buffered live event only when `seq <= tail`. The committed page is a *tail* page (60 messages);
  buffered events **below the page's start** (easy on a hot session — the log advanced a lot
  while the user was away) are re-appended *below the window's tail*. The next live event above
  them then lands at `tail + k, k > 1` → `gap = true` → banner → another repair → another
  replace — on a perfectly healthy line.
- **RC3b — two overlapping repairs.** `repairMissedEvents` is launched from the frame collector
  without a mutex. The `fetchToken` guard makes the second *commit* a no-op, but both fetches
  still hit the server, and the second's `stitchBuffer` drain can interleave with the first's
  commit, re-introducing the same out-of-order event. Same symptom: spurious gap, spurious
  rebuild loop.
- **RC3c — open/repair race.** While an `openSession` fetch is in flight, a live burst is
  buffered in `stitchBuffer`; if the open's tail page starts above part of that burst, the drain
  does the same below-tail re-append.

### RC4 — the window is never trimmed; the fold is O(n) per commit

`loadOlder()` only prepends; nothing is ever evicted from `currentEvents`. After paging to the
top of a long session the window holds the **entire history in RAM**, and every commit
(`rebuildCurrentLocked()`) runs `EventFold.fold(currentEvents.toList())` — a full refold of all
of it. Total cost to the top is O(n²); every spurious reconnect (RC3) multiplies it. This is
the "heavy load / energy" part, and it is worst exactly while the LLM is running (highest burst
density).

### What is already safe (do not regress)

- `loadOlder()` is race-safe: `compareAndSet` in-flight flag + `synchronized(lock)` commit +
  seq-dedupe. Keep it.
- The crash fix from `RECONNECTING_CRASH_ROOT_CAUSE.md` (total `sessionEventToEnvelope` +
  `CoroutineExceptionHandler` on the store scope) stays as-is; this plan only changes *what*
  gets folded, not *how* it is guarded.
- `fetchToken` supersession semantics in `openSession`/`repairMissedEvents` stay; we add a
  mutex, not a token redesign.

## 3. Changes

All in `SessionStore.kt` unless noted. New members are private.

### C1 — Remember the read position per session (fixes RC1)

```kotlin
/** Oldest seq still in (or just above) the window, per session — the paging cursor. */
private val oldestLoadedSeq = HashMap<String, Long>()
```

- In `loadOlder()`'s commit (inside the existing `synchronized(lock)` block), after the
  dedupe/merge: `oldestLoadedSeq[sid] = currentEvents.firstOrNull()?.seq ?: return`.
- In `openSession(sessionId)`:

  ```kotlin
  val beforeSeq = oldestLoadedSeq[sessionId]?.toIntOrNull()
  val r = api.sessionHistory(SessionHistoryRequest(sessionId, beforeSeq, HISTORY_PAGE_SIZE))
  ```

  - `beforeSeq != null` → the user lands **where they left off**; the page is the window
    starting at that seq, and the live tail is stitched on top by the existing `stitchBuffer`
    mechanism (events with `seq > page tail` already drain in correctly).
  - `beforeSeq == null` (never paged, or a fresh session) → `null` as today (tail page).
- In `repairMissedEvents`'s commit, refresh `oldestLoadedSeq[sessionId]` from the committed
  page's first seq (the page is contiguous, so its first seq is the new cursor).
- On `disconnect()`/session archive, drop the entry (avoid stale cursors across connections).

**UI note:** `ChatTranscript` already re-anchors to the tail only on session *switch* or
`followHint`; landing mid-history after C1 is a new but desired state — verify the
`LaunchedEffect(itemCount, sessionId, followHint)` block does not force a tail scroll when the
switched-to session has a remembered position (it must not, since `switched` is true — see
verification V4; if it does, add a `landAtTail: Boolean` parameter to the effect's decision).

### C2 — Range-aware stitch drain (fixes RC3a)

`drainStitchBufferLocked()` currently: `if (seq <= tail) continue`. Change to:

```kotlin
// Drop anything the committed page already spans: below-or-at its first seq, or below the
// tail. Keeping only events strictly above the page's top is what prevents the next live
// frame from landing "past the tail" over a hole we just filled.
val pageStart = currentEvents.firstOrNull()?.seq ?: 0L
if (seq <= pageStart || seq <= tail) continue
```

(When the page is empty, `pageStart = 0` and the old `seq <= tail` rule still applies since
`tail = -1` → only the `seq <= pageStart` branch is active; keep both checks for clarity.)

### C3 — One repair at a time (fixes RC3b)

Add a plain flag guarded by the existing lock:

```kotlin
@Volatile private var repairInFlight = false
```

- At the top of `repairMissedEvents`: `if (repairInFlight) return` (set it under the lock
  together with `repairing = true`).
- Clear it in the same places `repairing` is cleared (Ok commit, Err path, `finally`).
- The `fetchToken` supersession logic stays as the commit guard; the flag only stops the
  *second fetch* from being issued.

### C4 — Gap test that can see the buffer (fixes RC3, banner side)

The fold is pure and cannot see `stitchBuffer`, so do the "is this jump real?" check in
`handleSessionEvent` before triggering anything:

- Keep the existing `seq > tail + 1` trigger **only** when no buffered or in-flight event
  covers `(tail, seq)`:

  ```kotlin
  if (seq > tail + 1) {
      val covered = stitchBuffer.any { it.event.seq in (tail + 1)..seq } ||
          repairInFlight
      if (!covered) scope.launch { repairMissedEvents(sessionId) }
  }
  ```

- For the *banner*: `rebuildCurrentLocked()` currently copies `snapshot.gap` into the
  conversation. Suppress the banner while the hole is explainable: after the fold, if
  `snapshot.gap` is true but every missing seq in the hole is present in `stitchBuffer` or a
  repair is in flight, emit the snapshot with `gap = false`. (Smallest implementation: a
  `val explainable = ...` computed in the same `synchronized` block, passed as a parameter to
  the `merged` copy.) The fold itself stays pure — no `EventFold.kt` change needed for C4.

### C5 — Bounded window (fixes RC4)

```kotlin
private const val MAX_WINDOW_EVENTS = 500
```

- After any commit that grows the window (open, repair, loadOlder, live append), trim:

  ```kotlin
  while (currentEvents.size > MAX_WINDOW_EVENTS) {
      currentEvents.removeAt(0)
  }
  ```

  and set `currentHasMore = true` whenever a trim happens (the paging row stays available; its
  cursor is `oldestLoadedSeq` from C1, so re-paging below the trim point still works).
- `oldestLoadedSeq` must be updated to the *new* first seq after a trim, so the next
  `loadOlder`/`openSession` pages from the right place.
- The trim runs inside the existing `synchronized(lock)` blocks; it is O(k) removals from the
  head of an already-sorted list — fine at 500.

**Trade-off note:** a user who paged to the very top of a 5000-message session will, after a
trim, see the window start at message ~4501 with a "load older" row above it — the same UX as
today, just with a bounded memory floor. The transcript's `hasMore` row already renders in that
case.

### C6 — (Optional, follow-up) incremental fold for prepends

`EventFold.Incremental` exists for appends. A prepend-aware variant would make each page
O(page) instead of O(window). **Defer** — C5 already caps the window at 500, which bounds the
full refold; revisit only if profiling shows the 500-event refold is still visible.

## 4. Tests (new file `app/src/test/java/com/labteto/dshmobile/data/SessionHistoryRaceTest.kt`)

Plain JVM, no Robolectric (matches the module's test style). Drive `SessionStore` through its
public surface with a fake `DshApiClient` (the store takes `ConnectionManager`; if the existing
test seams don't allow injecting a fake api, add a package-private constructor parameter —
check `SessionStore`'s existing test setup first and reuse it).

1. **T1 remembered position:** page back two pages (fake history returns distinct seq ranges),
   `openSession(other)`, `openSession(hot)` → assert the `sessionHistory` request carried
   `beforeSeq ==` the oldest paged seq, not `null`; assert the rendered window starts at the
   remembered position with the live tail stitched above it.
2. **T2 drain range-awareness:** commit a tail page `[100..159]`; buffer live events
   `[90..99]` (below page start) and `[160]`; drain → assert `[90..99]` are dropped, `[160]`
   appended; then feed a live event `161` → assert **no** gap flag and **no** repair triggered.
3. **T3 double repair:** trigger a repair, then a second trigger while the first fetch is in
   flight (fake api suspends on a gate) → assert exactly **one** `sessionHistory` call is
   issued for the repair; the second trigger is a no-op.
4. **T4 open/repair interleave:** open in flight (gated), live burst `[tail+2 .. tail+5]`
   buffered, open commits a tail page starting below the burst → drain keeps only events above
   the page top; next live event `tail+6` → no spurious gap.
5. **T5 window bound:** feed 600 events via appends → `currentEvents.size <= 500`,
   `hasMore == true`, `oldestLoadedSeq` equals the new first seq; a subsequent `loadOlder`
   pages from that seq.
6. **T6 regression:** the existing totality test (`SessionEventEnvelopeTotalityTest`) and the
   full `:app:testDebugUnitTest` suite stay green.

## 5. Verification on the emulator (per `ANDROID_EMULATOR_HOWTO.md`)

Boot Pixel_8 headless, install the rebuilt debug APK, connect to the real harness via
`10.0.2.2:3080` (the `ConnectViewModel` loopback set already includes `10.0.2.2`).

- **V1 (the reported scenario):** start a long-running session (e.g. a multi-step Bash job);
  scroll to the top until paging stops; switch to a dead session; switch back.
  - Before: transcript re-anchors to the tail; re-paging to the top re-fetches everything.
  - After: the transcript lands at the remembered position; at most one page fetch; no
    "Reconnecting…" banner; `logcat` shows a single `session.history` for the return.
- **V2 (false reconnect):** with the session hot, force a reconnect burst (kill/restart the
  harness, or `adb shell am force-stop` + relaunch the app) while the user is at the top of a
  long transcript. Before: "Reconnecting…" appears and a full rebuild runs. After: no banner
  unless a real hole exists; if a real hole exists, one bounded repair (≤ 500-event refold).
- **V3 (crash regression):** `logcat -d | grep -E "FATAL EXCEPTION|E/AndroidRuntime"` stays
  empty across all of the above (guards the `RECONNECTING_CRASH_ROOT_CAUSE.md` fix).
- **V4 (scroll anchoring):** after C1, switching *into* a session with a remembered position
  must not yank the list to the tail (check `ChatTranscript`'s `LaunchedEffect` decision; if it
  does, add the `landAtTail` guard noted in C1).
- **V5 (energy sanity):** `adb shell dumpsys meminfo <pid>` before/after a full
  page-to-top + switch + return cycle: PSS growth should be bounded (window cap), not
  proportional to total history.

## 6. Rollout order

1. C5 (window cap) + T5 — smallest, biggest memory win, no behavior change for tail readers.
2. C2 (drain) + C3 (repair mutex) + C4 (explainable-gap) + T2/T3/T4 — kills the false
   "Reconnecting…".
3. C1 (remembered position) + T1 + V1/V4 — the UX change; do it last so the earlier fixes are
   already stabilizing the window it operates on.
4. CHANGELOG entry under `[Unreleased] / Fixed` + `Improved`.

## 7. Risks

- **C1 changes where the transcript lands** — the one user-visible behavior change; covered by
  V4 and the `landAtTail` guard.
- **C5 trim vs. a user mid-read at the very top** — the paging row re-appears; that is the
  intended "bounded window" UX, but confirm it doesn't flicker the list (the `load-older` item
  is already keyed, so it should be stable).
- **Cursor staleness across harness restarts** — if the harness's log is truncated/rotated, a
  remembered `beforeSeq` may page into nothing; the existing `RpcResult.Err` path shows the
  retry row, and a failed page should reset `oldestLoadedSeq[sid] = null` (add to C1's commit
  guard: on `Err`, drop the cursor).
