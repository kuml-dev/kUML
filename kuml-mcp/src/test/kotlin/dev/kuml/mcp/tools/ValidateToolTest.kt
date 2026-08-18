package dev.kuml.mcp.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Integration tests for `kuml.validate`'s source-style check
 * (`dev.kuml.core.script.style.NamedArgumentStyleCheck`, V0.50.0). Mirrors
 * `ValidateCommandStyleTest` (`:kuml-cli`) — same fixture scripts, same
 * scenarios, exercised through the MCP tool call path instead of the CLI.
 */
class ValidateToolTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        fun args(vararg pairs: Pair<String, Any>): JsonObject =
            buildJsonObject {
                pairs.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> put(key, value)
                        else -> put(key, value.toString())
                    }
                }
            }

        val positionalScript =
            """
            diagram(name = "Positional", type = DiagramType.CLASS) {
                classOf("Widget")
            }
            """.trimIndent()

        val namedScript =
            """
            diagram(name = "Named", type = DiagramType.CLASS) {
                classOf(name = "Widget")
            }
            """.trimIndent()

        val exemptionsScript =
            """
            diagram(name = "Exemptions", type = DiagramType.CLASS) {
                val animal = classOf(name = "Animal")
                classOf(name = "Dog") {
                    extends(animal)
                }
            }
            """.trimIndent()

        val combinedScript =
            """
            diagram(name = "Combined", type = DiagramType.CLASS) {
                classOf(name = "Empty") {
                    constraint("hasAttr", "self.attributes->size() > 0")
                }
            }
            """.trimIndent()

        test("a positional dev.kuml.* argument makes valid=false and is reported in styleViolations") {
            val result = ValidateTool.call(args("script" to positionalScript))
            val response = json.parseToJsonElement(result[0].text!!).jsonObject
            response["valid"]!!.jsonPrimitive.content shouldBe "false"
            response["violations"]!!.jsonArray.shouldBeEmpty()
            val style = response["styleViolations"]!!.jsonArray
            style shouldHaveSize 1
            style[0].jsonObject["id"]!!.jsonPrimitive.content shouldBe "POSITIONAL_ARGUMENT"
            style[0].jsonObject["category"]!!.jsonPrimitive.content shouldBe "style"
        }

        test("a fully named script has no style violations and valid=true") {
            val result = ValidateTool.call(args("script" to namedScript))
            val response = json.parseToJsonElement(result[0].text!!).jsonObject
            response["valid"]!!.jsonPrimitive.content shouldBe "true"
            response["styleViolations"]!!.jsonArray.shouldBeEmpty()
        }

        test("single-value-parameter and block-DSL-lambda calls remain exempt") {
            val result = ValidateTool.call(args("script" to exemptionsScript))
            val response = json.parseToJsonElement(result[0].text!!).jsonObject
            response["valid"]!!.jsonPrimitive.content shouldBe "true"
            response["styleViolations"]!!.jsonArray.shouldBeEmpty()
        }

        test("checkStyle=false skips the check even on a positional-argument script") {
            val result = ValidateTool.call(args("script" to positionalScript, "checkStyle" to false))
            val response = json.parseToJsonElement(result[0].text!!).jsonObject
            response["valid"]!!.jsonPrimitive.content shouldBe "true"
            response["styleViolations"]!!.jsonArray.shouldBeEmpty()
        }

        test("a script with both a genuine OCL violation and a style violation reports both") {
            val result = ValidateTool.call(args("script" to combinedScript))
            val response = json.parseToJsonElement(result[0].text!!).jsonObject
            response["valid"]!!.jsonPrimitive.content shouldBe "false"
            // The OCL violation (constraint "hasAttr" fails on an empty class):
            val modelViolations = response["violations"]!!.jsonArray
            modelViolations shouldHaveSize 1
            modelViolations[0].jsonObject["constraintName"]!!.jsonPrimitive.content shouldBe "hasAttr"
            // The style violation (constraint()'s own arguments passed positionally):
            val style = response["styleViolations"]!!.jsonArray
            style shouldHaveSize 2
            style.forEach { it.jsonObject["id"]!!.jsonPrimitive.content shouldBe "POSITIONAL_ARGUMENT" }
        }

        test("descriptor declares the optional checkStyle property, script stays the only required argument") {
            val descriptor = ValidateTool.descriptor
            val properties = descriptor.inputSchema["properties"]!!.jsonObject
            val checkStyleProperty = properties["checkStyle"]!!.jsonObject
            checkStyleProperty["type"]!!.jsonPrimitive.content shouldBe "boolean"
            val required = descriptor.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            required shouldBe listOf("script")
        }
    })
