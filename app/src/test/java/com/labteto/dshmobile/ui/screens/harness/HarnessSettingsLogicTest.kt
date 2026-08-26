package com.labteto.dshmobile.ui.screens.harness

import com.labteto.dshmobile.core.wire.dto.SettingsPathOp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write recipe and its validators, ported from the web's models store: which ops carry a
 * draft, under which reference a key stores, and which row is wrong.
 */
class HarnessSettingsLogicTest {

    // ------------------------------------------------------------------ path ops

    @Test
    fun `pathOps sets changed keys and unsets dropped ones`() {
        val before = buildJsonObject {
            put("baseURL", JsonPrimitive("https://old"))
            put("models", buildJsonArray { add(JsonObject(emptyMap())) })
            put("thinking", JsonPrimitive("enabled"))
        }
        val after = buildJsonObject {
            put("baseURL", JsonPrimitive("https://new"))
            put("thinking", JsonPrimitive("enabled"))
        }
        val ops = pathOps(listOf("providers", "my-route"), before, after)

        assertEquals(2, ops.size)
        val set = ops.filterIsInstance<SettingsPathOp.Set>()
        val unset = ops.filterIsInstance<SettingsPathOp.Unset>()
        assertEquals(1, set.size)
        assertEquals(listOf("providers", "my-route", "baseURL"), set.single().path)
        assertEquals(JsonPrimitive("https://new"), set.single().value)
        assertEquals(1, unset.size)
        assertEquals(listOf("providers", "my-route", "models"), unset.single().path)
    }

    @Test
    fun `pathOps is empty when the draft matches the stored profile`() {
        val before = buildJsonObject { put("baseURL", JsonPrimitive("https://x")) }
        val after = buildJsonObject { put("baseURL", JsonPrimitive("https://x")) }
        assertTrue(pathOps(emptyList(), before, after).isEmpty())
    }

    @Test
    fun `pathOps against a missing before sets every draft key`() {
        val after = buildJsonObject {
            put("api", JsonPrimitive("openai-completions"))
            put("baseURL", JsonPrimitive("https://x"))
        }
        val ops = pathOps(listOf("providers", "new"), null, after)
        assertEquals(2, ops.size)
        assertTrue(ops.all { it is SettingsPathOp.Set })
    }

    // ------------------------------------------------------------------ credential references

    @Test
    fun `deriveKeyRef upper-cases the route and collapses runs of other characters`() {
        assertEquals("MINIMAX_CN_API_KEY", deriveKeyRef("minimax-cn"))
        assertEquals("DEEPSEEK_OFFICIAL_API_KEY", deriveKeyRef("deepseek-official"))
        assertEquals("MY_API_KEY", deriveKeyRef("my"))
        assertEquals("A_B_C_API_KEY", deriveKeyRef("a--b__c"))
    }

    @Test
    fun `keyRefFor prefers the profile's named reference`() {
        val profile = buildJsonObject { put("apiKeyEnv", JsonPrimitive("MY_CUSTOM_KEY")) }
        assertEquals("MY_CUSTOM_KEY", keyRefFor("minimax-cn", profile))
    }

    @Test
    fun `keyRefFor falls back to the derived reference when the profile names none`() {
        assertEquals("MINIMAX_CN_API_KEY", keyRefFor("minimax-cn", null))
        assertEquals("MINIMAX_CN_API_KEY", keyRefFor("minimax-cn", JsonObject(emptyMap())))
        // A present-but-blank name is treated as absent, like the web's refFor.
        val blank = buildJsonObject { put("apiKeyEnv", JsonPrimitive("")) }
        assertEquals("MINIMAX_CN_API_KEY", keyRefFor("minimax-cn", blank))
    }

    // ------------------------------------------------------------------ API key judgement

    @Test
    fun `a blank key is not a failure`() {
        assertNull(apiKeyFailure(""))
    }

    @Test
    fun `a whitespace-only key is a blank failure`() {
        assertEquals(KeyFailure.Blank, apiKeyFailure("   "))
    }

    @Test
    fun `a quoted key is rejected`() {
        assertEquals(KeyFailure.IllegalCharacters, apiKeyFailure("\"sk-abc\""))
        assertEquals(KeyFailure.IllegalCharacters, apiKeyFailure("'sk-abc'"))
        assertEquals(KeyFailure.IllegalCharacters, apiKeyFailure("`sk-abc`"))
    }

    @Test
    fun `an environment-line paste is rejected`() {
        assertEquals(KeyFailure.IllegalCharacters, apiKeyFailure("DEEPSEEK_API_KEY=sk-abc"))
    }

    @Test
    fun `a normal key passes`() {
        assertNull(apiKeyFailure("sk-abc123"))
        assertNull(apiKeyFailure("sk-abc.def-123"))
    }

    @Test
    fun `a key with an illegal character is rejected`() {
        assertEquals(KeyFailure.IllegalCharacters, apiKeyFailure("sk abc"))
    }

    // ------------------------------------------------------------------ id patterns

    @Test
    fun `route ids follow the web pattern`() {
        assertTrue(ROUTE_ID_PATTERN.matches("my-route"))
        assertTrue(ROUTE_ID_PATTERN.matches("a"))
        assertTrue(ROUTE_ID_PATTERN.matches("ab1-cd2"))
        assertFalse(ROUTE_ID_PATTERN.matches("My-route"))
        assertFalse(ROUTE_ID_PATTERN.matches("-my"))
        assertFalse(ROUTE_ID_PATTERN.matches("my--route"))
        assertFalse(ROUTE_ID_PATTERN.matches(""))
    }

