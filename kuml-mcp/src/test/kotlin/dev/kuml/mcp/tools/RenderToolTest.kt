package dev.kuml.mcp.tools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * V3.3.x bugfix — `kuml.render` must default to the `kuml` brand theme, matching
 * every other rendering surface (CLI, Web/Server, Gradle plugin, Desktop). Prior
 * to this fix the MCP server hardcoded `PlainTheme()`, ignoring the default
 * entirely. See McpRenderPipeline.kt.
 */
class RenderToolTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } }

        val script =
            """
            classDiagram(name = "ThemeProbe") {
                classOf(name = "Alpha") {
                    attribute(name = "id", type = "String")
                }
            }
            """.trimIndent()

        test("descriptor declares an optional theme property and keeps script as the only required argument") {
            val descriptor = RenderTool.descriptor
            val properties = descriptor.inputSchema["properties"]!!.jsonObject
            val themeProperty = properties["theme"]!!.jsonObject
            themeProperty["type"]!!.jsonPrimitive.content shouldBe "string"
            themeProperty["description"]!!.jsonPrimitive.content shouldContain "kuml"
            val required = descriptor.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            required shouldBe listOf("script")
        }

        test("default render uses the kuml brand theme, not plain") {
            val result = RenderTool.call(args("script" to script))
            val svg = result[0].text!!
            svg shouldContain "#1D2B4F" // brand border/edge (navy)
            svg shouldContain "#F8F5F0" // brand nodeFill (off-white)
        }

        test("explicit theme plain is honored") {
            val defaultSvg = RenderTool.call(args("script" to script))[0].text!!
            val plainSvg = RenderTool.call(args("script" to script, "theme" to "plain"))[0].text!!
            plainSvg shouldNotContain "#1D2B4F"
            plainSvg shouldContain "#000000"
            plainSvg shouldNotBe defaultSvg
        }

        test("explicit theme kuml equals the default") {
            val svg = RenderTool.call(args("script" to script, "theme" to "kuml"))[0].text!!
            svg shouldContain "#1D2B4F"
            svg shouldContain "#F8F5F0"
        }

        test("blank theme falls back to the default") {
            val svg = RenderTool.call(args("script" to script, "theme" to ""))[0].text!!
            svg shouldContain "#1D2B4F"
            svg shouldContain "#F8F5F0"
        }

        test("unknown theme throws McpToolException naming the registered themes") {
            val exception =
                shouldThrow<McpToolException> {
                    RenderTool.call(args("script" to script, "theme" to "navy-blue"))
                }
            val error = json.parseToJsonElement(exception.message!!).jsonObject["error"]!!.jsonObject
            error["code"]!!.jsonPrimitive.content shouldBe "KUML-MCP-E-RENDER-UNKNOWN-THEME"
            val validThemes = error["validThemes"]!!.jsonArray.map { it.jsonPrimitive.content }
            validThemes shouldContain "kuml"
            validThemes shouldContain "plain"
        }

        test("png format also honors the theme") {
            val result = RenderTool.call(args("script" to script, "format" to "png"))
            result.size shouldBe 1
            result[0].type shouldBe "image"
            result[0].mimeType shouldBe "image/png"
            result[0].data.isNullOrBlank() shouldBe false
        }
    })
