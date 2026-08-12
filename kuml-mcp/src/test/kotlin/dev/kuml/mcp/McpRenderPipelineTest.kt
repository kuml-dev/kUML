package dev.kuml.mcp

import dev.kuml.core.dsl.classDiagram
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.renderer.theme.core.ThemeRegistry
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unit-level tests for [McpRenderPipeline]'s theme resolution, bypassing script
 * compilation by building the [dev.kuml.core.model.KumlDiagram] directly via the DSL.
 */
class McpRenderPipelineTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        val diagram =
            classDiagram(name = "ThemeProbe") {
                classOf(name = "Alpha") {
                    attribute(name = "id", type = "String")
                }
            }

        test("render defaults to the kuml theme when themeName is null") {
            val result = McpRenderPipeline.render(diagram = diagram, format = "svg") as McpRenderPipeline.RenderResult.Svg
            result.content shouldContain "#1D2B4F"
            result.content shouldContain "#F8F5F0"
        }

        test("render honors an explicit theme name") {
            val result =
                McpRenderPipeline.render(diagram = diagram, format = "svg", themeName = "plain") as McpRenderPipeline.RenderResult.Svg
            result.content shouldNotContain "#1D2B4F"
        }

        test("resolveTheme loads the registry from the classpath on first use") {
            McpRenderPipeline.resolveTheme()
            val names = ThemeRegistry.names()
            names shouldContain "plain"
            names shouldContain "kuml"
            names shouldContain "elegant"
            names shouldContain "playful"
        }

        test("resolveTheme throws McpToolException for an unknown name") {
            val exception = shouldThrow<McpToolException> { McpRenderPipeline.resolveTheme(themeName = "navy-blue") }
            val error = json.parseToJsonElement(exception.message!!).jsonObject["error"]!!.jsonObject
            error["code"]!!.jsonPrimitive.content shouldBe "KUML-MCP-E-RENDER-UNKNOWN-THEME"
            val validThemes = error["validThemes"]!!.jsonArray.map { it.jsonPrimitive.content }
            validThemes shouldContain "kuml"
        }
    })
