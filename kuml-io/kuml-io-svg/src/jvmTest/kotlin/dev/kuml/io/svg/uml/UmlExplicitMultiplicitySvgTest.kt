package dev.kuml.io.svg.uml

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/** An explicit multiplicity "1" is drawn at its end; the implicit default is not (Nacharbeit 79). */
class UmlExplicitMultiplicitySvgTest :
    FunSpec({
        // Edge from (200,120) down to (200,240); labels of the source end sit near y=120, of the target end near y=240.
        fun render(
            source: UmlAssociationEnd,
            target: UmlAssociationEnd,
        ): String {
            val a = UmlClass(id = "A", name = "A")
            val b = UmlClass(id = "B", name = "B")
            val diagram =
                KumlDiagram(
                    name = "T",
                    type = DiagramType.CLASS,
                    elements = listOf(a, b, UmlAssociation(id = "assoc1", ends = listOf(source, target))),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 400f, height = 360f),
                    nodes =
                        mapOf(
                            NodeId("A") to
                                NodeLayout(bounds = Rect(origin = Point(x = 120f, y = 40f), size = Size(width = 160f, height = 80f))),
                            NodeId("B") to
                                NodeLayout(bounds = Rect(origin = Point(x = 120f, y = 240f), size = Size(width = 160f, height = 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("assoc1") to EdgeRoute.Direct(source = Point(x = 200f, y = 120f), target = Point(x = 200f, y = 240f)),
                        ),
                    groups = emptyMap(),
                )
            return KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layout, theme = PlainTheme())
        }

        /** y coordinates of all label `<text class="kuml-small">` nodes whose content is exactly [content] (the halo copy is skipped). */
        fun textYs(
            svg: String,
            content: String,
        ): List<Float> =
            Regex("""<text class="kuml-small"[^>]*\by="([-0-9.]+)"[^>]*>\s*$content\s*</text>""")
                .findAll(svg)
                .map { it.groupValues[1].toFloat() }
                .toList()

        test("two explicit 1 ends draw two \"1\" labels, one near each end") {
            val svg =
                render(
                    source = UmlAssociationEnd(typeId = "A", multiplicityExplicit = true),
                    target = UmlAssociationEnd(typeId = "B", multiplicityExplicit = true),
                )
            val ys = textYs(svg = svg, content = "1").sorted()
            ys shouldHaveSize 2
            (ys[0] in 100f..160f) shouldBe true
            (ys[1] in 200f..280f) shouldBe true
        }

        test("implicit 1 ends draw no label") {
            val svg = render(source = UmlAssociationEnd(typeId = "A"), target = UmlAssociationEnd(typeId = "B"))
            textYs(svg = svg, content = "1") shouldHaveSize 0
        }

        test("a mixed 2 and implicit 1 stays as before: only the \"2\" is drawn") {
            val svg =
                render(
                    source =
                        UmlAssociationEnd(
                            typeId = "A",
                            multiplicity = Multiplicity(lower = 2, upper = 2),
                            multiplicityExplicit = true,
                        ),
                    target = UmlAssociationEnd(typeId = "B"),
                )
            textYs(svg = svg, content = "2") shouldHaveSize 1
            textYs(svg = svg, content = "1") shouldHaveSize 0
        }
    })
