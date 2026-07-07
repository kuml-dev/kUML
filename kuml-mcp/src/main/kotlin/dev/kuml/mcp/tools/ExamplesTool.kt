package dev.kuml.mcp.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.mcp.examples.CuratedExample
import dev.kuml.mcp.examples.ExampleCatalog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `kuml.examples` — fetches curated kUML DSL example scripts for a specific diagram type.
 *
 * Motivation (V3.3.1): examples are the strongest anti-hallucination lever for LLM clients
 * generating kUML DSL, but the pre-existing `kuml://dsl/examples` resource is all-or-nothing
 * (every bundled example concatenated) and resources are poorly supported by many MCP
 * clients compared to tools. This tool lets an LLM request exactly the examples it needs
 * for one `(language, diagramType)` pair, at a fraction of the token cost.
 *
 * Two call shapes:
 *  - `{language}` only → discovery: lists the diagram types available for that language
 *    (no scripts — cheap, lets the caller pick a type before paying for script content).
 *  - `{language, diagramType}` → retrieval: returns the matching example script(s), each
 *    with a one-sentence description and the source note title.
 */
internal object ExamplesTool : McpTool {
    private val json = Json { prettyPrint = false }

    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.examples",
            description =
                "Fetch curated kUML DSL example scripts for a specific diagram type — the strongest " +
                    "anti-hallucination lever. Call with `language` only to list available diagram " +
                    "types; add `diagramType` to get the matching `.kuml.kts` example script(s) plus " +
                    "a one-line description and the source note title.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("language") {
                            put("type", "string")
                            putJsonArray("enum") {
                                ExampleCatalog.languages().forEach { add(JsonPrimitive(it)) }
                            }
                            put("description", "kUML modelling language. Required.")
                        }
                        putJsonObject("diagramType") {
                            put("type", "string")
                            put(
                                "description",
                                "Optional per-language diagram-type token (kebab-case, e.g. 'class', " +
                                    "'sequence', 'state-machine', 'composite-structure', 'bdd', " +
                                    "'container', 'service-blueprint', 'journey'). Omit to list the " +
                                    "diagram types available for the given language. Call with " +
                                    "'language' only first to discover valid values.",
                            )
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("language")) }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val language =
            arguments["language"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: throw McpToolException(missingLanguageError())

        if (language !in ExampleCatalog.languages()) {
            throw McpToolException(unknownLanguageError(language))
        }

        val diagramType = arguments["diagramType"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val resultJson =
            if (diagramType == null) {
                discoveryResult(language)
            } else {
                val matches = ExampleCatalog.find(language = language, diagramType = diagramType)
                if (matches.isEmpty()) {
                    throw McpToolException(unknownDiagramTypeError(language, diagramType))
                }
                retrievalResult(language, diagramType, matches)
            }

        return listOf(McpContent(type = "text", text = json.encodeToString(resultJson)))
    }

    private fun discoveryResult(language: String): JsonObject =
        buildJsonObject {
            put("language", language)
            putJsonArray("diagramTypes") {
                ExampleCatalog.diagramTypes(language).forEach { type ->
                    val examples = ExampleCatalog.find(language = language, diagramType = type)
                    add(
                        buildJsonObject {
                            put("diagramType", type)
                            putJsonArray("examples") {
                                examples.forEach { example ->
                                    add(
                                        buildJsonObject {
                                            put("sourceNote", example.sourceNote)
                                            put("description", example.description)
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

    private fun retrievalResult(
        language: String,
        diagramType: String,
        matches: List<CuratedExample>,
    ): JsonObject =
        buildJsonObject {
            put("language", language)
            put("diagramType", diagramType)
            putJsonArray("examples") {
                matches.forEach { example ->
                    add(
                        buildJsonObject {
                            put("sourceNote", example.sourceNote)
                            put("description", example.description)
                            put("script", ExampleCatalog.loadScript(example))
                        },
                    )
                }
            }
        }

    private fun missingLanguageError(): String =
        json.encodeToString(
            buildJsonObject {
                putJsonObject("error") {
                    put("code", "KUML-MCP-E-EXAMPLES-MISSING-LANGUAGE")
                    put("message", "Missing required argument: language")
                    putJsonArray("validLanguages") {
                        ExampleCatalog.languages().forEach { add(JsonPrimitive(it)) }
                    }
                }
            },
        )

    private fun unknownLanguageError(language: String): String =
        json.encodeToString(
            buildJsonObject {
                putJsonObject("error") {
                    put("code", "KUML-MCP-E-EXAMPLES-UNKNOWN-LANGUAGE")
                    put("message", "Unknown language '$language'.")
                    putJsonArray("validLanguages") {
                        ExampleCatalog.languages().forEach { add(JsonPrimitive(it)) }
                    }
                }
            },
        )

    private fun unknownDiagramTypeError(
        language: String,
        diagramType: String,
    ): String =
        json.encodeToString(
            buildJsonObject {
                putJsonObject("error") {
                    put("code", "KUML-MCP-E-EXAMPLES-UNKNOWN-DIAGRAM-TYPE")
                    put("message", "Unknown diagramType '$diagramType' for language '$language'.")
                    put("language", language)
                    putJsonArray("validDiagramTypes") {
                        ExampleCatalog.diagramTypes(language).forEach { add(JsonPrimitive(it)) }
                    }
                }
            },
        )
}
