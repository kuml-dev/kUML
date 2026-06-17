package dev.kuml.plugin.examples.pdf

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.plugin.api.core.PluginCapability
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PdfRendererPluginTest :
    FunSpec({

        val plugin = PdfRendererPlugin()

        fun testModel(name: String = "TestDiagram"): KumlModel =
            KumlModel(
                root = KumlDiagram(name = name),
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = name,
            )

        test("render('pdf') returns non-empty ByteArray") {
            val result = plugin.render(testModel(), "pdf")
            result.isNotEmpty() shouldBe true
        }

        test("render result starts with PDF magic bytes '%PDF-'") {
            val result = plugin.render(testModel(), "pdf")
            val header = String(result.take(5).toByteArray())
            header shouldBe "%PDF-"
        }

        test("renderCapabilities.canRender('uml-class', 'pdf') returns true") {
            plugin.renderCapabilities.canRender("uml-class", "pdf") shouldBe true
        }

        test("renderCapabilities.canRender('c4-context', 'pdf') returns true") {
            plugin.renderCapabilities.canRender("c4-context", "pdf") shouldBe true
        }

        test("renderCapabilities.canRender('uml-class', 'svg') returns false") {
            plugin.renderCapabilities.canRender("uml-class", "svg") shouldBe false
        }

        test("render('svg', ...) throws IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                plugin.render(testModel(), "svg")
            }
        }

        test("descriptor capabilities contains RENDERER") {
            plugin.descriptor.capabilities shouldContain PluginCapability.RENDERER
        }

        test("descriptor requiredPermissions is empty — rendering is pure computation") {
            plugin.descriptor.requiredPermissions shouldBe emptySet()
        }

        test("descriptor id is dev.kuml.plugin.renderer.pdf") {
            plugin.descriptor.id shouldBe "dev.kuml.plugin.renderer.pdf"
        }

        test("renderCapabilities has 'pdf' in supportedFormats") {
            plugin.renderCapabilities.supportedFormats shouldContain "pdf"
        }

        test("render result size is reasonable (more than 100 bytes)") {
            val result = plugin.render(testModel("My Diagram"), "pdf")
            result.size shouldNotBe 0
            (result.size > 100) shouldBe true
        }
    })
