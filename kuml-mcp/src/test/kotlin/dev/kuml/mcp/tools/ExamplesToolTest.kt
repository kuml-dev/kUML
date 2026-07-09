package dev.kuml.mcp.tools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * V3.3.1 — tests for the `kuml.examples` MCP tool.
 */
class ExamplesToolTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } }

        test("descriptor is kuml.examples with language enum of six and required language") {
            val descriptor = ExamplesTool.descriptor
            descriptor.name shouldBe "kuml.examples"
            val properties = descriptor.inputSchema["properties"]!!.jsonObject
            val languageEnum = properties["language"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
            // Order matches ExampleCatalog.languages() catalog-insertion order: uml, c4, sysml2, bpmn, blueprint, erm.
            languageEnum shouldBe listOf("uml", "c4", "sysml2", "bpmn", "blueprint", "erm")
            val required = descriptor.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            required shouldBe listOf("language")
        }

        test("language only returns diagram-type discovery listing without scripts") {
            val result = ExamplesTool.call(args("language" to "uml"))
            result shouldHaveSize 1
            val parsed = json.parseToJsonElement(result[0].text!!).jsonObject
            parsed["language"]!!.jsonPrimitive.content shouldBe "uml"
            val diagramTypes = parsed["diagramTypes"]!!.jsonArray
            diagramTypes.isEmpty() shouldBe false
            result[0].text!! shouldNotContain "\"script\""
        }

        test("language plus diagramType returns script and description and sourceNote") {
            val result = ExamplesTool.call(args("language" to "uml", "diagramType" to "class"))
            val parsed = json.parseToJsonElement(result[0].text!!).jsonObject
            val examples = parsed["examples"]!!.jsonArray
            examples shouldHaveSize 1
            val first = examples[0].jsonObject
            first["script"]!!.jsonPrimitive.content shouldContain "classDiagram("
            first["sourceNote"]!!.jsonPrimitive.content shouldBe "01 UML Klasse – Order Domain"
            first["description"]!!.jsonPrimitive.content.shouldNotBeBlank()
        }

        test("multi-example diagram type returns all matching scripts") {
            val result = ExamplesTool.call(args("language" to "uml", "diagramType" to "component"))
            val parsed = json.parseToJsonElement(result[0].text!!).jsonObject
            parsed["examples"]!!.jsonArray shouldHaveSize 2
        }

        test("blueprint journey and service-blueprint are distinct types") {
            val journey = ExamplesTool.call(args("language" to "blueprint", "diagramType" to "journey"))
            val journeyJson = json.parseToJsonElement(journey[0].text!!).jsonObject
            val journeyScript =
                journeyJson["examples"]!!
                    .jsonArray[0]
                    .jsonObject["script"]!!
                    .jsonPrimitive.content
            journeyScript shouldContain "journeyDiagram("

            val blueprint = ExamplesTool.call(args("language" to "blueprint", "diagramType" to "service-blueprint"))
            val blueprintJson = json.parseToJsonElement(blueprint[0].text!!).jsonObject
            val blueprintScript =
                blueprintJson["examples"]!!
                    .jsonArray[0]
                    .jsonObject["script"]!!
                    .jsonPrimitive.content
            blueprintScript shouldContain "blueprintDiagram("
        }

        test("missing language throws McpToolException with structured error") {
            val exception = shouldThrow<McpToolException> { ExamplesTool.call(buildJsonObject { }) }
            val error = json.parseToJsonElement(exception.message!!).jsonObject["error"]!!.jsonObject
            error["code"]!!.jsonPrimitive.content shouldBe "KUML-MCP-E-EXAMPLES-MISSING-LANGUAGE"
        }

        test("unknown language throws structured error listing valid languages") {
            val exception = shouldThrow<McpToolException> { ExamplesTool.call(args("language" to "cobol")) }
            val error = json.parseToJsonElement(exception.message!!).jsonObject["error"]!!.jsonObject
            error["code"]!!.jsonPrimitive.content shouldBe "KUML-MCP-E-EXAMPLES-UNKNOWN-LANGUAGE"
            val validLanguages = error["validLanguages"]!!.jsonArray.map { it.jsonPrimitive.content }
            validLanguages shouldContain "uml"
        }

        test("unknown diagramType throws structured error listing valid diagram types") {
            val exception =
                shouldThrow<McpToolException> {
                    ExamplesTool.call(args("language" to "uml", "diagramType" to "klasse"))
                }
            val error = json.parseToJsonElement(exception.message!!).jsonObject["error"]!!.jsonObject
            error["code"]!!.jsonPrimitive.content shouldBe "KUML-MCP-E-EXAMPLES-UNKNOWN-DIAGRAM-TYPE"
            val validDiagramTypes = error["validDiagramTypes"]!!.jsonArray.map { it.jsonPrimitive.content }
            validDiagramTypes shouldContain "class"
        }

        test("ToolRegistry exposes kuml.examples") {
            ToolRegistry.descriptors.map { it.name } shouldContain "kuml.examples"
        }
    })
