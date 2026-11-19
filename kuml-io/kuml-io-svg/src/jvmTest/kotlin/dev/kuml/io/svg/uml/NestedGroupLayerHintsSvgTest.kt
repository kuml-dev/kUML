package dev.kuml.io.svg.uml

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlArtifact
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Nacharbeit 119: a deployment node with a nested node and `wrapColumns = 2` renders as a grid of its direct artifacts
 * with the nested node as a closed box inside; the PNG next to the sample SVG is for the visual inspection.
 */
class NestedGroupLayerHintsSvgTest :
    FunSpec({
        test("a deployment node with a nested node: wrapColumns acts on the direct artifacts, the nested node stays closed") {
            val direct = (1..6).map { UmlArtifact(id = "M$it", name = "Member $it") }
            val inner = (1..2).map { UmlArtifact(id = "Q$it", name = "Inner $it") }
            val n1 = UmlNode(id = "N1", name = "Nested node", artifacts = inner)
            val c1 = UmlNode(id = "C1", name = "Outer node", artifacts = direct, children = listOf(n1))
            val diagram =
                KumlDiagram(
                    name = "Nested hints",
                    type = DiagramType.DEPLOYMENT,
                    elements = listOf(c1, UmlDependency(id = "d1", clientId = "Q1", supplierId = "Q2")),
                    layout = LayoutSpec(wrapColumns = 2),
                )
            val graph = UmlLayoutBridge.toLayoutGraph(diagram = diagram)
            val outcome =
                LayoutSpecRunner.layout(
                    engine = ElkLayoutEngine(),
                    graph = graph,
                    hints = LayoutHints(),
                    spec = diagram.layout,
                    kind = SpecDiagramKind.UML_DEPLOYMENT,
                    diagramName = diagram.name,
                )
            outcome.findings.map { it.code } shouldBe emptyList()
            val svg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = outcome.result, theme = PlainTheme())
            svg shouldContain "Nested node"
            svg shouldContain "Inner 2"
            SampleOutput.write(filename = "uml/deployment-nested-node-wrap-columns.svg", content = svg)
        }
    })
