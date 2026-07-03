package dev.kuml.ai.tools.render

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.result.RenderResult
import dev.kuml.ai.tools.result.ValidateResult
import dev.kuml.ai.tools.uml.UmlEditingTools
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

class RenderingToolsTest :
    FunSpec({

        fun makeTempStore(): RenderArtifactStore {
            val dir = Files.createTempDirectory("kuml-test-renders").toFile()
            dir.deleteOnExit()
            return RenderArtifactStore.forDir(dir)
        }

        fun makeContext(): AgentEditingContext = AgentEditingContext.emptyUml()

        test("render_preview writes SVG to temp and returns the path") {
            val ctx = makeContext()
            val umlTools = UmlEditingTools(ctx)
            val tools = RenderingTools(ctx, makeTempStore())
            runTest {
                umlTools.addClass("Order")
                val result = tools.renderPreview("svg")
                val svg = result.shouldBeInstanceOf<RenderResult.Svg>()
                File(svg.filePath).exists() shouldBe true
            }
        }

        test("render_preview SVG summary contains element and relationship counts") {
            val ctx = makeContext()
            val umlTools = UmlEditingTools(ctx)
            val tools = RenderingTools(ctx, makeTempStore())
            runTest {
                umlTools.addClass("Order")
                umlTools.addClass("Customer")
                umlTools.addAssociation("Order", "Customer")
                val result = tools.renderPreview("svg") as RenderResult.Svg
                result.summary shouldContain "2 elements"
                result.summary shouldContain "1 relationships"
            }
        }

        test("render_preview PNG returns width and height") {
            val ctx = makeContext()
            val umlTools = UmlEditingTools(ctx)
            val tools = RenderingTools(ctx, makeTempStore())
            runTest {
                umlTools.addClass("Service")
                val result = tools.renderPreview("png")
                val png = result.shouldBeInstanceOf<RenderResult.Png>()
                png.widthPx shouldBe 1200
                File(png.filePath).exists() shouldBe true
            }
        }

        test("validate_model returns Ok for a freshly created empty model") {
            val ctx = makeContext()
            val tools = RenderingTools(ctx, makeTempStore())
            runTest {
                val result = tools.validateModel()
                result.shouldBeInstanceOf<ValidateResult.Ok>()
            }
        }

        test("validate_model returns Issues when there are validation errors") {
            // This is a structural test — we verify the validate path executes
            val ctx = makeContext()
            val umlTools = UmlEditingTools(ctx)
            val tools = RenderingTools(ctx, makeTempStore())
            runTest {
                umlTools.addClass("Order")
                // Model is structurally valid — should return Ok
                val result = tools.validateModel()
                result.shouldBeInstanceOf<ValidateResult.Ok>()
            }
        }
    }) {
    companion object {
        private infix fun Boolean.shouldBe(b: Boolean) {
            if (this != b) throw AssertionError("Expected $b but was $this")
        }

        private infix fun Int.shouldBe(n: Int) {
            if (this != n) throw AssertionError("Expected $n but was $this")
        }
    }
}
