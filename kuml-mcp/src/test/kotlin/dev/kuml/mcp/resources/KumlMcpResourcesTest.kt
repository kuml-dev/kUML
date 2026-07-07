package dev.kuml.mcp.resources

import dev.kuml.mcp.McpServer
import dev.kuml.mcp.examples.ExampleCatalog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * V3.2.17 — tests for the MCP resources capability: `kuml://dsl/{reference,examples,schema}`.
 * V3.3.1 extends this with the granular per-`(language, diagramType)` example resources.
 *
 * Exercises both the [ResourceRegistry] directly (unit level, mirrors [dev.kuml.mcp.tools.ToolRegistry]
 * test style) and the JSON-RPC surface via [McpServer.handleLine] (protocol level, mirrors how a
 * real MCP client would call `resources/list` / `resources/read`).
 */
class KumlMcpResourcesTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        // Distinct (language, diagramType) combinations in the catalog — one granular
        // resource descriptor per combination, plus the three aggregate resources.
        val granularCount =
            ExampleCatalog.languages().sumOf { language -> ExampleCatalog.diagramTypes(language).size }

        test("descriptors exposes the three aggregate kuml://dsl resource URIs plus one per catalog entry") {
            ResourceRegistry.descriptors shouldHaveSize (3 + granularCount)
            val uris = ResourceRegistry.descriptors.map { it.uri }
            uris.take(3) shouldBe listOf("kuml://dsl/reference", "kuml://dsl/examples", "kuml://dsl/schema")
            uris shouldContain "kuml://dsl/examples/uml/class"
            uris shouldContain "kuml://dsl/examples/c4/container"
            uris shouldContain "kuml://dsl/examples/blueprint/journey"
        }

        test("read(kuml://dsl/reference) returns non-empty asciidoc content covering all DSL families") {
            val contents = ResourceRegistry.read("kuml://dsl/reference")
            contents.mimeType shouldBe "text/asciidoc"
            contents.text.shouldNotBeBlank()
            contents.text shouldContain "UML"
            contents.text shouldContain "SysML"
            contents.text shouldContain "C4"
            contents.text shouldContain "BPMN"
        }

        test("read(kuml://dsl/examples) returns non-empty markdown content with multiple examples") {
            val contents = ResourceRegistry.read("kuml://dsl/examples")
            contents.mimeType shouldBe "text/markdown"
            contents.text.shouldNotBeBlank()
            // Separator between concatenated examples must appear at least once for >1 bundled example.
            contents.text shouldContain "---"
        }

        test("read(kuml://dsl/schema) returns valid JSON listing every MCP tool's input schema") {
            val contents = ResourceRegistry.read("kuml://dsl/schema")
            contents.mimeType shouldBe "application/json"
            val parsed = json.parseToJsonElement(contents.text).jsonObject
            parsed["tools"]!!.jsonArray.size shouldBe dev.kuml.mcp.tools.ToolRegistry.descriptors.size
        }

        test("granular example resource returns the raw kuml.kts script for its type") {
            val contents = ResourceRegistry.read("kuml://dsl/examples/uml/class")
            contents.mimeType shouldBe "text/x-kotlin"
            contents.text shouldContain "classDiagram("
        }

        test("granular example resource concatenates multiple examples for a type") {
            val contents = ResourceRegistry.read("kuml://dsl/examples/uml/component")
            contents.text shouldContain "12 UML Component – Order Architecture"
            contents.text shouldContain "35 AUTOSAR Classic – SW-Komponenten"
        }

        test("unknown granular example URI throws McpResourceException") {
            shouldThrow<McpResourceException> {
                ResourceRegistry.read("kuml://dsl/examples/uml/does-not-exist")
            }
        }

        test("read(unknown URI) throws McpResourceException") {
            shouldThrow<McpResourceException> {
                ResourceRegistry.read("kuml://dsl/does-not-exist")
            }
        }

        test("JSON-RPC resources/list returns the three aggregate plus one granular descriptor per catalog entry") {
            val response =
                McpServer.handleLine("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")
                    ?: error("Expected a response for resources/list")
            val resources = response.result!!.jsonObject["resources"]!!.jsonArray
            resources shouldHaveSize (3 + granularCount)
            resources.forEach { resource ->
                val obj = resource.jsonObject
                obj["uri"]!!.jsonPrimitive.content.shouldNotBeBlank()
                obj["name"]!!.jsonPrimitive.content.shouldNotBeBlank()
                obj["description"]!!.jsonPrimitive.content.shouldNotBeBlank()
                obj["mimeType"]!!.jsonPrimitive.content.shouldNotBeBlank()
            }
        }

        test("JSON-RPC resources/list includes granular example URIs") {
            val response =
                McpServer.handleLine("""{"jsonrpc":"2.0","id":5,"method":"resources/list"}""")
                    ?: error("Expected a response for resources/list")
            val resources = response.result!!.jsonObject["resources"]!!.jsonArray
            val uris = resources.map { it.jsonObject["uri"]!!.jsonPrimitive.content }
            uris shouldContain "kuml://dsl/examples/sysml2/bdd"
            uris shouldContain "kuml://dsl/examples/bpmn/choreography"
        }

        test("JSON-RPC resources/read for each known URI returns non-empty contents") {
            listOf("kuml://dsl/reference", "kuml://dsl/examples", "kuml://dsl/schema").forEach { uri ->
                val request =
                    """{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"$uri"}}"""
                val response = McpServer.handleLine(request) ?: error("Expected a response for resources/read")
                response.error shouldBe null
                val contentsArray = response.result!!.jsonObject["contents"]!!.jsonArray
                contentsArray shouldHaveSize 1
                val entry = contentsArray[0].jsonObject
                entry["uri"]!!.jsonPrimitive.content shouldBe uri
                entry["text"]!!.jsonPrimitive.content.shouldNotBeBlank()
            }
        }

        test("JSON-RPC resources/read with unknown URI returns a clean JSON-RPC error, not a crash") {
            val request =
                """{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"kuml://dsl/nope"}}"""
            val response = McpServer.handleLine(request) ?: error("Expected a response for resources/read")
            response.result shouldBe null
            response.error shouldNotBe null
        }

        test("initialize advertises the resources capability") {
            val response =
                McpServer.handleLine("""{"jsonrpc":"2.0","id":4,"method":"initialize"}""")
                    ?: error("Expected a response for initialize")
            response.result!!.jsonObject["capabilities"]!!.jsonObject["resources"] shouldNotBe null
        }
    })
