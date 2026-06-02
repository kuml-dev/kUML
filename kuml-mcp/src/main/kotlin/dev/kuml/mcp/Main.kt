package dev.kuml.mcp

/**
 * Entry point for the kUML MCP server.
 *
 * Usage (add to MCP client config):
 * ```json
 * {
 *   "mcpServers": {
 *     "kuml": {
 *       "command": "/path/to/kuml-mcp/bin/kuml-mcp"
 *     }
 *   }
 * }
 * ```
 */
public fun main() {
    // Redirect stderr to a log file so it doesn't interfere with MCP protocol on stdout/stdin
    // (MCP clients may log stderr, which is fine)
    McpServer.run()
}
