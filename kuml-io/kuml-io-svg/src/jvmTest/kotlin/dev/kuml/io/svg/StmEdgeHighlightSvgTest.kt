package dev.kuml.io.svg

import dev.kuml.core.model.DiagramType
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
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Hidden per-transition overlays (`highlight-edge-<id>`) for the desktop simulation, on both the
 * UML STATE path and the SysML-2 STM path. Asserts elements/attributes by regex, never bytes.
 */
class StmEdgeHighlightSvgTest :
    StringSpec({

        fun route(
            x1: Float,
            x2: Float,
        ) = EdgeRoute.Direct(source = Point(x = x1, y = 50f), target = Point(x = x2, y = 50f))

        fun node(
            x: Float,
            w: Float = 100f,
        ) = NodeLayout(bounds = Rect(origin = Point(x = x, y = 20f), size = Size(width = w, height = 60f)))

        fun count(
            svg: String,
            regex: String,
        ): Int = Regex(regex).findAll(svg).count()

        // ── UML STATE ────────────────────────────────────────────────────────

        fun umlFixture(transitionId: String = "t1"): Triple<KumlDiagram, LayoutResult, String> {
            val a = UmlState(id = "A", name = "A")
            val b = UmlState(id = "B", name = "B")
            val sm =
                UmlStateMachine(
                    id = "sm",
                    name = "M",
                    vertices = listOf(a, b),
                    transitions = listOf(UmlTransition(id = transitionId, sourceId = "A", targetId = "B", trigger = "go()")),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(sm), type = DiagramType.STATE)
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 400f, height = 120f),
                    nodes = mapOf(NodeId("A") to node(x = 10f), NodeId("B") to node(x = 200f)),
                    edges = mapOf(EdgeId(transitionId) to route(x1 = 110f, x2 = 200f)),
                    groups = emptyMap(),
                )
            return Triple(diagram, layout, transitionId)
        }

        "UML STATE emits exactly one hidden non-interactive overlay per prepared transition" {
            val (diagram, layout, id) = umlFixture()
            val svg =
                KumlSvgRenderer.toSvg(
                    diagram = diagram,
                    layoutResult = layout,
                    theme = PlainTheme(),
                    options = SvgRenderOptions(preparedHighlightEdgeIds = setOf(id)),
                )
            count(svg, "id=\"highlight-edge-t1\"") shouldBe 1
            count(svg, "class=\"kuml-highlight-edge\"") shouldBe 1
            svg shouldContain "visibility=\"hidden\""
            svg shouldContain "pointer-events=\"none\""
        }

        "UML STATE without the option contains no edge overlay at all" {
            val (diagram, layout, _) = umlFixture()
            val svg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layout, theme = PlainTheme())
            svg shouldNotContain "kuml-highlight-edge"
            svg shouldNotContain "highlight-edge-"
        }

        "UML STATE escapes special characters in the overlay id" {
            val (diagram, layout, id) = umlFixture(transitionId = "t\"<&>1")
            val svg =
                KumlSvgRenderer.toSvg(
                    diagram = diagram,
                    layoutResult = layout,
                    theme = PlainTheme(),
                    options = SvgRenderOptions(preparedHighlightEdgeIds = setOf(id)),
                )
            svg shouldNotContain "highlight-edge-t\"<"
            svg shouldContain "highlight-edge-t"
            svg shouldContain "&quot;"
        }

        "UML STATE ignores prepared ids that match no edge" {
            val (diagram, layout, _) = umlFixture()
            val svg =
                KumlSvgRenderer.toSvg(
                    diagram = diagram,
                    layoutResult = layout,
                    theme = PlainTheme(),
                    options = SvgRenderOptions(preparedHighlightEdgeIds = setOf("nope")),
                )
            svg shouldNotContain "kuml-highlight-edge"
        }

        // ── SysML-2 STM ──────────────────────────────────────────────────────

        fun stmFixture(): Triple<Sysml2Model, StmDiagram, LayoutResult> {
            val model =
                sysml2Model(name = "M") {
                    val initial = stateDef(name = "Initial", isInitial = true)
                    val red = stateDef(name = "Red")
                    transition(name = "init", source = initial, target = red)
                    stmDiagram(name = "Cycle") {
                        include(initial)
                        include(red)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 400f, height = 120f),
                    nodes = mapOf(NodeId("Initial") to node(x = 10f, w = 24f), NodeId("Red") to node(x = 200f)),
                    edges = mapOf(EdgeId("transition:Initial::Red") to route(x1 = 34f, x2 = 200f)),
                    groups = emptyMap(),
                )
            return Triple(model, stm, layout)
        }

        "SysML STM emits a hidden overlay for a prepared transition and none by default" {
            val (model, stm, layout) = stmFixture()
            val withOverlay =
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = stm,
                    layoutResult = layout,
                    theme = PlainTheme(),
                    options = SvgRenderOptions(preparedHighlightEdgeIds = setOf("transition:Initial::Red")),
                )
            count(withOverlay, "id=\"highlight-edge-transition:Initial::Red\"") shouldBe 1
            withOverlay shouldContain "pointer-events=\"none\""

            val plain = KumlSvgRenderer.toSvg(model = model, diagram = stm, layoutResult = layout, theme = PlainTheme())
            plain shouldNotContain "kuml-highlight-edge"
        }
    })
