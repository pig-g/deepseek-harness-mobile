package com.labteto.dshmobile.ui.screens.harness

import com.labteto.dshmobile.core.wire.dto.SettingsPathOp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure derivation and validation for the harness settings screens, ported field-for-field from
 * the web client (`ui-settings-models/src/client/store.ts`, `ProviderEditor.tsx`, `apiKey.ts`,
 * `DeepSeekModelsEditor.tsx`, `ui-agent-preset/src/client/section-store.ts`).
 *
 * These functions touch no UI and no network: they are the ported "write recipe" — which ops
 * carry a draft, under which credential reference a key stores, and which row is wrong — and
 * the unit tests pin them.
 */

// ------------------------------------------------------------------ JSON paths

/** The object at [path] inside [root], or null when any key is missing or not an object. */
fun jsonObjectAt(root: JsonElement?, path: List<String>): JsonObject? {
    if (root == null || path.isEmpty()) return null
    var node: JsonElement = root
    for (key in path) {
        val obj = node as? JsonObject ?: return null
        node = obj[key] ?: return null
    }
    return node as? JsonObject
}

/** The value at [path] inside [root], or null when any key is missing. */
fun jsonAt(root: JsonElement?, path: List<String>): JsonElement? {
    var node: JsonElement? = root
    for (key in path) {
        val obj = node as? JsonObject ?: return null
        node = obj[key]
    }
    return node
}

/**
 * The minimal path ops carrying [after] over [before], both as the editor sees them (a web
 * port of `pathOps`). Only keys the editor observed are named: a field absent from both sides
 * produces no op, which is why edits are path-addressed rather than a rebuilt section.
 */
fun pathOps(base: List<String>, before: JsonObject?, after: JsonObject): List<SettingsPathOp> {
    val previous = before ?: JsonObject(emptyMap())
    val ops = ArrayList<SettingsPathOp>()
    for ((key, value) in after) {
        if (previous[key] == value) continue
        ops.add(SettingsPathOp.Set(base + key, value))
    }
    for (key in previous.keys) {
        if (key !in after) ops.add(SettingsPathOp.Unset(base + key))
    }
    return ops
}

/** The stored profile at [path], as an editor draft: every key kept so untouched fields pass through. */
fun draftOf(profile: JsonObject?): LinkedHashMap<String, JsonElement> =
    (jsonObjectAt(profile, emptyList()) ?: profile)
        ?.let { LinkedHashMap(it) }
        ?: LinkedHashMap()

// ------------------------------------------------------------------ credential references

/**
 * The conventional credential reference for a provider route: the route upper-cased, runs of
 * anything but `[A-Z0-9]` collapsed to `_`, suffixed `_API_KEY` (`minimax-cn` → `MINIMAX_CN_API_KEY`).
 */
fun deriveKeyRef(provider: String): String =
    provider.uppercase().replace(Regex("[^A-Z0-9]+"), "_") + "_API_KEY"

/**
 * The credential reference a profile resolves keys through: its named `apiKeyEnv` when present,
 * otherwise the derived reference.
 */
fun keyRefFor(provider: String, profile: JsonObject?): String {
    val named = (profile?.get("apiKeyEnv") as? JsonPrimitive)?.content
    return named?.takeIf { it.isNotEmpty() } ?: deriveKeyRef(provider)
}

// ------------------------------------------------------------------ API key judgement

/** Why a typed API key cannot be stored, mirroring the web's `apiKeyFailure`. */
enum class KeyFailure { Blank, IllegalCharacters }

/** Printable ASCII, space excluded — the twin of the host's `normalizeApiKey` charset rule. */
private val LEGAL_API_KEY = Regex("^[\\x21-\\x7E]+$")

/**
 * A pasted `NAME=value` environment line. Two narrowings keep real keys clear of it: the name
 * must be upper-case, and the `=` must be followed by something other than another `=`. The
 * value is the rest of the string (at least one character, not starting with `=`).
 */
private val ENV_LINE = Regex("^[A-Z][A-Z0-9_]*=[^=].*")

/** Whether a value is wrapped in one matching pair of `"`, `'`, or backtick quotes. */
private fun isQuoted(value: String): Boolean {
    val first = value.firstOrNull() ?: return false
    if (first != '"' && first != '\'' && first != '`') return false
    return value.length > 1 && value.endsWith(first)
}

/**
 * Judge the key input's current value, untrimmed. An empty field is not a failure — every
 * editor opens with it empty, where it means "keep the stored key". A field holding only
 * whitespace is a failure, so typed input is never silently discarded.
 */
