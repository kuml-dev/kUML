package dev.kuml.plugin.api.layout

import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutCapabilities
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Size
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class KumlLayoutPluginTest :
    FunSpec({

        fun fakeEngine(id: String) =
            object : KumlLayoutEngine {
                override val id = LayoutEngineId(id)
                override val capabilities =
                    LayoutCapabilities(
                        deterministic = true,
                        supportedDiagramKinds = setOf(DiagramKind.UmlClass),
                        supportedEdgeStyles = setOf(EdgeRouteStyle.Direct),
                        respectsGridHints = false,
                        respectsRelativeConstraints = false,
                        maxRecommendedNodes = 100,
                    )

                override fun layout(
                    graph: LayoutGraph,
                    hints: LayoutHints,
                ): LayoutResult =
                    LayoutResult(
                        engineId = this.id,
                        seed = null,
                        canvas = Size(0f, 0f),
                        nodes = emptyMap(),
                        edges = emptyMap(),
                        groups = emptyMap(),
                    )
            }

        val testPlugin =
            object : KumlLayoutPlugin {
                override val descriptor =
                    PluginDescriptor(
                        id = "test-layout-plugin",
                        name = "Test Layout Plugin",
                        version = PluginVersion(1, 0, 0),
                        kumlVersionRange = KumlVersionRange(">=3.0.27"),
                        capabilities = setOf(PluginCapability.LAYOUT),
                    )

                override fun engines() = listOf(fakeEngine("test.engine"))
            }

        test("KumlLayoutPlugin engines() must not be empty") {
            testPlugin.engines().shouldNotBeEmpty()
        }

        test("minimal KumlLayoutPlugin implementation returns fake engine") {
            val engines = testPlugin.engines()
            engines.size shouldBe 1
            engines.first().id.value shouldBe "test.engine"
        }

        test("fake engine layout() returns empty LayoutResult without throwing") {
            val engine = testPlugin.engines().first()
            val result = engine.layout(LayoutGraph(nodes = emptyList(), edges = emptyList()))
            result.nodes shouldBe emptyMap()
            result.edges shouldBe emptyMap()
        }

        test("descriptor capabilities contains LAYOUT") {
            testPlugin.descriptor.capabilities shouldContain PluginCapability.LAYOUT
        }

        test("plugin with multiple engines returns all of them") {
            val multiPlugin =
                object : KumlLayoutPlugin {
                    override val descriptor =
                        PluginDescriptor(
                            id = "multi-layout-plugin",
                            name = "Multi Layout Plugin",
                            version = PluginVersion(1, 0, 0),
                            kumlVersionRange = KumlVersionRange.ANY,
                            capabilities = setOf(PluginCapability.LAYOUT),
                        )

                    override fun engines() = listOf(fakeEngine("engine.a"), fakeEngine("engine.b"))
                }

            multiPlugin.engines().size shouldBe 2
            multiPlugin.engines().map { it.id.value } shouldContain "engine.a"
            multiPlugin.engines().map { it.id.value } shouldContain "engine.b"
        }
    })
