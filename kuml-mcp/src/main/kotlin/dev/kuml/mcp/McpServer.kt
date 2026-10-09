package dev.kuml.mcp

import dev.kuml.mcp.tools.ToolRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * kUML MCP server — JSON-RPC 2.0 over stdio.
 *
 * Protocol version: 2024-11-05
 * Transport: stdio (newline-delimited JSON messages)
 *
 * The protocol logic lives in [McpRequestHandler]; this object only owns the stdio loop.
 */
internal object McpServer {
    internal val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val handler =
        McpRequestHandler(
            registry = ToolRegistry.registry,
            serverInfo = McpServerInfo(name = "kuml-mcp", version = "1.0.0"),
            protocolVersion = McpRequestHandler.DEFAULT_PROTOCOL_VERSION,
            includeResources = true,
        )

    /** Run the server, reading from stdin and writing to stdout until EOF. */
    internal fun run() {
        val reader = System.`in`.bufferedReader(Charsets.UTF_8)
        val writer = System.out.bufferedWriter(Charsets.UTF_8)

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val raw = line ?: continue
            if (raw.isBlank()) continue

            val response = handleLine(raw) ?: continue
            writer.write(json.encodeToString(response))
            writer.newLine()
            writer.flush()
        }
    }

    internal fun handleLine(line: String): JsonRpcResponse? = handler.handleRequest(line)
}
