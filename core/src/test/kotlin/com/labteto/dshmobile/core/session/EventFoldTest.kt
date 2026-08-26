package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFoldTest {

    private fun event(type: String, seq: Long, data: kotlinx.serialization.json.JsonObject): SessionEventEnvelope =
        SessionEventEnvelope(type, seq, seq, data)

    @Test
    fun foldsBasicTurn() {
        val events = listOf(
            event("turn/start", 0, buildJsonObject { put("turn", 1) }),
            event("user/message", 1, buildJsonObject {
                put("id", "m1")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "hello") })
                }
                putJsonObject("source") { put("kind", "user") }
            }),
            event("assistant/chunk", 2, buildJsonObject {
                put("turn", 1); put("step", 1)
                putJsonObject("chunk") { put("type", "block-start"); put("index", 0); put("blockType", "text") }
            }),
            event("assistant/chunk", 3, buildJsonObject {
                put("turn", 1); put("step", 1)
                putJsonObject("chunk") { put("type", "text-delta"); put("index", 0); put("text", "hi") }
            }),
            event("assistant/message", 4, buildJsonObject {
                put("turn", 1); put("step", 1)
                putJsonObject("message") { put("id", "a1") }
            }),
            event("turn/end", 5, buildJsonObject {
                put("turn", 1)
                putJsonObject("reason") { put("kind", "completed") }
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        assertEquals(4, snapshot.nodes.size)
        assertFalse(snapshot.blank)
        val user = snapshot.nodes[1] as UserMessageNode
        assertEquals("hello", user.previewText)
        val assistant = snapshot.nodes.first { it is AssistantMessageNode } as AssistantMessageNode
        assertEquals("hi", assistant.blocks.first().text)
        assertEquals("hi", assistant.plainText)
        assertFalse(snapshot.running)
        assertEquals(5L, snapshot.lastSeq)
    }

    @Test
    fun interruptedTurnMarksAssistant() {
        val events = listOf(
            event("turn/start", 0, buildJsonObject { put("turn", 2) }),
            event("assistant/message", 1, buildJsonObject {
                put("turn", 2); put("step", 1)
                putJsonObject("message") {
                    put("id", "a2")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "partial") })
                    }
                }
            }),
            event("turn/end", 2, buildJsonObject {
                put("turn", 2)
                putJsonObject("reason") { put("kind", "aborted") }
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        val assistant = snapshot.nodes.first { it is AssistantMessageNode } as AssistantMessageNode
        assertTrue(assistant.interrupted)
    }

    @Test
    fun unknownEventBecomesOtherNode() {
        val events = listOf(
            event("mystery/event", 0, buildJsonObject { put("x", 1) }),
        )
        val snapshot = EventFold("s1").fold(events)
        assertTrue(snapshot.nodes.single() is OtherNode)
    }

    @Test
    fun incrementalSkipsDuplicates() {
        val fold = EventFold.Incremental(ConversationSnapshot("s1", lastSeq = 2), "s1")
        val first = fold.apply(event("turn/start", 3, buildJsonObject { put("turn", 7) }))
        val duplicate = fold.apply(event("turn/start", 3, buildJsonObject { put("turn", 7) }))
        assertTrue(first!!.nodes.any { it is TurnStartNode })
        assertEquals(null, duplicate)
    }

    @Test
    fun detectsGap() {
        val events = listOf(
            event("turn/start", 0, buildJsonObject { put("turn", 1) }),
            event("turn/end", 5, buildJsonObject {
                put("turn", 1)
                putJsonObject("reason") { put("kind", "completed") }
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        assertTrue(snapshot.gap)
    }

    @Test
    fun parsesReasoningAndToolBlocks() {
        val message = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "reasoning"); put("text", "thinking…") })
                add(buildJsonObject { put("type", "tool-call"); put("id", "c1"); put("name", "bash"); put("arguments", "{\"command\":\"ls\"}") })
                add(buildJsonObject { put("type", "tool-result"); put("toolCallId", "c1"); put("isError", false) })
            }
        }
        val events = listOf(
            event("assistant/message", 0, buildJsonObject {
                put("turn", 1); put("step", 1)
                put("message", message)
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        val assistant = snapshot.nodes.single() as AssistantMessageNode
        assertEquals(3, assistant.blocks.size)
        assertEquals("reasoning", assistant.blocks[0].kind)
        assertEquals("c1", assistant.blocks[1].toolCallId)
        assertEquals("bash", assistant.blocks[1].toolName)
    }

    /**
     * A plugin-sourced `user/message` (the runtime-context snapshot) folds to a
     * [ContextMessageNode] with the durable sections, not a [UserMessageNode] bubble.
     */
    @Test
    fun foldsPluginUserMessageIntoContextNode() {
        val events = listOf(
            event("user/message", 1, buildJsonObject {
                put("id", "ctx-1")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "Current runtime context. This snapshot supersedes earlier runtime-context snapshots.")
                    })
                }
                putJsonObject("source") {
                    put("kind", "plugin")
                    put("plugin", "@deepseek-ai/dsh-system-prompt")
                    put("form", "snapshot")
                    putJsonArray("sections") {
                        add(buildJsonObject {
                            put("name", "sandbox:policy")
                            put("text", "Current DSH file policy: workspace-write.")
                        })
                        add(buildJsonObject {
                            put("name", "approval:policy")
                            put("text", "Approval policy: ask.")
                        })
                    }
                }
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        val node = snapshot.nodes.single() as ContextMessageNode
        assertEquals("@deepseek-ai/dsh-system-prompt", node.plugin)
        assertEquals("snapshot", node.form)
        assertEquals(2, node.sections.size)
        assertEquals("sandbox:policy", node.sections[0].name)
        assertEquals("Current DSH file policy: workspace-write.", node.sections[0].text)
        assertEquals("Approval policy: ask.", node.sections[1].text)
        assertEquals(
            "Current runtime context. This snapshot supersedes earlier runtime-context snapshots.",
            node.text,
        )
        assertFalse(snapshot.blank)
    }

    /**
     * A plugin-sourced message without a usable section list still folds to a [ContextMessageNode]
     * (with the model-facing text for the opaque fallback), never to a user bubble.
     */
    @Test
    fun foldsPluginUserMessageWithoutSections() {
        val events = listOf(
            event("user/message", 1, buildJsonObject {
                put("id", "ctx-2")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "You are a delegated subagent.") })
                }
                putJsonObject("source") { put("kind", "plugin"); put("plugin", "@deepseek-ai/dsh-system-prompt") }
            }),
        )
        val node = EventFold("s1").fold(events).nodes.single() as ContextMessageNode
        assertTrue(node.sections.isEmpty())
        assertEquals("You are a delegated subagent.", node.text)
    }

    /**
     * An `agent-instructions`-sourced `user/message` (the `<system-reminder>` AGENTS.md frame)
     * folds to a [ContextMessageNode] with the model-facing text, not a [UserMessageNode] bubble:
     * the web client's rule is `source.kind != 'user'`, not `kind == 'plugin'`.
     */
    @Test
    fun foldsInstructionFrameUserMessageIntoContextNode() {
        val events = listOf(
            event("user/message", 1, buildJsonObject {
                put("id", "instr-1")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put(
                            "text",
                            "<system-reminder>\nAdditional instructions from: AGENTS.md\n\nRules.\n</system-reminder>",
                        )
                    })
                }
                putJsonObject("source") {
                    put("kind", "agent-instructions")
                    put("form", "instructions")
                }
            }),
        )
        val node = EventFold("s1").fold(events).nodes.single() as ContextMessageNode
        assertNull(node.plugin)
        assertEquals("instructions", node.form)
        assertTrue(node.sections.isEmpty())
        assertTrue(node.text!!.startsWith("<system-reminder>"))
        assertEquals("<system-reminder>", node.text!!.lineSequence().firstOrNull())
    }

    /**
     * A `skill-catalog`-sourced `user/message` folds to a [ContextMessageNode] the same way.
     */
    @Test
    fun foldsCatalogUserMessageIntoContextNode() {
        val events = listOf(
            event("user/message", 1, buildJsonObject {
                put("id", "cat-1")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "<system-reminder>\n<available_skills>\n- `a`: A\n</available_skills>\n</system-reminder>")
                    })
                }
                putJsonObject("source") {
                    put("kind", "skill-catalog")
                    put("form", "catalog")
                }
            }),
        )
        val node = EventFold("s1").fold(events).nodes.single() as ContextMessageNode
        assertEquals("catalog", node.form)
        assertTrue(node.sections.isEmpty())
    }

    /**
     * A `user/message` with no `source` field at all stays a [UserMessageNode]: a prompt typed by
     * a person must never render as a context row, which is the fold's leniency contract.
     */
    @Test
    fun foldsUserMessageWithoutSourceIntoUserNode() {
        val events = listOf(
            event("user/message", 1, buildJsonObject {
                put("id", "m1")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "hi") })
                }
            }),
        )
        val node = EventFold("s1").fold(events).nodes.single() as UserMessageNode
        assertEquals(null, node.sourceKind)
        assertEquals("hi", node.previewText)
    }

    /**
     * A build that sends `content` as a bare string instead of a block array used to fold to no
     * blocks at all — the user's own message would disappear from the transcript rather than render
     * imperfectly, which is the opposite of the fold's leniency contract everywhere else.
     */
    @Test
    fun foldsStringUserContentIntoATextBlock() {
        val events = listOf(
            event("user/message", 1, buildJsonObject {
                put("id", "m1")
                put("content", "just a string")
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        val user = snapshot.nodes.single() as UserMessageNode
        assertEquals(1, user.blocks.size)
        assertEquals("text", user.blocks[0].kind)
        assertEquals("just a string", user.blocks[0].text)
        assertEquals("just a string", user.previewText)
    }

    @Test
    fun ignoresBlankStringUserContent() {
        val events = listOf(
            event("user/message", 1, buildJsonObject {
                put("id", "m1")
                put("content", "   ")
            }),
        )
        val user = EventFold("s1").fold(events).nodes.single() as UserMessageNode
        assertTrue(user.blocks.isEmpty())
    }

    @Test
    fun roundTripsThroughWireJson() {
        // The wire JSON parser (lenient) must accept the event envelope.
        val raw = """{"type":"turn/end","seq":4,"time":5,"data":{"turn":1,"reason":{"kind":"completed"}},"extra":"ignored"}"""
        val parsed = WireJson.parseToJsonElement(raw)
        assertTrue(parsed.toString().contains("turn/end"))
        assertTrue(parsed.toString().contains("completed"))
    }
}