fun apiKeyFailure(draft: String): KeyFailure? {
    if (draft.isEmpty()) return null
    val value = draft.trim()
    if (value.isEmpty()) return KeyFailure.Blank
    if (ENV_LINE.matches(value) || isQuoted(value)) return KeyFailure.IllegalCharacters
    if (!LEGAL_API_KEY.matches(value)) return KeyFailure.IllegalCharacters
    return null
}

// ------------------------------------------------------------------ id patterns

/** A custom provider route id: `^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$`. */
val ROUTE_ID_PATTERN = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")

/** An agent-preset id: `^[a-z0-9][a-z0-9-]*$`. */
val PRESET_ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")

/** The pi-ai wire protocols a hand-declared route may name. */
val PIAI_PROTOCOLS = listOf("openai-completions", "openai-responses", "anthropic-messages")

// ------------------------------------------------------------------ model rows

/** One model row as the editor holds it: raw field text, parsed on save. */
data class ModelRowDraft(
    val id: String = "",
    val name: String = "",
    val contextWindow: String = "",
    val maxTokens: String = "",
)

/** The field a model-row failure names, so the UI can point at the right column. */
enum class ModelField { Id, Name, ContextWindow, MaxTokens }

/** A validation failure naming the row (0-based) and the field at fault. */
data class ModelFailure(val index: Int, val field: ModelField, val duplicate: Boolean = false)

/**
 * Validate the row list, mirroring the web's `validateDeepSeekModels`: ids are required and
 * unique (compared trimmed — surrounding whitespace is a paste artifact), a present name must
 * not be blank, and present capacities must be positive integers. Absent optional fields are
 * fine: the host applies its defaults.
 */
fun validateModelRows(rows: List<ModelRowDraft>): ModelFailure? {
    val seen = HashSet<String>()
    rows.forEachIndexed { index, row ->
        val trimmedId = row.id.trim()
        if (trimmedId.isEmpty()) return ModelFailure(index, ModelField.Id)
        if (!seen.add(trimmedId)) return ModelFailure(index, ModelField.Id, duplicate = true)
        val name = row.name.trim()
        if (row.name.isNotEmpty() && name.isEmpty()) return ModelFailure(index, ModelField.Name)
        if (row.contextWindow.trim().isNotEmpty() && row.contextWindow.trim().toPositiveLongOrNull() == null) {
            return ModelFailure(index, ModelField.ContextWindow)
        }
        if (row.maxTokens.trim().isNotEmpty() && row.maxTokens.trim().toPositiveLongOrNull() == null) {
            return ModelFailure(index, ModelField.MaxTokens)
        }
    }
    return null
}

/** One model row as written: `id` always, the rest only when the field holds a value. */
fun modelRowToJson(row: ModelRowDraft): JsonObject = buildJsonObject {
    put("id", row.id.trim())
    val name = row.name.trim()
    if (name.isNotEmpty()) put("name", name)
    row.contextWindow.trim().toPositiveLongOrNull()?.let { put("contextWindow", it) }
    row.maxTokens.trim().toPositiveLongOrNull()?.let { put("maxTokens", it) }
}

/** A positive whole number from field text, or null. */
fun String.toPositiveLongOrNull(): Long? =
    toLongOrNull()?.takeIf { it > 0 }

// ------------------------------------------------------------------ editor numerics

/** The display text for an optional integer settings field (numbers only; strings are read-only). */
fun intFieldText(value: JsonElement?): String {
    val primitive = value as? JsonPrimitive ?: return ""
    if (primitive.isString || primitive is JsonNull) return ""
    return primitive.content
}

/** Read one optional string settings field (a non-string primitive is not a string value). */
fun stringFieldText(value: JsonElement?): String {
    val primitive = value as? JsonPrimitive ?: return ""
    if (!primitive.isString) return ""
    return primitive.content
}

/** The stored models array as editor rows (lenient: a row that cannot be read contributes id only). */
fun modelRowsOf(value: JsonElement?): List<ModelRowDraft> {
    val rows = value as? JsonArray ?: return emptyList()
    return rows.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        ModelRowDraft(
            id = stringFieldText(obj["id"]),
            name = stringFieldText(obj["name"]),
            contextWindow = intFieldText(obj["contextWindow"]),
            maxTokens = intFieldText(obj["maxTokens"]),
        )
    }
}

/** Whether the user layer alone carries the value at [path] (removal restores the base). */
fun userLayerOwns(user: JsonElement?, base: JsonElement?, path: List<String>): Boolean {
    val at = jsonAt(user, path) ?: return false
    if (at is JsonNull) return false
    return jsonAt(base, path) == null
}
