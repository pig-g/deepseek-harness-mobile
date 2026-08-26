package com.labteto.dshmobile.data

import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.AgentPresetCopyRequest
import com.labteto.dshmobile.core.wire.dto.AgentPresetOpenDocumentRequest
import com.labteto.dshmobile.core.wire.dto.AgentPresetOpenDocumentValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetReadRequest
import com.labteto.dshmobile.core.wire.dto.AgentPresetReadValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetRemoveRequest
import com.labteto.dshmobile.core.wire.dto.CredentialsDescribeRequest
import com.labteto.dshmobile.core.wire.dto.CredentialsDescribeValue
import com.labteto.dshmobile.core.wire.dto.CredentialsSetRequest
import com.labteto.dshmobile.core.wire.dto.CredentialsUnsetRequest
import com.labteto.dshmobile.core.wire.dto.DiscoveredModelView
import com.labteto.dshmobile.core.wire.dto.HostFrame
import com.labteto.dshmobile.core.wire.dto.LlmDiscoverModelsRequest
import com.labteto.dshmobile.core.wire.dto.LlmModelsValue
import com.labteto.dshmobile.core.wire.dto.LlmProvidersValue
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.core.wire.dto.SettingsMutateRequest
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mirror of the harness's configuration plane: the settings document, credential
 * references, the provider directory, and agent-preset authoring.
 *
 * This mirrors the web client's settings mirror + models store: one `settings.describe` seeds the
 * mirror, every write answer carries the namespace's new redacted view (folded straight back
 * instead of a refetch), and remote `host/remote-event` frames (`settings/document-updated`,
 * `credentials/reference-updated`, `llm/adapters-updated`) invalidate it. Writes CAS on the
 * revision the mirror held; a `settings-conflict` re-reads the mirror and retries once against
 * the revision the harness actually has.
 *
 * A trust-fenced (HTTP 403) or uncomposed (404) harness refuses the plane outright:
 * [PlaneStatus.Refused] is terminal until the connection resets. The non-privileged reads
 * ([llmProviders], [llmModels], [pluginInventory]) deliberately keep working in that state, so a
 * read-only harness screen can still show what the host exposes.
 */
