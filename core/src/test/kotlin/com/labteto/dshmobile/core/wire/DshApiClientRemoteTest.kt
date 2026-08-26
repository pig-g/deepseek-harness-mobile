package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.PluginFiberPhase
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typert Remote gateway shares the ordinary client-request envelope: `{"args": …}` is the
 * *payload*, not the body, and the envelope's `method` must equal the path. Posting a bare body —
 * which is what this client used to do — is rejected before it reaches a handler, so these tests
 * pin the shape rather than merely the happy path.
 */
class DshApiClientRemoteTest {

    private class RecordingTransport(
        private val responder: (path: String, body: String) -> RpcHttpResponse,
    ) : RpcTransport {
        var lastPath: String? = null
        var lastBody: String? = null
        var lastDownloadPath: String? = null
        var downloadBytes: ByteArray = ByteArray(0)

        override suspend fun post(path: String, body: String): RpcHttpResponse {
            lastPath = path
            lastBody = body
            return responder(path, body)
        }

        override suspend fun <T> download(
            path: String,
            consume: (String?, String?, InputStream) -> T,
        ): T {
            lastDownloadPath = path
            return consume("application/zip", "attachment; filename=\"x.zip\"", ByteArrayInputStream(downloadBytes))
        }
    }

    private fun client(transport: RpcTransport) = DshApiClient(
        transport = transport,
        wsFactory = { _, _ -> error("not used") },
    )

    private fun ok(rpcId: String, value: String) = RpcHttpResponse(
        status = 200,
        body = """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":$value}}""",
    )

    @Test
    fun `commands list posts a client-request envelope with agentId in args`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(rpcId, """[{"name":"permission","description":"Switch","input":{"hint":"<preset>"}}]""")
        }
        val result = client(transport).commandsList("session-1")

        assertEquals("/api/commands/list", transport.lastPath)
        val envelope = Json.parseToJsonElement(transport.lastBody!!).jsonObject
        assertEquals("client-request", envelope["type"]!!.jsonPrimitive.content)
        assertEquals("commands/list", envelope["method"]!!.jsonPrimitive.content)
        assertTrue(envelope["rpcId"]!!.jsonPrimitive.content.isNotBlank())

        // The gateway matches arg names against the descriptor exactly: the session-addressed
        // parameter is `agentId`, and an unexpected key fails the whole call.
        val args = envelope["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals(setOf("agentId"), args.keys)
        assertEquals("session-1", args["agentId"]!!.jsonPrimitive.content)

        val commands = (result as RpcResult.Ok).value
        assertEquals(1, commands.size)
        assertEquals("permission", commands.first().name)
        assertEquals("<preset>", commands.first().input?.hint)
    }

    @Test
    fun `commands execute sends agentId and line`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(rpcId, "{}")
        }
        client(transport).commandsExecute("session-2", "/permission workspace-write")

        val args = Json.parseToJsonElement(transport.lastBody!!)
            .jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals(setOf("agentId", "line"), args.keys)
        assertEquals("session-2", args["agentId"]!!.jsonPrimitive.content)
        assertEquals("/permission workspace-write", args["line"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a 404 becomes capability-unavailable rather than a connection failure`() = runTest {
        val transport = RecordingTransport { _, _ ->
            throw RpcTransportException(404, "carrier returned HTTP 404")
        }
        val result = client(transport).commandsList("session-3")

        // A harness that composes no command registry answers 404. That is a missing optional
        // capability, not a broken link, and callers rely on the distinction to degrade the menu
        // instead of raising a failure banner over a healthy session.
        assertEquals("capability-unavailable", (result as RpcResult.Err).error.code)
    }

    @Test
    fun `a 403 becomes forbidden`() = runTest {
        val transport = RecordingTransport { _, _ ->
            throw RpcTransportException(403, "harness trust fence rejected the request (HTTP 403)")
        }
        val result = client(transport).commandsList("session-4")
        assertEquals("forbidden", (result as RpcResult.Err).error.code)
    }

    @Test
    fun `malformed command rows drop out instead of emptying the catalog`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(rpcId, """[{"name":"plan"},{"unexpected":true},{"name":"compact","future":42}]""")
        }
        val result = client(transport).commandsList("session-5")

        val commands = (result as RpcResult.Ok).value
        assertEquals(listOf("plan", "compact"), commands.map { it.name })
    }

    @Test
    fun `plugin inventory list posts an empty args object and decodes entries`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(
                rpcId,
                """{"entries":[
                    {"entryId":"deepseek-harness/web","moduleName":"@deepseek-ai/dsh-web","enabled":true,"fiberPhase":"active"},
                    {"entryId":"deepseek-harness/off","moduleName":"@deepseek-ai/dsh-off","enabled":false,"fiberPhase":null}
                ]}""",
            )
        }
        val result = client(transport).pluginInventoryList()

        assertEquals("/api/pluginInventory/list", transport.lastPath)
        val args = Json.parseToJsonElement(transport.lastBody!!)
            .jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals(emptySet<String>(), args.keys)

        val snapshot = (result as RpcResult.Ok).value
        assertEquals(2, snapshot.entries.size)
        assertEquals(PluginFiberPhase.Active, snapshot.entries[0].fiberPhase)
        assertEquals("deepseek-harness/off", snapshot.entries[1].entryId)
        assertNull(snapshot.entries[1].fiberPhase)
    }

    @Test
    fun `a row with an unknown fiber phase is kept and mapped to Unknown`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(
                rpcId,
                """{"entries":[
                    {"entryId":"a","moduleName":"m-a","enabled":true,"fiberPhase":"loading"},
                    {"entryId":"b","moduleName":"m-b","enabled":true,"fiberPhase":"brand-new-phase"}
                ]}""",
            )
        }
        val result = client(transport).pluginInventoryList()

        val snapshot = (result as RpcResult.Ok).value
        // A future harness phase must not make the row vanish; it reads as Unknown instead.
        assertEquals(listOf("a", "b"), snapshot.entries.map { it.entryId })
        assertEquals(PluginFiberPhase.Loading, snapshot.entries[0].fiberPhase)
        assertEquals(PluginFiberPhase.Unknown, snapshot.entries[1].fiberPhase)
    }

    @Test
    fun `a structurally malformed plugin inventory row drops out`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(
                rpcId,
                """{"entries":[
                    {"entryId":"a","moduleName":"m-a","enabled":true,"fiberPhase":"active"},
                    {"entryId":123,"moduleName":"m-b","enabled":true}
                ]}""",
            )
        }
        val result = client(transport).pluginInventoryList()

        val snapshot = (result as RpcResult.Ok).value
        assertEquals(listOf("a"), snapshot.entries.map { it.entryId })
    }

    @Test
    fun `session export streams from a GET with the session id in the query`() = runTest {
        val transport = RecordingTransport { _, _ -> error("export must not POST") }
        transport.downloadBytes = "PK-zip-bytes".toByteArray()

        val result = client(transport).sessionExport("session-6", includeDescendants = true) { _, _, body ->
            body.readBytes().decodeToString()
        }

        assertEquals(
            "/api/session.export?sessionId=session-6&includeDescendants=true",
            transport.lastDownloadPath,
        )
        assertEquals("PK-zip-bytes", (result as RpcResult.Ok).value)
        assertNull(transport.lastPath)
    }
}
