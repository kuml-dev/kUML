package dev.kuml.cli

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.UmlContentSizeProvider
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.valueOrNull

/** PAL 2B: the diagram-level `layout { }` block of UML diagrams (P-UML-01 b) and its element-level namesake. */
class LayoutBlockTest :
    FunSpec({
        fun diagramOf(code: String): KumlDiagram {
            val result = KumlScriptHost.eval(code = code)
            result.reports
                .filter { it.severity == ScriptDiagnostic.Severity.ERROR }
                .map { it.message }
                .shouldBeEmpty()
            val value = result.valueOrNull()!!.returnValue
            return dev.kuml.core.script.DiagramExtractor
                .extract(returnValue = value as ResultValue, input = File("inline.kuml.kts"))
        }

        val subclasses = (1..11).joinToString("\n") { "    val s$it = classOf(name = \"Sub$it\") { extends(general = base) }" }

        test("classDiagram layout { wrapColumns = 3 } stores the spec and limits the subclasses per row") {
            val diagram =
                diagramOf(
                    """
                    import dev.kuml.core.dsl.layout.layout
                    classDiagram(name = "Zoo") {
                        val base = classOf(name = "Base") {
                            layout { col = 1 }
                        }
                    $subclasses
                        layout {
                            wrapColumns = 3
                            aspectRatio = 1.6
                        }
                    }
                    """.trimIndent(),
                )
            diagram.layout shouldBe LayoutSpec(wrapColumns = 3, aspectRatio = 1.6)

            val engine = ElkLayoutEngineProvider().engine()
            val graph =
                UmlLayoutBridge.toLayoutGraph(
                    diagram = diagram,
                    sizeProvider = UmlContentSizeProvider(diagram = diagram, layoutDirection = LayoutHints.DEFAULT.direction),
                )
            val result = LayoutSpecRunner.layout(engine = engine, graph = graph, hints = LayoutHints.DEFAULT, spec = diagram.layout).result
            val perRow =
                result.nodes.entries
                    .filter { it.key.value.contains("Sub") }
                    .groupingBy { it.value.bounds.origin.y }
                    .eachCount()
            (perRow.values.max() <= 3) shouldBe true
        }

        test("without a layout block the diagram has no spec and element hints keep working") {
            val diagram =
                diagramOf(
                    """
                    import dev.kuml.core.dsl.layout.layout
                    classDiagram(name = "Plain") {
                        classOf(name = "A") { layout { col = 2 } }
                    }
                    """.trimIndent(),
                )
            diagram.layout shouldBe null
            diagram.elements.shouldNotBeNull()
        }

        test("every renderable fixture still renders with the layout block present") {
            val tmp = Files.createTempDirectory("kuml-layout-block")
            try {
                val script = tmp.resolve("z.kuml.kts").toFile()
                script.writeText(
                    """
                    classDiagram(name = "Z") {
                        val a = classOf(name = "A")
                        val b = classOf(name = "B")
                        layout { sameLayer("A", "B"); direction = Right; maxWidth = 1200 }
                    }
                    """.trimIndent(),
                )
                RenderPipeline.run(input = script, output = tmp.resolve("z.svg"), format = "svg", width = 800, themeName = "kuml")
                (Files.size(tmp.resolve("z.svg")) > 0L) shouldBe true
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

        fun hubScript(direction: String) =
            """
            classDiagram(name = "Hub") {
                val hub = classOf(name = "Hub")
                ${(1..6).joinToString("\n") { "val n$it = classOf(name = \"Leaf$it\") { extends(general = hub) }" }}
                layout { direction = $direction }
            }
            """.trimIndent()

        fun hubBox(direction: String): Pair<Double, Double> {
            val tmp = Files.createTempDirectory("kuml-layout-size")
            try {
                val script = tmp.resolve("h.kuml.kts").toFile().apply { writeText(hubScript(direction)) }
                RenderPipeline.run(input = script, output = tmp.resolve("h.svg"), format = "svg", width = 800, themeName = "kuml")
                val svg = tmp.resolve("h.svg").toFile().readText()
                // The first class box is the hub (declared first); the canvas and frame rects are not class boxes.
                val hub =
                    Regex("<rect[^>]*class=\"kuml-class\"[^>]*>").find(svg)?.value
                        ?: error("no class box in SVG")

                fun attr(name: String): Double = Regex("""\b$name="([0-9.]+)"""").find(hub)!!.groupValues[1].toDouble()
                return attr("width") to attr("height")
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

        test("UML boxes are sized for the layout { direction }: Right grows the hub in height, Down in width") {
            val down = hubBox("Down")
            val right = hubBox("Right")
            (right.second > down.second) shouldBe true
            (down.first > right.first) shouldBe true
        }

        test("unknown ids, exceeded limits and first/last conflicts are reported") {
            val engine = ElkLayoutEngineProvider().engine()
            val diagram =
                diagramOf(
                    """
                    classDiagram(name = "Z") {
                        classOf(name = "Cat"); classOf(name = "Dog")
                        layout { sameLayer("Cat", "Dgo"); first("Cat"); last("Cat") }
                    }
                    """.trimIndent(),
                )
            val graph =
                UmlLayoutBridge.toLayoutGraph(
                    diagram = diagram,
                    sizeProvider = UmlContentSizeProvider(diagram = diagram, layoutDirection = LayoutHints.DEFAULT.direction),
                )
            val findings =
                LayoutSpecRunner
                    .layout(
                        engine = engine,
                        graph = graph,
                        hints = LayoutHints.DEFAULT,
                        spec = diagram.layout,
                    ).findings
            findings.any { it.code == "KUML-LAYOUT-W004" && it.message.contains("Dgo") } shouldBe true
            findings.any { it.code == "KUML-LAYOUT-W001" && it.message.contains("Cat") } shouldBe true

            val many = LayoutSpec(sameLayer = (1..40).map { listOf("Cat", "Dog") })
            val f2 = LayoutSpecRunner.layout(engine = engine, graph = graph, hints = LayoutHints.DEFAULT, spec = many).findings
            f2.any { it.code == "KUML-LAYOUT-E001" } shouldBe true
        }

        test("an explicit aspectRatio missed on the narrow side is reported as W003") {
            val engine = ElkLayoutEngineProvider().engine()
            val diagram =
                diagramOf(
                    """
                    classDiagram(name = "Z") {
                        val a = classOf(name = "A"); classOf(name = "B") { extends(general = a) }
                        layout { aspectRatio = 10.0 }
                    }
                    """.trimIndent(),
                )
            val graph =
                UmlLayoutBridge.toLayoutGraph(
                    diagram = diagram,
                    sizeProvider = UmlContentSizeProvider(diagram = diagram, layoutDirection = LayoutHints.DEFAULT.direction),
                )
            val outcome = LayoutSpecRunner.layout(engine = engine, graph = graph, hints = LayoutHints.DEFAULT, spec = diagram.layout)
            outcome.findings.any { it.code == "KUML-LAYOUT-W003" } shouldBe true
        }
    })
