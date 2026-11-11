package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection.In
import dev.kuml.sysml2.ConstraintParameterDirection.Out
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.dsl.pin
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Nacharbeit 64: bindings inside a nested constraint (owner pin to inner pin, sibling to sibling) carry their
 * name like every other binding. Asserts on the text content of the label groups only, so it needs no font.
 */
class Sysml2ParNestedBindLabelSvgTest :
    StringSpec({
        "every binding of a nested constraint appears exactly once as an edge label" {
            val model =
                sysml2Model(name = "NestedBinds") {
                    val real = attributeDef(name = "Real")
                    val outer =
                        constraintDef(
                            name = "Outer",
                            expression = "o = f(i1, i2)",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "i1", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "i2", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "o1", typeId = "Real", direction = Out),
                                ),
                        )
                    val a =
                        constraintDef(
                            name = "StageA",
                            expression = "a_out = a1",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "a1", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "a_out", typeId = "Real", direction = Out),
                                ),
                            parent = outer,
                        )
                    val b =
                        constraintDef(
                            name = "StageB",
                            expression = "b_out = b1 + b2",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "b1", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "b2", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "b_out", typeId = "Real", direction = Out),
                                ),
                            parent = outer,
                        )
                    bind(name = "i1_a1", source = outer.pin("i1"), target = a.pin("a1"))
                    bind(name = "i2_b2", source = outer.pin("i2"), target = b.pin("b2"))
                    bind(name = "a_to_b1", source = a.pin("a_out"), target = b.pin("b1"))
                    bind(name = "b_to_o1", source = b.pin("b_out"), target = outer.pin("o1"))
                    parDiagram(name = "Nested binds") {
                        include(definition = outer)
                        include(definition = a)
                        include(definition = b)
                        include(definition = real)
                    }
                }
            val diagram = model.diagrams.filterIsInstance<ParDiagram>().single()
            val graph =
                Sysml2LayoutBridge.toLayoutGraph(
                    model = model,
                    diagram = diagram,
                    sizeProvider = Sysml2LayoutBridge.parContentAwareSizeProvider(model),
                )
            val layout =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = diagram),
                        spec = diagram.layout,
                        kind = SpecDiagramKind.SYSML2_PAR,
                    ).result
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())

            val labels =
                Regex("""<g class="kuml-edge-label"[^>]*>(.*?)</g>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(svg)
                    .flatMap { g -> Regex("""<text[^>]*>([^<]*)</text>""").findAll(g.groupValues[1]).map { it.groupValues[1].trim() } }
                    .toList()
            for (name in listOf("i1_a1", "i2_b2", "a_to_b1", "b_to_o1")) {
                labels.count { it == name } shouldBe 1
            }
        }
    })
