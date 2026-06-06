package dev.kuml.io.latex

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

/**
 * End-to-end behaviour for [KumlLatexRenderer] — the V2.0.2 MVP entry point.
 *
 * The strategy is two-pronged:
 *   1. **Structural assertions** on the produced TikZ source — every node
 *      lands at the expected coordinate, every relationship style is picked
 *      correctly, escaping survives stereotypes, etc.
 *   2. A **determinism check** — the same input must produce byte-identical
 *      output, mirroring the existing `KumlSvgRendererTest` invariant. This
 *      is what makes the LaTeX backend goldfile-testable.
 */
class KumlLatexRendererTest :
    StringSpec({

        "single class produces a tikzpicture block" {
            val diagram =
                KumlDiagram(
                    name = "Test",
                    elements = listOf(UmlClass(id = "cls1", name = "Order")),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(300f, 200f),
                    nodes =
                        mapOf(
                            NodeId("cls1") to NodeLayout(bounds = Rect(Point(10f, 10f), Size(120f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, layout)

            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "\\end{tikzpicture}"
            tex shouldContain "Order"
            // Layout origin (10,10) → TikZ (10pt, -10pt). Hard-coded so a drifting
            // axis-flip would fail loudly.
            tex shouldContain "at (10.000pt, -10.000pt)"
            // Snippet mode by default — no \documentclass.
            tex shouldNotContain "\\documentclass"

            SampleOutput.write("uml/single-class-snippet.tex", tex)
        }

        "standalone mode wraps the picture in a complete document" {
            val diagram =
                KumlDiagram(
                    name = "Test",
                    elements = listOf(UmlClass(id = "cls1", name = "Order")),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(300f, 200f),
                    nodes = mapOf(NodeId("cls1") to NodeLayout(bounds = Rect(Point(10f, 10f), Size(120f, 80f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex =
                KumlLatexRenderer.toLatex(
                    diagram,
                    layout,
                    options = LatexRenderOptions(standalone = true),
                )

            tex shouldStartWith "\\documentclass[border=10pt]{standalone}"
            tex shouldContain "\\usepackage{tikz}"
            tex shouldContain "\\usetikzlibrary{arrows.meta"
            tex shouldContain "\\begin{document}"
            tex shouldContain "\\end{document}"

            SampleOutput.write("uml/single-class-standalone.tex", tex)
        }

        "deterministic — same input yields byte-identical output" {
            val diagram =
                KumlDiagram(
                    name = "Test",
                    elements =
                        listOf(
                            UmlClass(id = "a", name = "A"),
                            UmlClass(id = "b", name = "B"),
                        ),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 7L,
                    canvas = Size(500f, 400f),
                    nodes =
                        mapOf(
                            NodeId("a") to NodeLayout(bounds = Rect(Point(10f, 10f), Size(100f, 60f))),
                            NodeId("b") to NodeLayout(bounds = Rect(Point(150f, 10f), Size(100f, 60f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val one = KumlLatexRenderer.toLatex(diagram, layout)
            val two = KumlLatexRenderer.toLatex(diagram, layout)
            one shouldBe two
        }

        "class with attributes and operations emits all three compartments" {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "orderId",
                                name = "orderId",
                                visibility = Visibility.PRIVATE,
                                type = UmlTypeRef("String"),
                            ),
                            UmlProperty(
                                id = "total",
                                name = "total",
                                visibility = Visibility.PUBLIC,
                                type = UmlTypeRef("Money"),
                            ),
                        ),
                    operations =
                        listOf(
                            UmlOperation(
                                id = "place",
                                name = "place",
                                visibility = Visibility.PUBLIC,
                                parameters = listOf(UmlParameter(id = "p1", name = "customer", type = UmlTypeRef("Customer"))),
                                returnType = UmlTypeRef("Receipt"),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "T", elements = listOf(cls))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 300f),
                    nodes = mapOf(NodeId("Order") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(200f, 180f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, layout)

            // Header name. Inside the compartmented variant the name is wrapped
            // in the tabular env, so we just check that the literal name appears
            // somewhere in the output.
            tex shouldContain "Order"
            // Attributes — with visibility glyphs.
            tex shouldContain "- orderId : String"
            tex shouldContain "+ total : Money"
            // Operation with parameter and return type.
            tex shouldContain "+ place(customer: Customer) : Receipt"
            // Two compartment dividers (between header/attrs and attrs/ops).
            val dividerCount = "\\\\draw\\[line width=0\\.4pt\\]".toRegex().findAll(tex).count()
            dividerCount shouldBe 2

            SampleOutput.write("uml/class-with-attributes-and-operations.tex", tex)
        }

        "association edge picks the kuml-association style and direct route" {
            val a = UmlClass(id = "A", name = "A")
            val b = UmlClass(id = "B", name = "B")
            val assoc =
                UmlAssociation(
                    id = "assoc1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "A", multiplicity = Multiplicity()),
                            UmlAssociationEnd(typeId = "B", multiplicity = Multiplicity()),
                        ),
                )
            val diagram = KumlDiagram(name = "T", elements = listOf(a, b, assoc))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(500f, 300f),
                    nodes =
                        mapOf(
                            NodeId("A") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(80f, 60f))),
                            NodeId("B") to NodeLayout(bounds = Rect(Point(200f, 0f), Size(80f, 60f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("assoc1") to EdgeRoute.Direct(source = Point(80f, 30f), target = Point(200f, 30f)),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, layout)

            tex shouldContain "\\draw[kuml-association]"
            tex shouldContain "(80.000pt, -30.000pt) -- (200.000pt, -30.000pt)"

            SampleOutput.write("uml/association.tex", tex)
        }

        "generalization edge picks the kuml-generalization style with hollow triangle" {
            val child = UmlClass(id = "Child", name = "Child")
            val parent = UmlClass(id = "Parent", name = "Parent")
            val gen = UmlGeneralization(id = "gen1", specificId = "Child", generalId = "Parent")
            val diagram = KumlDiagram(name = "T", elements = listOf(child, parent, gen))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(300f, 400f),
                    nodes =
                        mapOf(
                            NodeId("Child") to NodeLayout(bounds = Rect(Point(50f, 200f), Size(80f, 50f))),
                            NodeId("Parent") to NodeLayout(bounds = Rect(Point(50f, 50f), Size(80f, 50f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("gen1") to
                                EdgeRoute.OrthogonalRounded(
                                    source = Point(90f, 200f),
                                    target = Point(90f, 100f),
                                    waypoints = emptyList(),
                                    cornerRadiusPx = 4f,
                                ),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, layout)
            tex shouldContain "\\draw[kuml-generalization, rounded corners=4.000pt]"
            // Hollow triangle (open Triangle) comes from the picture-level style block.
            tex shouldContain "Triangle[length=3mm, open]"

            SampleOutput.write("uml/generalization.tex", tex)
        }

        "stereotypes survive the escape and end up in the header line" {
            val cls =
                UmlClass(
                    id = "Brake",
                    name = "BrakeAssist",
                    stereotypes = listOf("SoftwareComponent"),
                )
            val diagram = KumlDiagram(name = "T", elements = listOf(cls))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(300f, 200f),
                    nodes = mapOf(NodeId("Brake") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(160f, 100f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, layout)

            tex shouldContain "\\guillemotleft{}SoftwareComponent\\guillemotright{}"
            tex shouldContain "BrakeAssist"
        }

        "TikZ node IDs are sanitised — dots and slashes become underscores" {
            val cls = UmlClass(id = "pkg.sub/A", name = "A")
            val diagram = KumlDiagram(name = "T", elements = listOf(cls))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(200f, 100f),
                    nodes = mapOf(NodeId("pkg.sub/A") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(80f, 40f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, layout)
            // Node id became `n_pkg_sub_A`.
            tex shouldContain "(n_pkg_sub_A)"
            tex shouldNotContain "(n_pkg.sub/A)"
        }

        "scale option flows through to the tikzpicture scale attribute" {
            val diagram = KumlDiagram(name = "T", elements = listOf(UmlClass(id = "a", name = "A")))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(100f, 100f),
                    nodes = mapOf(NodeId("a") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(80f, 40f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex =
                KumlLatexRenderer.toLatex(
                    diagram,
                    layout,
                    options = LatexRenderOptions(scale = 0.5),
                )

            tex shouldContain "\\begin{tikzpicture}[scale=0.500"
        }
    })