@Singleton
class SettingsPlane @Inject constructor(
    private val connectionManager: ConnectionManager,
) {
    /** Read state of the settings mirror. */
    sealed interface PlaneStatus {
        /** No answer yet: not connected, or nothing has loaded since the last reset. */
        data object Idle : PlaneStatus

        /** A describe call is in flight. */
        data object Loading : PlaneStatus

        /** The mirror is filled; edits are possible when [PlaneUi.writable] holds. */
        data object Ready : PlaneStatus

        /** The harness refused the plane (trust fence, or no settings service composed). */
        data object Refused : PlaneStatus

        /** describe failed for an ordinary reason; [PlaneUi.loadError] explains. */
        data object Unavailable : PlaneStatus
    }

    /** Why the plane is [PlaneStatus.Refused], so the banner can say the right thing. */
    enum class Refusal {
        /** HTTP 403: the harness was started without `--allow-privileged-remote`. */
        TrustFence,

        /** HTTP 404: this harness build composes no settings service at all. */
        Unsupported,
    }

    /** The mirror's UI state: status plus whatever it currently holds. */
    data class PlaneUi(
        val status: PlaneStatus = PlaneStatus.Idle,
        val refusal: Refusal = Refusal.TrustFence,
        val writable: Boolean = false,
        val hasDocument: Boolean = false,
        val namespaces: List<SettingsNamespaceView> = emptyList(),
        val loadError: String? = null,
    ) {
        /** Whether the screen may offer edits at all. */
        val editable: Boolean get() = status == PlaneStatus.Ready && writable

        /** One served namespace view by id, or null when the plane does not serve it. */
        fun namespace(ns: String): SettingsNamespaceView? = namespaces.firstOrNull { it.ns == ns }
    }

    /**
     * Outcome of one configuration-plane write. On failure [code] is the wire error code
     * (`settings-conflict`, `agent-preset-not-found`, …) and [message] the harness's explanation.
     */
    data class WriteOutcome(
        val ok: Boolean,
        val code: String? = null,
        val message: String? = null,
        /** The namespace's new redacted view, when the write answered with one. */
        val view: SettingsNamespaceView? = null,
    )

    /** A `llm.discoverModels` answer: the found models, or the harness's failure explanation. */
    data class Discovery(
        val models: List<DiscoveredModelView>,
        val failure: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadMutex = Mutex()

    private val _ui = MutableStateFlow(PlaneUi())
    val ui: StateFlow<PlaneUi> = _ui.asStateFlow()

    init {
        scope.launch { observeConnection() }
        scope.launch { observeInvalidationEvents() }
    }

    // ------------------------------------------------------------------ mirror load / invalidation

    /**
     * Load the mirror if it holds no answer. A no-op when a load is already in flight or the
     * plane already answered (Ready, Refused, or a plain failure): re-reading a refused plane
     * would just re-trip the fence on every screen open.
     */
    suspend fun ensureLoaded() {
        when (_ui.value.status) {
            is PlaneStatus.Loading, is PlaneStatus.Ready, is PlaneStatus.Refused -> return
            is PlaneStatus.Idle, is PlaneStatus.Unavailable -> load()
        }
    }

    /** Re-read the mirror from the harness (retry button, manual refresh). */
    suspend fun refresh() {
        load(force = true)
    }

    /**
     * A configuration-plane change arrived from the harness; re-read if we hold an answer. The
     * call is fire-and-forget — the event handler must not block on the network.
     */
    fun invalidate() {
        when (_ui.value.status) {
            is PlaneStatus.Ready, is PlaneStatus.Unavailable -> scope.launch { load(force = true) }
            is PlaneStatus.Idle, is PlaneStatus.Loading, is PlaneStatus.Refused -> Unit
        }
    }

    private suspend fun load(force: Boolean = false) {
        val api = connectionManager.connectedApi ?: return
        loadMutex.withLock {
            if (!force && _ui.value.status is PlaneStatus.Ready) return@withLock
            _ui.value = _ui.value.copy(status = PlaneStatus.Loading, loadError = null)
            when (val r = api.settingsDescribe()) {
                is RpcResult.Ok -> _ui.value = PlaneUi(
                    status = PlaneStatus.Ready,
                    writable = r.value.writable,
                    hasDocument = r.value.hasDocument,
                    namespaces = r.value.namespaces,
                )
                is RpcResult.Err -> when (r.error.code) {
                    "forbidden" -> _ui.value = PlaneUi(
                        status = PlaneStatus.Refused,
                        refusal = Refusal.TrustFence,
                    )
                    "capability-unavailable" -> _ui.value = PlaneUi(
                        status = PlaneStatus.Refused,
                        refusal = Refusal.Unsupported,
                    )
                    else -> _ui.value = _ui.value.copy(
                        status = PlaneStatus.Unavailable,
                        loadError = r.error.message,
                    )
                }
            }
        }
    }

    private suspend fun observeConnection() {
        var prev = connectionManager.state.value
        connectionManager.state.collect { state ->
            val dropped = prev.hasConnected && state.phase == ConnectionPhase.DISCONNECTED
            prev = state
            // A dropped link owns no settings the harness will still honor: back to Idle so the
            // next screen open re-reads against whatever the (new) generation serves.
            if (dropped) _ui.value = PlaneUi()
        }
    }

    private suspend fun observeInvalidationEvents() {
        connectionManager.hostFrames.collect { frame ->
            val host = parseHostFrame(frame.payload) ?: return@collect
            if (host is HostFrame.RemoteEvent && host.event in INVALIDATING_EVENTS) {
                invalidate()
            }
        }
    }

    // ------------------------------------------------------------------ settings writes

    /**
     * Applies path-addressed ops to one namespace (CAS on [expectedRevision] when given).
     *
     * A `settings-conflict` means the section moved under us: the mirror re-reads and the ops
     * retry exactly once against the revision the harness answered with. The ops are explicit
     * path sets/unsets, so a retry replays cleanly.
     */
    suspend fun settingsMutate(
        ns: String,
        ops: List<SettingsPathOp>,
        expectedRevision: Long? = null,
    ): WriteOutcome {
        val api = connectionManager.connectedApi ?: return disconnected()
        return when (val r = api.settingsMutate(SettingsMutateRequest(ns, ops, expectedRevision))) {
            is RpcResult.Ok -> accepted(r.value)
            is RpcResult.Err -> if (r.error.code == "settings-conflict") {
                val revision = revisionAfterConflict(ns, r.error)
                if (revision != null) {
                    when (val r2 = api.settingsMutate(SettingsMutateRequest(ns, ops, revision))) {
                        is RpcResult.Ok -> accepted(r2.value)
                        is RpcResult.Err -> failed(r2.error)
                    }
                } else failed(r.error)
            } else failed(r.error)
        }
    }

    /** Re-read the mirror after a CAS miss and answer with the freshest known revision for [ns]. */
    private suspend fun revisionAfterConflict(ns: String, error: RpcError): Long? {
        load(force = true)
        return _ui.value.namespace(ns)?.revision
            ?: error.details.jsonObject["actual"]?.jsonPrimitive?.longOrNull
    }

    /** Opens the user settings document on the harness computer; null when the call failed. */
    suspend fun openSettingsDocument(): Boolean? = connectionManager.connectedApi?.let { api ->
        when (val r = api.settingsOpenDocument()) {
            is RpcResult.Ok -> r.value.opened
            is RpcResult.Err -> null
        }
    }

    // ------------------------------------------------------------------ credentials

    /** Describes credential slots by reference name; null when the call failed. */
    suspend fun credentials(refs: List<String>): CredentialsDescribeValue? =
        connectionManager.connectedApi?.let { api ->
            when (val r = api.credentialsDescribe(CredentialsDescribeRequest(refs))) {
                is RpcResult.Ok -> r.value
                is RpcResult.Err -> null
            }
        }

    /** Stores one credential value (write-only: the value never crosses the wire back). */
    suspend fun credentialSet(ref: String, value: String): WriteOutcome {
        val api = connectionManager.connectedApi ?: return disconnected()
        return when (val r = api.credentialsSet(CredentialsSetRequest(ref, value))) {
            is RpcResult.Ok -> WriteOutcome(true)
            is RpcResult.Err -> failed(r.error)
        }
    }

    /** Clears one credential slot. */
    suspend fun credentialUnset(ref: String): WriteOutcome {
        val api = connectionManager.connectedApi ?: return disconnected()
        return when (val r = api.credentialsUnset(CredentialsUnsetRequest(ref))) {
            is RpcResult.Ok -> WriteOutcome(true)
            is RpcResult.Err -> failed(r.error)
        }
    }

    // ------------------------------------------------------------------ llm directory + discovery

    /** The configurable-provider directory (non-privileged: served even to a refused plane). */
    suspend fun llmProviders(): LlmProvidersValue? = connectionManager.connectedApi?.let { api ->
        (api.llmProviders() as? RpcResult.Ok)?.value
    }

    /** The model catalog per provider group (non-privileged). */
    suspend fun llmModels(): LlmModelsValue? = connectionManager.connectedApi?.let { api ->
        (api.llmModels() as? RpcResult.Ok)?.value
    }

    /** Interrogates a draft provider endpoint for its model listing. */
    suspend fun discoverModels(request: LlmDiscoverModelsRequest): Discovery {
        val api = connectionManager.connectedApi ?: return Discovery(emptyList(), "not-connected")
        return when (val r = api.llmDiscoverModels(request)) {
            is RpcResult.Ok -> Discovery(r.value.models)
            is RpcResult.Err -> Discovery(emptyList(), r.error.message)
        }
    }

    // ------------------------------------------------------------------ agent presets (authoring)

    /** Reads one preset's source document; null when the call failed. */
    suspend fun presetRead(id: String): AgentPresetReadValue? =
        connectionManager.connectedApi?.let { api ->
            (api.agentPresetRead(AgentPresetReadRequest(id)) as? RpcResult.Ok)?.value
        }

    /** Copies one preset into a new user preset. */
    suspend fun presetCopy(from: String, id: String, name: String?): WriteOutcome {
        val api = connectionManager.connectedApi ?: return disconnected()
        return when (val r = api.agentPresetCopy(AgentPresetCopyRequest(from, id, name))) {
            is RpcResult.Ok -> WriteOutcome(true)
            is RpcResult.Err -> failed(r.error)
        }
    }

    /** Opens a preset's document on the harness computer; null when the call failed. */
    suspend fun presetOpenDocument(id: String): AgentPresetOpenDocumentValue? =
        connectionManager.connectedApi?.let { api ->
            (api.agentPresetOpenDocument(AgentPresetOpenDocumentRequest(id)) as? RpcResult.Ok)?.value
        }

    /** Removes a user preset. */
    suspend fun presetRemove(id: String): WriteOutcome {
        val api = connectionManager.connectedApi ?: return disconnected()
        return when (val r = api.agentPresetRemove(AgentPresetRemoveRequest(id))) {
            is RpcResult.Ok -> WriteOutcome(true)
            is RpcResult.Err -> failed(r.error)
        }
    }

    // ------------------------------------------------------------------ plugin inventory

    /** The host's composed-plugin inventory (non-privileged); null when the call failed. */
    suspend fun pluginInventory(): PluginInventorySnapshot? =
        connectionManager.connectedApi?.let { api ->
            (api.pluginInventoryList() as? RpcResult.Ok)?.value
        }

    // ------------------------------------------------------------------ shared write plumbing

    private fun disconnected(): WriteOutcome =
        WriteOutcome(ok = false, code = "not-connected", message = null)

    private fun failed(error: RpcError): WriteOutcome =
        WriteOutcome(ok = false, code = error.code, message = error.message)

    /** Folds a write's new namespace view back into the mirror. */
    private fun accepted(view: SettingsNamespaceView): WriteOutcome {
        val current = _ui.value
        val namespaces = if (current.namespaces.any { it.ns == view.ns }) {
            current.namespaces.map { if (it.ns == view.ns) view else it }
        } else {
            current.namespaces + view
        }
        _ui.value = current.copy(namespaces = namespaces)
        return WriteOutcome(ok = true, view = view)
    }

    companion object {
        /** Host events that move the configuration plane under a loaded mirror. */
        private val INVALIDATING_EVENTS = setOf(
            "settings/document-updated",
            "credentials/reference-updated",
            "llm/adapters-updated",
        )
    }
}
