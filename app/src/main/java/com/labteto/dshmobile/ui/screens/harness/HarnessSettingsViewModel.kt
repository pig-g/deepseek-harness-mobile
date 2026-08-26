package com.labteto.dshmobile.ui.screens.harness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetOpenDocumentValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetReadValue
import com.labteto.dshmobile.core.wire.dto.ConfigurableProviderView
import com.labteto.dshmobile.core.wire.dto.CredentialView
import com.labteto.dshmobile.core.wire.dto.LlmDiscoverModelsRequest
import com.labteto.dshmobile.core.wire.dto.LlmModelsValue
import com.labteto.dshmobile.core.wire.dto.ModelCatalogModel
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.core.wire.dto.SettingsPathOp
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.data.SettingsPlane
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One screen, one view model: the harness settings tabs share the [SettingsPlane] mirror, so a
 * write on the General tab is seen by Models on the next composition without any hand-off.
 *
 * The VM stays thin — plane reads/writes go straight to [SettingsPlane]; the pure derivation
 * (drafts, ops, validation) lives in [HarnessSettingsLogic] and is unit-tested there.
 */
@HiltViewModel
class HarnessSettingsViewModel @Inject constructor(
    private val plane: SettingsPlane,
    private val store: SessionStore,
) : ViewModel() {

    /** The four sections, mirroring the web GUI's settings tabs. */
    enum class Tab { General, Models, Plugins, Presets }

    private val _tab = MutableStateFlow(Tab.General)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    /** The configuration-plane mirror (status, writability, served namespaces). */
    val planeUi: StateFlow<SettingsPlane.PlaneUi> = plane.ui

    /** The agent-preset roster (host-scoped, owned by the session store). */
    val presets: StateFlow<AgentPresetListValue?> = store.agentPresets

    /** The open session's permission choice, when one is open (drives the General permission row). */
    val permissions = store.permissions

    // ------------------------------------------------------------------ plane lifecycle

    /** Seed the mirror once when the screen opens. */
    fun loadPlane() {
        viewModelScope.launch { plane.ensureLoaded() }
    }

    /** Re-read the mirror (retry button after a failure). */
    fun refreshPlane() {
        viewModelScope.launch { plane.refresh() }
    }

    /** Select a section, kicking off its lazy reads. */
    fun selectTab(next: Tab) {
        if (_tab.value == next) return
        _tab.value = next
        when (next) {
            Tab.Models -> viewModelScope.launch { loadModels() }
            Tab.Plugins -> viewModelScope.launch { loadInventory() }
            Tab.Presets -> viewModelScope.launch { store.refreshAgentPresets() }
            Tab.General -> Unit
        }
    }

    // ------------------------------------------------------------------ general tab

    private fun revisionOf(ns: String): Long? = plane.ui.value.namespace(ns)?.revision

    /** Writes the default agent preset (`agent-presets.default`), then re-reads the roster's flags. */
    suspend fun setDefaultPreset(id: String): SettingsPlane.WriteOutcome {
        val out = plane.settingsMutate(
            ns = "agent-presets",
            ops = listOf(SettingsPathOp.Set(listOf("default"), JsonPrimitive(id))),
            expectedRevision = revisionOf("agent-presets"),
        )
        if (out.ok) store.refreshAgentPresets()
        return out
    }

    /** Writes the default permission preset (`permission.defaultPreset`). */
    suspend fun setPermissionPreset(value: String): SettingsPlane.WriteOutcome = plane.settingsMutate(
        ns = "permission",
        ops = listOf(SettingsPathOp.Set(listOf("defaultPreset"), JsonPrimitive(value))),
        expectedRevision = revisionOf("permission"),
    )

    /**
     * Writes the web-GUI locale preference (`locale.preference`); [preference] of null unsets the
     * field, which delegates to the browser's own language.
     */
    suspend fun setLocale(preference: String?): SettingsPlane.WriteOutcome {
        val op = if (preference == null) {
            SettingsPathOp.Unset(listOf("preference"))
        } else {
            SettingsPathOp.Set(listOf("preference"), JsonPrimitive(preference))
        }
        return plane.settingsMutate("locale", listOf(op), revisionOf("locale"))
    }

    /** Writes the web-GUI appearance preference (`ui-theme.preference`). */
    suspend fun setTheme(preference: String): SettingsPlane.WriteOutcome = plane.settingsMutate(
        ns = "ui-theme",
        ops = listOf(SettingsPathOp.Set(listOf("preference"), JsonPrimitive(preference))),
        expectedRevision = revisionOf("ui-theme"),
    )

    /** Opens the user settings document on the host; null when the call failed. */
    suspend fun openSettingsDocument(): Boolean? = plane.openSettingsDocument()

    // ------------------------------------------------------------------ models tab

    /** One provider row the Models section renders: the directory entry joined with the mirror. */
    data class ProviderRowUi(
        val entry: ConfigurableProviderView,
        /** The resolved effective profile; null when no layer configures the provider. */
        val profile: JsonObject?,
        val configured: Boolean,
        /** Whether the user layer alone carries the profile (removal restores the base). */
        val removable: Boolean,
        /** The credential reference the profile resolves keys through. */
        val keyRef: String,
        val credential: CredentialView?,
    ) {
        val custom: Boolean get() = entry.declared != true
        val keyConfigured: Boolean get() = credential?.configured == true
    }

    /** The Models section snapshot. */
    data class ModelsUi(
        val rows: List<ProviderRowUi>,
        /**
         * The non-privileged model catalog, joined in so a refused plane can still show what
         * the host's providers actually serve (the profiles themselves stay private then).
         */
        val catalog: LlmModelsValue?,
        /** Whole-load failure (the directory call failed); rows stay usable when one is present. */
        val error: String?,
        /** The credential enrichment failed; the rows remain usable. */
        val credentialError: Boolean,
    ) {
        fun catalogModels(provider: String): List<ModelCatalogModel> =
            catalog?.groups?.firstOrNull { it.id == provider }?.models.orEmpty()
    }

    private val _models = MutableStateFlow<ModelsUi?>(null)
    val models: StateFlow<ModelsUi?> = _models.asStateFlow()

    /** Re-reads the provider directory and joins it with the settings mirror and credentials. */
    suspend fun loadModels() {
        val directory = plane.llmProviders()
        if (directory == null) {
            _models.value = _models.value?.copy(error = "llm.providers unavailable") ?: ModelsUi(
                rows = emptyList(),
                catalog = plane.llmModels(),
                error = "llm.providers unavailable",
                credentialError = false,
            )
            return
        }
        val mirror = plane.ui.value
        val rows = directory.providers.map { entry ->
            val nsView = mirror.namespace(entry.settingsNs)
            // A path of `[]` means the whole section is the profile (the deepseek layout).
            val profile = if (entry.settingsPath.isEmpty()) {
                nsView?.value?.let { it as? JsonObject }
            } else {
                jsonObjectAt(nsView?.value, entry.settingsPath)
            }
            val keyRef = keyRefFor(entry.provider, profile)
            ProviderRowUi(
                entry = entry,
                profile = profile,
                configured = profile != null,
                removable = nsView != null && userLayerOwns(nsView.user, nsView.base, entry.settingsPath),
                keyRef = keyRef,
                credential = null,
            )
        }
        val credentialMap = plane.credentials(rows.map { it.keyRef }.distinct())?.credentials
        // The catalog is a separate, non-privileged read: it still answers when the plane is
        // refused, which is exactly when the read-only Models view leans on it.
        val catalog = plane.llmModels()
        _models.value = ModelsUi(
            rows = rows.map { row -> row.copy(credential = credentialMap?.get(row.keyRef)) },
            catalog = catalog,
            error = null,
            credentialError = credentialMap == null,
        )
    }

    /**
     * The editor layout a settings namespace owns: the two shipped layouts get dedicated fields;
     * anything else degrades to a read-only section view.
     */
    fun layoutOf(settingsNs: String): Layout = when (settingsNs) {
        "llm-deepseek" -> Layout.DeepSeek
        "llm-pi-ai" -> Layout.PiAi
        else -> Layout.Unknown
    }

    enum class Layout { DeepSeek, PiAi, Unknown }

    /**
     * Saves one provider editor's draft, mirroring the web's `applyOnce`: the draft carries every
     * stored key (so fields outside the card pass through), the ops name only the changed ones,
     * and a typed key stores through `credentials.set` under the profile's reference.
     *
     * @param before the stored profile at open (null when the editor created a new profile).
     * @param draft the whole draft as the editor sees it.
     * @param key the typed key, untrimmed; blank means "keep the stored key".
     */
    suspend fun saveProvider(
        layout: Layout,
        settingsNs: String,
        settingsPath: List<String>,
        provider: String,
        before: JsonObject?,
        draft: LinkedHashMap<String, JsonElement?>,
        key: String,
    ): SettingsPlane.WriteOutcome {
        // A pi-ai profile names the conventional reference only when this editor is about to
        // store a key: otherwise the route keeps its provider-native auth path.
        val keyRef = keyRefFor(provider, before)
        val next = LinkedHashMap(draft)
        if (layout == Layout.PiAi && next["apiKeyEnv"] == null && before?.get("apiKeyEnv") == null && key.isNotBlank()) {
            next["apiKeyEnv"] = JsonPrimitive(keyRef)
        }
        val kept = LinkedHashMap<String, JsonElement>()
        for ((k, v) in next) if (v != null) kept[k] = v
        val after = JsonObject(kept)
        val ops = pathOps(settingsPath, before, after)
        if (ops.isNotEmpty()) {
            val out = plane.settingsMutate(settingsNs, ops, revisionOf(settingsNs))
            if (!out.ok) return out
        }
        val keyValue = key.trim()
        if (keyValue.isNotEmpty()) {
            val stored = plane.credentialSet(keyRef, keyValue)
            if (!stored.ok) return stored
        }
        viewModelScope.launch { loadModels() }
        return SettingsPlane.WriteOutcome(ok = true)
    }

    /**
     * Removes a provider: unsets its profile path, and clears the conventional credential when
     * the profile resolves keys through it (a profile naming its own reference keeps that one).
     */
    suspend fun removeProvider(row: ProviderRowUi): SettingsPlane.WriteOutcome {
        val out = plane.settingsMutate(
            ns = row.entry.settingsNs,
            ops = listOf(SettingsPathOp.Unset(row.entry.settingsPath)),
            expectedRevision = revisionOf(row.entry.settingsNs),
        )
        if (!out.ok) return out
        val namedEnv = (row.profile?.get("apiKeyEnv") as? JsonPrimitive)?.content
        val managed = namedEnv.isNullOrEmpty() || namedEnv == deriveKeyRef(row.entry.provider)
        if (managed && row.keyConfigured) {
            val cleared = plane.credentialUnset(row.keyRef)
            if (!cleared.ok) return cleared
        }
        viewModelScope.launch { loadModels() }
        return out
    }

    /**
     * Adds a custom pi-ai route: one `set` op materializing the profile from the typed fields,
     * then the key through the credentials face.
     */
    suspend fun addCustomProvider(
        settingsNs: String,
        route: String,
        displayName: String,
        api: String,
        baseURL: String,
        rows: List<ModelRowDraft>,
        key: String,
    ): SettingsPlane.WriteOutcome {
        val keyRef = deriveKeyRef(route)
        val keyValue = key.trim()
        val profile = buildJsonObject {
            if (displayName.isNotBlank()) put("displayName", displayName.trim())
            if (keyValue.isNotEmpty()) put("apiKeyEnv", keyRef)
            put("api", api)
            put("baseURL", baseURL.trim())
            put("models", JsonArray(rows.map { modelRowToJson(it) }))
        }
        val out = plane.settingsMutate(
            ns = settingsNs,
            ops = listOf(SettingsPathOp.Set(listOf("providers", route), profile)),
            expectedRevision = revisionOf(settingsNs),
        )
        if (!out.ok) return out
        if (keyValue.isNotEmpty()) {
            val stored = plane.credentialSet(keyRef, keyValue)
            if (!stored.ok) return stored
        }
        viewModelScope.launch { loadModels() }
        return out
    }

    /** Interrogates a draft provider endpoint for its model listing. */
    suspend fun discover(request: LlmDiscoverModelsRequest): SettingsPlane.Discovery =
        plane.discoverModels(request)

    // ------------------------------------------------------------------ plugins tab

    private val _inventory = MutableStateFlow<PluginInventorySnapshot?>(null)
    val inventory: StateFlow<PluginInventorySnapshot?> = _inventory.asStateFlow()

    /** Re-reads the host's composed-plugin inventory. */
    suspend fun loadInventory() {
        _inventory.value = plane.pluginInventory()
    }

    /**
     * Writes one positive-integer settings field of a plugin card; blank clears the override
     * (the base value applies).
     */
    suspend fun setIntField(ns: String, key: String, text: String): SettingsPlane.WriteOutcome {
        val op = if (text.isBlank()) {
            SettingsPathOp.Unset(listOf(key))
        } else {
            SettingsPathOp.Set(listOf(key), JsonPrimitive(text.trim().toPositiveLongOrNull() ?: 0L))
        }
        return plane.settingsMutate(ns, listOf(op), revisionOf(ns))
    }

    /** Writes one string settings field of a plugin card; blank clears the override. */
    suspend fun setStringField(ns: String, key: String, text: String): SettingsPlane.WriteOutcome {
        val op = if (text.isBlank()) {
            SettingsPathOp.Unset(listOf(key))
        } else {
            SettingsPathOp.Set(listOf(key), JsonPrimitive(text.trim()))
        }
        return plane.settingsMutate(ns, listOf(op), revisionOf(ns))
    }

    /**
     * Stores a plugin card's API key through the credentials face. [fallbackKeyRef] is the
     * plugin's own default reference when the stored section names none.
     */
    suspend fun setSectionKey(ns: String, fallbackKeyRef: String, key: String): SettingsPlane.WriteOutcome {
        val named = (jsonObjectAt(plane.ui.value.namespace(ns)?.value, emptyList())?.get("apiKeyEnv") as? JsonPrimitive)
            ?.content?.takeIf { it.isNotEmpty() }
        return plane.credentialSet(named ?: fallbackKeyRef, key.trim())
    }

    // ------------------------------------------------------------------ presets tab

    /** Refreshes the roster (the authoring ops change it). */
    suspend fun refreshPresets() {
        store.refreshAgentPresets()
    }

    /** Reads one preset's source document for the viewer sheet; null on failure. */
    suspend fun presetRead(id: String): AgentPresetReadValue? = plane.presetRead(id)

    /** Copies one preset into a new user preset. */
    suspend fun presetCopy(from: String, id: String, name: String?): SettingsPlane.WriteOutcome {
        val out = plane.presetCopy(from, id, name)
        if (out.ok) store.refreshAgentPresets()
        return out
    }

    /** Opens a preset's document on the host; null on failure. */
    suspend fun presetOpenDocument(id: String): AgentPresetOpenDocumentValue? =
        plane.presetOpenDocument(id)

    /** Removes a user preset. */
    suspend fun presetRemove(id: String): SettingsPlane.WriteOutcome {
        val out = plane.presetRemove(id)
        if (out.ok) store.refreshAgentPresets()
        return out
    }
}
