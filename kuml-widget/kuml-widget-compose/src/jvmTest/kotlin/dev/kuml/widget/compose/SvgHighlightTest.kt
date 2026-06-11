package dev.kuml.widget.compose

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests verifying that the SVG highlight-ring overlay works correctly in
 * [KumlSvgRenderer.renderUmlStateDiagram] (V2.0.43).
 */
class SvgHighlightTest : FunSpec({

    fun buildMinimalSmDiagram(): Pair<KumlDiagram, LayoutResult> {
        val initial = UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL)
        val stateA = UmlState(id = "StateA", name = "State A")
        val sm = UmlStateMachine(
            id = "sm1",
            name = "TestSm",
            vertices = listOf(initial, stateA),
            transitions = listOf(
                UmlTransition(id = "t0", sourceId = "init", targetId = "StateA"),
            ),
        )
        val diagram = KumlDiagram(
            name = "TestSm",
            type = DiagramType.STATE,
            elements = listOf(sm),
            id = "sm1",
        )
        // Minimal layout result with one node for "StateA"
        val layoutResult = LayoutResult(
            engineId = LayoutEngineId("test"),
            seed = null,
            canvas = Size(300f, 200f),
            nodes = mapOf(
                NodeId("StateA") to NodeLayout(
                    bounds = Rect(origin = Point(20f, 30f), size = Size(120f, 60f)),
                ),
            ),
            edges = emptyMap(),
            groups = mapOf(
                GroupId("sm1") to GroupLayout(
                    bounds = Rect(origin = Point(0f, 0f), size = Size(300f, 200f)),
                ),
            ),
        )
        return diagram to layoutResult
    }

    test("highlight ring present when highlightVertexIds non-empty") {
        val (diagram, layout) = buildMinimalSmDiagram()
        val options = SvgRenderOptions(
            highlightVertexIds = setOf("StateA"),
        )
        val svg = KumlSvgRenderer.toSvg(
            diagram = diagram,
            layoutResult = layout,
            theme = PlainTheme(),
            options = options,
        )
        svg shouldContain "kuml-highlight-ring"
    }

    test("no ring when highlightVertexIds empty") {
        val (diagram, layout) = buildMinimalSmDiagram()
        val options = SvgRenderOptions(
            highlightVertexIds = emptySet(),
        )
        val svg = KumlSvgRenderer.toSvg(
            diagram = diagram,
            layoutResult = layout,
            theme = PlainTheme(),
            options = options,
        )
        svg shouldNotContain "kuml-highlight-ring"
    }

    test("ring ID matches vertex ID") {
        val (diagram, layout) = buildMinimalSmDiagram()
        val options = SvgRenderOptions(
            highlightVertexIds = setOf("StateA"),
        )
        val svg = KumlSvgRenderer.toSvg(
            diagram = diagram,
            layoutResult = layout,
            theme = PlainTheme(),
            options = options,
        )
        svg shouldContain "highlight-ring-StateA"
    }

    test("multiple highlight rings for multiple vertex IDs") {
        val initial = UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL)
        val stateA = UmlState(id = "StateA", name = "State A")
        val stateB = UmlState(id = "StateB", name = "State B")
        val sm = UmlStateMachine(
            id = "sm2",
            name = "TestSm2",
            vertices = listOf(initial, stateA, stateB),
            transitions = listOf(
                UmlTransition(id = "t0", sourceId = "init", targetId = "StateA"),
                UmlTransition(id = "t1", sourceId = "StateA", targetId = "StateB", trigger = "go"),
            ),
        )
        val diagram = KumlDiagram(
            name = "TestSm2",
            type = DiagramType.STATE,
            elements = listOf(sm),
            id = "sm2",
        )
        val layoutResult = LayoutResult(
            engineId = LayoutEngineId("test"),
            seed = null,
            canvas = Size(400f, 300f),
            nodes = mapOf(
                NodeId("StateA") to NodeLayout(bounds = Rect(origin = Point(20f, 30f), size = Size(120f, 60f))),
                NodeId("StateB") to NodeLayout(bounds = Rect(origin = Point(200f, 30f), size = Size(120f, 60f))),
            ),
            edges = emptyMap(),
            groups = mapOf(
                GroupId("sm2") to GroupLayout(bounds = Rect(origin = Point(0f, 0f), size = Size(400f, 300f))),
            ),
        )
        val options = SvgRenderOptions(
            highlightVertexIds = setOf("StateA", "StateB"),
        )
        val svg = KumlSvgRenderer.toSvg(
            diagram = diagram,
            layoutResult = layoutResult,
            theme = PlainTheme(),
            options = options,
        )
        svg shouldContain "highlight-ring-StateA"
        svg shouldContain "highlight-ring-StateB"
    }

    test("highlight ring uses custom stroke color") {
        val (diagram, layout) = buildMinimalSmDiagram()
        val customColor = "#AABBCC"
        val options = SvgRenderOptions(
            highlightVertexIds = setOf("StateA"),
            highlightStrokeColor = customColor,
        )
        val svg = KumlSvgRenderer.toSvg(
            diagram = diagram,
            layoutResult = layout,
            theme = PlainTheme(),
            options = options,
        )
        svg shouldContain customColor
    }

    test("no ring emitted for vertex not in highlightVertexIds") {
        val (diagram, layout) = buildMinimalSmDiagram()
        val options = SvgRenderOptions(
            highlightVertexIds = setOf("SomeOtherVertex"),
        )
        val svg = KumlSvgRenderer.toSvg(
            diagram = diagram,
            layoutResult = layout,
            theme = PlainTheme(),
            options = options,
        )
        svg shouldNotContain "kuml-highlight-ring"
    }
})