    @Test
    fun `preset ids follow the web pattern`() {
        assertTrue(PRESET_ID_PATTERN.matches("my-preset"))
        assertTrue(PRESET_ID_PATTERN.matches("1preset"))
        assertFalse(PRESET_ID_PATTERN.matches("my_preset"))
        assertFalse(PRESET_ID_PATTERN.matches("-preset"))
    }

    // ------------------------------------------------------------------ model rows

    @Test
    fun `rows validate clean when every field is optional`() {
        assertNull(validateModelRows(listOf(ModelRowDraft(id = "m1"))))
    }

    @Test
    fun `a missing id fails on the id field`() {
        assertEquals(ModelFailure(0, ModelField.Id), validateModelRows(listOf(ModelRowDraft())))
    }

    @Test
    fun `duplicate ids are compared trimmed`() {
        val failure = validateModelRows(listOf(ModelRowDraft(id = "m1"), ModelRowDraft(id = "m1 ")))
        assertEquals(ModelFailure(1, ModelField.Id, duplicate = true), failure)
    }

    @Test
    fun `a blank-only name fails on the name field`() {
        assertEquals(ModelFailure(0, ModelField.Name), validateModelRows(listOf(ModelRowDraft(id = "m1", name = "   "))))
    }

    @Test
    fun `capacities must be positive integers when present`() {
        assertEquals(
            ModelFailure(0, ModelField.ContextWindow),
            validateModelRows(listOf(ModelRowDraft(id = "m1", contextWindow = "0"))),
        )
        assertEquals(
            ModelFailure(0, ModelField.ContextWindow),
            validateModelRows(listOf(ModelRowDraft(id = "m1", contextWindow = "1.5"))),
        )
        assertEquals(
            ModelFailure(0, ModelField.MaxTokens),
            validateModelRows(listOf(ModelRowDraft(id = "m1", maxTokens = "-3"))),
        )
        assertNull(validateModelRows(listOf(ModelRowDraft(id = "m1", contextWindow = "128000", maxTokens = "4096"))))
    }

    @Test
    fun `modelRowToJson writes only the fields that hold values`() {
        val row = modelRowToJson(ModelRowDraft(id = " m1 ", name = "n", contextWindow = "128000", maxTokens = ""))
        assertEquals(JsonPrimitive("m1"), row["id"])
        assertEquals(JsonPrimitive("n"), row["name"])
        assertEquals(JsonPrimitive(128000L), row["contextWindow"])
        assertNull(row["maxTokens"])
    }

    // ------------------------------------------------------------------ field text

    @Test
    fun `intFieldText reads numbers and ignores strings`() {
        assertEquals("128000", intFieldText(JsonPrimitive(128000L)))
        assertEquals("", intFieldText(JsonPrimitive("not a number")))
        assertEquals("", intFieldText(null))
    }

    @Test
    fun `stringFieldText reads strings only`() {
        assertEquals("https://x", stringFieldText(JsonPrimitive("https://x")))
        assertEquals("", stringFieldText(JsonPrimitive(42L)))
    }

    @Test
    fun `modelRowsOf reads a stored array leniently`() {
        val value: JsonObject = buildJsonObject {
            put(
                "models",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive("m1"))
                            put("name", JsonPrimitive("One"))
                            put("contextWindow", JsonPrimitive(128000L))
                        },
                    )
                    add(JsonPrimitive("not-an-object"))
                },
            )
        }
        val rows = modelRowsOf(value["models"])
        assertEquals(1, rows.size)
        assertEquals(ModelRowDraft(id = "m1", name = "One", contextWindow = "128000", maxTokens = ""), rows.single())
    }

    // ------------------------------------------------------------------ layer ownership

    @Test
    fun `userLayerOwns is true only when the user layer carries the path and the base does not`() {
        val user = buildJsonObject {
            put("providers", buildJsonObject { put("r1", buildJsonObject { put("api", JsonPrimitive("x")) }) })
        }
        val base = buildJsonObject {
            put("providers", buildJsonObject { put("r2", buildJsonObject { put("api", JsonPrimitive("y")) }) })
        }
        assertTrue(userLayerOwns(user, base, listOf("providers", "r1")))
        assertFalse(userLayerOwns(user, base, listOf("providers", "r2")))
        // The whole-section case: an empty path means the section itself.
        assertTrue(userLayerOwns(user, null, emptyList()))
        assertFalse(userLayerOwns(null, base, emptyList()))
    }

    // ------------------------------------------------------------------ managed-field application

    @Test
    fun `applyManagedFields drops blanks and writes the rest`() {
        val draft = LinkedHashMap<String, JsonElement?>()
        draft["thinking"] = JsonPrimitive("enabled")
        draft["baseURL"] = JsonPrimitive("stale")
        draft["models"] = JsonArray(emptyList())
        applyManagedFields(
            draft = draft,
            layout = HarnessSettingsViewModel.Layout.DeepSeek,
            showDisplayName = false,
            api = "",
            displayName = "",
            baseURL = " https://new ",
            modelRows = listOf(ModelRowDraft(id = "m1")),
        )
        assertEquals(JsonPrimitive("enabled"), draft["thinking"])
        assertEquals(JsonPrimitive("https://new"), draft["baseURL"])
        val models = draft["models"] as JsonArray
        assertEquals(1, models.size)
    }

    @Test
    fun `applyManagedFields clears the key when the field is blank`() {
        val draft = LinkedHashMap<String, JsonElement?>()
        draft["baseURL"] = JsonPrimitive("https://old")
        draft["models"] = JsonArray(emptyList())
        applyManagedFields(
            draft = draft,
            layout = HarnessSettingsViewModel.Layout.DeepSeek,
            showDisplayName = false,
            api = "",
            displayName = "",
            baseURL = "",
            modelRows = emptyList(),
        )
        assertNull(draft["baseURL"])
        assertNull(draft["models"])
    }
}
