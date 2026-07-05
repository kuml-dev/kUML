package dev.kuml.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Regression test for a strict-client incompatibility: MCP clients (e.g. Claude Code) validate
 * responses against a JSON-RPC 2.0 union schema that rejects a `result`/`error` pair appearing
 * together — even when the unused member is serialized as `null` rather than omitted. Encoding
 * `error: null` alongside `result` (or vice versa) fails that union validation and the client
 * reports the connection itself as broken, even though every response was otherwise correct.
 */
class McpServerProtocolTest :
    FunSpec({
        test("a result response never serializes an error key") {
            val response = JsonRpcResponse(id = JsonPrimitive(1), result = buildJsonObject {})
            McpServer.json.encodeToString(response) shouldNotContain "\"error\""
        }

        test("an error response never serializes a result key") {
            val response = JsonRpcResponse(id = JsonPrimitive(1), error = JsonRpcError(code = -32601, message = "not found"))
            McpServer.json.encodeToString(response) shouldNotContain "\"result\""
        }

        test("initialize response round-trips to exactly jsonrpc, id, result") {
            val response =
                McpServer.handleLine("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
                    ?: error("Expected a response for initialize")
            val raw = McpServer.json.encodeToString(response)
            raw shouldNotContain "\"error\""
        }
    })
