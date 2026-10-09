package dev.kuml.mcp.tools

/** The stateless, script-in/text-out authoring tools of the kUML MCP server. */
public object KumlAuthoringTools {
    /** `kuml.describe`, `kuml.examples`, `kuml.generate`, `kuml.list_elements` — no session state. */
    public val all: List<McpTool> = listOf(DescribeTool, ExamplesTool, GenerateTool, ListElementsTool)
}
