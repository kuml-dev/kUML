package dev.kuml.mcp.resources

import dev.kuml.mcp.McpResourceContents
import dev.kuml.mcp.McpResourceDescriptor
import dev.kuml.mcp.examples.BundledExamples
import dev.kuml.mcp.examples.ExampleCatalog
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
 * `read(uri)` dispatch function. All resources — the three aggregate ones plus the
 * granular per-`(language, diagramType)` example resources (V3.3.1) — are static
 * (built once, lazily, from classpath resources) — no live filesystem/vault access,
 * so they work identically in the packaged binary and in tests.
 */
internal object ResourceRegistry {
    private const val URI_REFERENCE = "kuml://dsl/reference"
    private const val URI_EXAMPLES = "kuml://dsl/examples"
    private const val URI_SCHEMA = "kuml://dsl/schema"

    /** Prefix for the granular per-(language, diagramType) example resources — V3.3.1. */
    private const val URI_EXAMPLES_PREFIX = "kuml://dsl/examples/"

    /** Handbook pages bundled under `dsl/reference/` by `:kuml-mcp:processResources` (see build.gradle.kts). */
    private val referencePages =
        listOf("uml-dsl.adoc", "sysml2.adoc", "c4-dsl.adoc", "bpmn-dsl.adoc")

    private val json = Json { prettyPrint = true }

    /**
     * One descriptor per distinct `(language, diagramType)` combination in [ExampleCatalog] —
     * V3.3.1. Backward-compatible companion to the aggregate [URI_EXAMPLES] resource: an MCP
     * client that only supports `resources/list` (no tool calls) can still fetch a single
     * diagram type's script(s) without paying for the full concatenated corpus.
     */
    private val granularExampleDescriptors: List<McpResourceDescriptor> =
        ExampleCatalog.languages().flatMap { language ->
            ExampleCatalog.diagramTypes(language).map { diagramType ->
                McpResourceDescriptor(
                    uri = "$URI_EXAMPLES_PREFIX$language/$diagramType",
                    name = "kUML example: $language $diagramType",
                    description =
                        ExampleCatalog
                            .find(language = language, diagramType = diagramType)
                            .joinToString(separator = " | ") { it.description },
                    mimeType = "text/x-kotlin",
                )
            }
        }

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
        ) + granularExampleDescriptors

    /**
     * Reads the resource identified by [uri].
     * @throws McpResourceException if [uri] is unknown
     */
    internal fun read(uri: String): McpResourceContents =
        when {
            uri == URI_REFERENCE ->
                McpResourceContents(uri = uri, mimeType = "text/asciidoc", text = buildReferenceText())
            uri == URI_EXAMPLES ->
                McpResourceContents(uri = uri, mimeType = "text/markdown", text = buildExamplesText())
            uri == URI_SCHEMA ->
                McpResourceContents(uri = uri, mimeType = "application/json", text = buildSchemaText())
            uri.startsWith(URI_EXAMPLES_PREFIX) ->
                McpResourceContents(uri = uri, mimeType = "text/x-kotlin", text = buildGranularExampleText(uri))
            else -> throw McpResourceException("Unknown resource: '$uri'")
        }

    /**
     * Parses `kuml://dsl/examples/<language>/<diagramType>` and returns the concatenated
     * `.kuml.kts` script(s) for that combination, each prefixed with a source-note header.
     * @throws McpResourceException if the combination is unknown
     */
    private fun buildGranularExampleText(uri: String): String {
        val path = uri.removePrefix(URI_EXAMPLES_PREFIX)
        val language = path.substringBefore('/')
        val diagramType = path.substringAfter('/', missingDelimiterValue = "")
        val matches = ExampleCatalog.find(language = language, diagramType = diagramType)
        if (matches.isEmpty()) {
            throw McpResourceException(
                "Unknown resource: '$uri'. No example for language '$language' and diagramType '$diagramType'.",
            )
        }
        return matches.joinToString(separator = "\n\n") { example ->
            "// ── ${example.sourceNote} ──\n${ExampleCatalog.loadScript(example)}"
        }
    }

    private fun buildReferenceText(): String =
        referencePages.joinToString(separator = "\n\n") { page ->
            val stream =
                javaClass.getResourceAsStream("/dsl/reference/$page")
                    ?: throw McpResourceException("Missing bundled reference page: $page")
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

    private fun buildExamplesText(): String {
        val exampleNames = BundledExamples.listNames()
        if (exampleNames.isEmpty()) {
            throw McpResourceException("No bundled vault examples found under dsl/examples/")
        }
        return exampleNames.sorted().joinToString(separator = "\n\n---\n\n") { name ->
            BundledExamples.readRaw(name) ?: throw McpResourceException("Missing bundled example: $name")
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
