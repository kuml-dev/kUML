package dev.kuml.mcp.resources

import dev.kuml.mcp.McpResourceContents
import dev.kuml.mcp.McpResourceDescriptor
import dev.kuml.mcp.tools.ToolRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Registry of read-only MCP resources exposing kUML DSL knowledge — the
 * "Lehrkanal" (teaching channel) complementing the `kuml.render` feedback loop.
 *
 * Pattern mirrors [ToolRegistry]: a fixed list of descriptors plus a
 * `read(uri)` dispatch function. All three resources are static (built once,
 * lazily, from classpath resources) — no live filesystem/vault access, so
 * they work identically in the packaged binary and in tests.
 */
internal object ResourceRegistry {
    private const val URI_REFERENCE = "kuml://dsl/reference"
    private const val URI_EXAMPLES = "kuml://dsl/examples"
    private const val URI_SCHEMA = "kuml://dsl/schema"

    /** Handbook pages bundled under `dsl/reference/` by `:kuml-mcp:processResources` (see build.gradle.kts). */
    private val referencePages =
        listOf("uml-dsl.adoc", "sysml2.adoc", "c4-dsl.adoc", "bpmn-dsl.adoc")

    private val json = Json { prettyPrint = true }

    internal val descriptors: List<McpResourceDescriptor> =
        listOf(
            McpResourceDescriptor(
                uri = URI_REFERENCE,
                name = "kUML DSL reference",
                description =
                    "Full DSL reference for all builder functions across UML, SysML v2, C4, and BPMN " +
                        "diagram types — the handbook reference pages, concatenated.",
                mimeType = "text/asciidoc",
            ),
            McpResourceDescriptor(
                uri = URI_EXAMPLES,
                name = "kUML curated vault examples",
                description =
                    "Curated example scripts covering every kUML diagram type, extracted from the " +
                        "vault's example notes. Each example includes the surrounding Markdown context.",
                mimeType = "text/markdown",
            ),
            McpResourceDescriptor(
                uri = URI_SCHEMA,
                name = "kUML DSL machine-readable schema",
                description =
                    "JSON schema of all MCP tool input shapes, for structured autocompletion in MCP clients.",
                mimeType = "application/json",
            ),
        )

    /**
     * Reads the resource identified by [uri].
     * @throws McpResourceException if [uri] is unknown
     */
    internal fun read(uri: String): McpResourceContents =
        when (uri) {
            URI_REFERENCE -> McpResourceContents(uri = uri, mimeType = "text/asciidoc", text = buildReferenceText())
            URI_EXAMPLES -> McpResourceContents(uri = uri, mimeType = "text/markdown", text = buildExamplesText())
            URI_SCHEMA -> McpResourceContents(uri = uri, mimeType = "application/json", text = buildSchemaText())
            else -> throw McpResourceException("Unknown resource: '$uri'")
        }

    private fun buildReferenceText(): String =
        referencePages.joinToString(separator = "\n\n") { page ->
            val stream =
                javaClass.getResourceAsStream("/dsl/reference/$page")
                    ?: throw McpResourceException("Missing bundled reference page: $page")
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

    private fun buildExamplesText(): String {
        val exampleNames = listExampleResourceNames()
        if (exampleNames.isEmpty()) {
            throw McpResourceException("No bundled vault examples found under dsl/examples/")
        }
        return exampleNames.sorted().joinToString(separator = "\n\n---\n\n") { name ->
            val stream =
                javaClass.getResourceAsStream("/dsl/examples/$name")
                    ?: throw McpResourceException("Missing bundled example: $name")
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    /**
     * Lists the `*.md` example files bundled under `dsl/examples/` on the classpath.
     * Uses the resource directory listing rather than a hardcoded name list, so newly
     * added vault examples (V3.2.18) are picked up automatically by the next build.
     */
    private fun listExampleResourceNames(): List<String> {
        val dirUrl = javaClass.getResource("/dsl/examples/") ?: return emptyList()
        return when (dirUrl.protocol) {
            "file" -> {
                val dir = java.io.File(dirUrl.toURI())
                dir
                    .listFiles { file -> file.isFile && file.name.endsWith(".md") }
                    ?.map { it.name }
                    ?: emptyList()
            }
            "jar" -> {
                val jarPath = dirUrl.path.substringBefore("!").removePrefix("file:")
                java.util.jar.JarFile(jarPath).use { jar ->
                    jar
                        .entries()
                        .asSequence()
                        .filter { it.name.startsWith("dsl/examples/") && it.name.endsWith(".md") }
                        .map { it.name.removePrefix("dsl/examples/") }
                        .toList()
                }
            }
            else -> emptyList()
        }
    }

    /**
     * MVP schema: derived from [ToolRegistry]'s existing `inputSchema` definitions
     * (already hand-maintained per tool) rather than a separate builder-signature
     * catalogue. Out of scope for this wave: a full reflective DSL-builder schema
     * generator (see wave spec "MVP" note).
     */
    private fun buildSchemaText(): String {
        val schema =
            buildJsonObject {
                put("\$schema", "https://json-schema.org/draft/2020-12/schema")
                put("title", "kUML MCP tool input schemas")
                put(
                    "description",
                    "Machine-readable input schemas for every kUML MCP tool, keyed by tool name. " +
                        "Generated from the same McpToolDescriptor definitions returned by tools/list.",
                )
                put(
                    "tools",
                    buildJsonArray {
                        ToolRegistry.descriptors.forEach { descriptor ->
                            add(
                                buildJsonObject {
                                    put("name", descriptor.name)
                                    put("description", descriptor.description)
                                    put("inputSchema", descriptor.inputSchema)
                                },
                            )
                        }
                    },
                )
            }
        return json.encodeToString(schema)
    }
}

internal class McpResourceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
