package dev.kuml.plugin.api.renderer

import dev.kuml.core.model.KumlModel
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class KumlRendererPluginTest :
    FunSpec({

        val pdfCapabilities =
            RendererCapabilities(
                supportedFormats = setOf("pdf"),
                supportedDiagramTypes = setOf("uml-class", "c4-container"),
            )

        val wildcardCapabilities =
            RendererCapabilities(
                supportedFormats = setOf("pdf"),
                supportedDiagramTypes = setOf("*"),
            )

        test("RendererCapabilities.canRender returns true for declared format and type") {
            pdfCapabilities.canRender(diagramType = "uml-class", format = "pdf") shouldBe true
        }

        test("RendererCapabilities with wildcard diagram type accepts any diagram type") {
            wildcardCapabilities.canRender(diagramType = "uml-class", format = "pdf") shouldBe true
            wildcardCapabilities.canRender(diagramType = "c4-context", format = "pdf") shouldBe true
            wildcardCapabilities.canRender(diagramType = "sysml2-bdd", format = "pdf") shouldBe true
        }

        test("RendererCapabilities.canRender returns false for undeclared format") {
            pdfCapabilities.canRender(diagramType = "uml-class", format = "png") shouldBe false
        }

        test("minimal KumlRendererPlugin implementation with stubbed render") {
            val descriptor =
                PluginDescriptor(
                    id = "stub-pdf-renderer",
                    name = "Stub PDF Renderer",
                    version = PluginVersion(major = 1, minor = 0, patch = 0),
                    kumlVersionRange = KumlVersionRange(">=3.0.27"),
                    capabilities = setOf(PluginCapability.RENDERER),
                )

            val plugin =
                object : KumlRendererPlugin {
                    override val descriptor = descriptor
                    override val renderCapabilities = pdfCapabilities

                    override fun render(
                        model: KumlModel,
                        format: String,
                        options: Map<String, String>,
                    ): ByteArray = throw NotImplementedError("stub")
                }

            plugin.renderCapabilities.supportedFormats shouldContain "pdf"
            plugin.descriptor.capabilities shouldContain PluginCapability.RENDERER
        }

        test("descriptor capabilities contains RENDERER") {
            val descriptor =
                PluginDescriptor(
                    id = "test-renderer",
                    name = "Test Renderer",
                    version = PluginVersion(major = 1, minor = 0, patch = 0),
                    kumlVersionRange = KumlVersionRange(">=3.0.27"),
                    capabilities = setOf(PluginCapability.RENDERER),
                )
            descriptor.capabilities shouldContain PluginCapability.RENDERER
        }
    })
