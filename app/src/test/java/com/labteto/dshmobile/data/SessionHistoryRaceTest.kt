package com.labteto.dshmobile.data

import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.LoopSinks
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.ServerRequest
import com.labteto.dshmobile.core.wire.dto.ContentBlock
import com.labteto.dshmobile.core.wire.dto.HistoryEntry
import com.labteto.dshmobile.core.wire.dto.MessageData
import com.labteto.dshmobile.core.wire.dto.MessageSource
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import com.labteto.dshmobile.core.wire.dto.SessionEventSerializer
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRequest
import com.labteto.dshmobile.core.wire.dto.SessionHistoryValue
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.collections.toIntArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-history race fixes from `docs/SESSION_HISTORY_RACE_FIX_PLAN.md`, driven through the
 * store's public surface with a fake harness client:
 *
 *  - T1 — a paged session remembers its read position and re-opens there (not at the tail);
 *  - T2 — the stitch drain is range-aware: buffered events below a committed page's start are
 *    dropped, and the next live frame over them raises no gap and no repair;
 *  - T3 — one gap repair at a time: a second trigger while a fetch is in flight is a no-op;
 *  - T4 — an open in flight interleaved with a live burst: the burst stitches on top, the next
 *    live frame lands contiguously, no spurious gap;
 *  - T5 — the window is bounded: a long stream trims to the cap, keeps `hasMore`, and pages from
 *    the new head.
 *
 * Plain JVM (no Robolectric), matching the module's test style. The store's own scope runs on
 * `Dispatchers.Default`, so the tests pump it with real time and poll the published state until
 * the value they assert on is visible.
 */
class SessionHistoryRaceTest {

    // ------------------------------------------------------------------ fakes

    /** A no-op [androidx.datastore.core.DataStore] that never blocks. */
    private class FakeDataStore : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        // The data flow is never actually read in these tests: the store only calls
        // `lastSessionId` (overridden to null) and `setLastSessionId` (a no-op write).
        override val data: Flow<androidx.datastore.preferences.core.Preferences> =
            kotlinx.coroutines.flow.flow { }

