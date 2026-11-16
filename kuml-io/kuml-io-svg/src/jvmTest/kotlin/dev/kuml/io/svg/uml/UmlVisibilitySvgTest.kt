package dev.kuml.io.svg.uml

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Pins how visibility marks are drawn in a UML class box: all four levels, and the documented
 * default of an attribute (`-`) or operation (`+`) that sets no visibility.
 */
class UmlVisibilitySvgTest :
    FunSpec({

        fun render(umlClass: UmlClass): String {
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(umlClass))
            val size = Size(width = 320f, height = 260f)
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = size,
                    nodes = mapOf(NodeId(umlClass.id) to NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 10f), size = size))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            return KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layoutResult, theme = PlainTheme())
        }

        val string = UmlTypeRef(name = "String")

        test("all four visibility levels are drawn for attributes") {
            val svg =
                render(
                    UmlClass(
                        id = "C",
                        name = "C",
                        attributes =
                            listOf(
                                UmlProperty(id = "a1", name = "a1", visibility = Visibility.PUBLIC, type = string),
                                UmlProperty(id = "a2", name = "a2", visibility = Visibility.PRIVATE, type = string),
                                UmlProperty(id = "a3", name = "a3", visibility = Visibility.PROTECTED, type = string),
                                UmlProperty(id = "a4", name = "a4", visibility = Visibility.PACKAGE, type = string),
                            ),
                    ),
                )
            svg shouldContain "+ a1: String"
            svg shouldContain "- a2: String"
            svg shouldContain "# a3: String"
            svg shouldContain "~ a4: String"
        }

        test("all four visibility levels are drawn for operations") {
            val svg =
                render(
                    UmlClass(
                        id = "C",
                        name = "C",
                        operations =
                            listOf(
                                UmlOperation(id = "o1", name = "o1", visibility = Visibility.PUBLIC),
                                UmlOperation(id = "o2", name = "o2", visibility = Visibility.PRIVATE),
                                UmlOperation(id = "o3", name = "o3", visibility = Visibility.PROTECTED),
                                UmlOperation(id = "o4", name = "o4", visibility = Visibility.PACKAGE),
                            ),
                    ),
                )
            svg shouldContain "+ o1()"
            svg shouldContain "- o2()"
            svg shouldContain "# o3()"
            svg shouldContain "~ o4()"
        }

        test("without an explicit visibility an attribute shows '-' and an operation '+' (documented default)") {
            val svg =
                render(
                    UmlClass(
                        id = "C",
                        name = "C",
                        attributes = listOf(UmlProperty(id = "id", name = "id", type = UmlTypeRef(name = "UUID"))),
                        operations = listOf(UmlOperation(id = "run", name = "run")),
                    ),
                )
            svg shouldContain "- id: UUID"
            svg shouldContain "+ run()"
        }
    })