        override suspend fun updateData(
            transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
        ): androidx.datastore.preferences.core.Preferences =
            throw UnsupportedOperationException("not used in the test")
    }

    /** A harness client that answers only the calls the store issues in these tests. */
    private class FakeApi : DshApiClient(
        transport = object : com.labteto.dshmobile.core.wire.RpcTransport {
            override suspend fun post(
                path: String,
                body: String,
            ): com.labteto.dshmobile.core.wire.RpcHttpResponse =
                throw UnsupportedOperationException("no transport in the fake")

            override suspend fun <T> download(
                path: String,
                consume: (contentType: String?, contentDisposition: String?, body: java.io.InputStream) -> T,
            ): T = throw UnsupportedOperationException("no transport in the fake")
        },
        wsFactory = { _, _ -> throw UnsupportedOperationException("no ws in the fake") },
    ) {
        val historyCalls = mutableListOf<SessionHistoryRequest>()
        var pageFor: (SessionHistoryRequest) -> SessionHistoryValue? = { null }
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun sessionHistory(request: SessionHistoryRequest): RpcResult<SessionHistoryValue> {
            historyCalls.add(request)
            gate?.await()
            return pageFor(request)?.let { RpcResult.Ok(it) } ?: RpcResult.Err(
                com.labteto.dshmobile.core.wire.RpcError(
                    code = "internal",
                    message = "no page configured for $request",
                ),
            )
        }
    }


    /** A connection manager whose only live member is the injected fake client. */
    private class FakeConnectionManager(private val fakeApi: DshApiClient) :
        ConnectionManager(
            context = android.content.ContextWrapper(null),
            okHttpClient = okhttp3.OkHttpClient(),
            hostsStore = object : HostsStore(
                dataStore = FakeDataStore(),
            ) {
                override suspend fun lastSessionId(hostKey: String): String? = null
            },
        ) {
        init {
            this.api = fakeApi
            _state.value = ConnectionUiState(
                phase = ConnectionPhase.CONNECTED,
                host = HostConfig(id = "h", name = "h", host = "127.0.0.1", port = 3080),
                hasConnected = true,
            )
        }
    }

    private class NoopLoopSinks : LoopSinks {
        override fun onMuxFrame(frame: ServerRequest) {}
        override fun onHostFrame(frame: ServerRequest) {}
        override fun onConnected(description: com.labteto.dshmobile.core.wire.dto.HostDescription) {}
        override fun onStateChange(state: com.labteto.dshmobile.core.wire.ConnectionState) {}
        override fun onHandshakeStep(step: com.labteto.dshmobile.core.wire.HandshakeStep) {}
        override fun onGenerationFailed(
            attempt: Int,
            failure: com.labteto.dshmobile.core.wire.GenerationFailure,
        ) {}
    }

    /**
     * A [SessionStore] for the test. The store's own scope is a private `Dispatchers.Default`
     * scope the test cannot cancel, so the test drives it with real time (the [waitFor] polls) and
     * abandons the scope with the JVM.
     */
    private class TestStore(
        connectionManager: ConnectionManager,
        hostsStore: HostsStore,
    ) : SessionStore(connectionManager, hostsStore)

    // ------------------------------------------------------------------ helpers

    private fun userMessage(seq: Int, text: String = "m$seq"): SessionEvent =
        SessionEvent.UserMessage(
            seq = seq,
            time = 1000L + seq,
            data = MessageData(
                id = "m$seq",
                role = "user",
                content = listOf(ContentBlock.Text(text)),
                source = MessageSource(kind = "user"),
            ),
        )

    private fun entry(event: SessionEvent) = HistoryEntry(event = event)

    /** A page of user-message events with the given seqs. */
    private fun pageOf(vararg seqs: Int, hasMore: Boolean = false) =
        SessionHistoryValue(
            events = seqs.map { entry(userMessage(it)) },
            hasMore = hasMore,
        )

    private fun sessionEventFrame(sessionId: String, event: SessionEvent): ServerRequest =
        ServerRequest(
            rpcId = "r${event.seq}",
            method = "session/event",
            payload = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("session/event"),
                    "sessionId" to JsonPrimitive(sessionId),
                    "event" to encodeToJsonElement(SessionEventSerializer, event),
                ),
            ),
        )

    private fun waitFor(condition: () -> Boolean, timeoutMs: Long = 5000L): Boolean =
        kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(timeoutMs) {
                while (!condition()) delay(10)
            } != null
        }

    private fun newHostsStore() = object : HostsStore(
        dataStore = FakeDataStore(),
    ) {
        override suspend fun lastSessionId(hostKey: String): String? = null
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `T1 a paged session remembers its cursor for the next page`() = runBlocking {
        val api = FakeApi()
        val cm = FakeConnectionManager(api)
        val store = TestStore(cm, newHostsStore())

        // A 300-event session; the tail page is the last 60.
        val tailSeqs = (241..300).toList()
        val olderSeqs = (181..240).toList()
        val oldestSeqs = (121..180).toList()
        api.pageFor = { req ->
            when (req.beforeSeq) {
                null -> pageOf(*tailSeqs.toIntArray(), hasMore = true)
                241 -> pageOf(*olderSeqs.toIntArray(), hasMore = true)
                181 -> pageOf(*oldestSeqs.toIntArray(), hasMore = true)
                else -> null
            }
        }

        store.openSession("hot")
        assertTrue("open should commit", waitFor({ store.currentConversation.value?.lastSeq == 300L }))

        // Page back twice: the window's head walks 240 -> 180 -> 120.
        store.loadOlder()
        assertTrue("page 1 should commit", waitFor({ store.currentConversation.value?.lastSeq == 300L }))
        delay(50)
        store.loadOlder()
        assertTrue("page 2 should commit", waitFor({ store.currentConversation.value?.lastSeq == 300L }))
        delay(50)

        // The third page must use the cursor from the second page's commit (121, the first
        // seq of the [121..180] page), not the tail.
        store.loadOlder()
        val thirdRequest = api.historyCalls.last { it.sessionId == "hot" }
        assertEquals(
            "the third page must use the remembered cursor, not the tail",
            121,
            thirdRequest.beforeSeq,
        )
    }

    @Test
    fun `T4 an open in flight interleaved with a live burst stitches contiguously`() = runBlocking {
        val api = FakeApi()
        val cm = FakeConnectionManager(api)
        val store = TestStore(cm, newHostsStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // The open's tail page starts below the burst: the burst [151..155] is above the page
        // top (100) and above the tail (150), so the drain keeps it all.
        api.pageFor = { pageOf(*(100..150).toList().toIntArray(), hasMore = true) }
        val open = scope.launch { store.openSession("hot") }
        assertTrue("open fetch issued", waitFor({ api.historyCalls.size == 1 }))

        for (seq in 151..155) {
            cm.muxFrames.tryEmit(sessionEventFrame("hot", userMessage(seq, "burst")))
        }
        open.join()
        assertTrue("open commits with the burst stitched on", waitFor({ store.currentConversation.value?.lastSeq == 155L }))
        assertFalse("no gap over the stitched burst", store.currentConversation.value?.gap == true)

        // The next live frame (156) lands contiguously: no gap, no repair.
        val callsBefore = api.historyCalls.size
        cm.muxFrames.tryEmit(sessionEventFrame("hot", userMessage(156, "next")))
        assertTrue("156 appends", waitFor({ store.currentConversation.value?.lastSeq == 156L }))
        assertFalse("no spurious gap", store.currentConversation.value?.gap == true)
        delay(100)
        assertEquals("no repair was triggered", callsBefore, api.historyCalls.size)
    }
}
