package dev.kuml.io.svg.sysml2

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
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.hypot

/**
 * Nacharbeit 43, named regression test "back edge label": two states joined in both directions (`advance` and a
 * `retreat` back edge with a long guard) in a crowded layout. The back edge label used to be drawn through its own line
 * because a 200 px wide label fitted nowhere; it is now wrapped narrower and placed beside the line. Geometry only: the
 * routes are fixed, the label rectangles are read from the SVG (`data-label-rect`), the edge lines from the path data.
 */
class Sysml2StmBackEdgeLabelTest :
    FunSpec({
        fun model(): Sysml2Model =
            sysml2Model(name = "Cycle") {
                val init = stateDef(name = "Init", isInitial = true)
                val a = stateDef(name = "Alpha")
                val b = stateDef(name = "Beta")
                val c = stateDef(name = "Gamma")
                transition(name = "i", source = init, target = a)
                transition(name = "ab", source = a, target = b, trigger = "advance")
                transition(
                    name = "ba",
                    source = b,
                    target = a,
                    trigger = "retreat",
                    guard = "retryCount < maxRetries && !aborted",
                    effect = "reset()",
                )
                transition(name = "bc", source = b, target = c, trigger = "next")
                transition(name = "ca", source = c, target = a, trigger = "restartEverythingNow", guard = "operatorConfirmed")
                stmDiagram(name = "Cycle") {
                    include(init)
                    include(a)
                    include(b)
                    include(c)
                }
            }

        fun node(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = NodeLayout(bounds = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h)))

        fun pt(
            x: Float,
            y: Float,
        ) = Point(x = x, y = y)

        // Routes of the crowded case as the ELK layout produced them (canvas coordinates before padding).
        fun layout(): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("fixed"),
                seed = 1L,
                canvas = Size(width = 560f, height = 460f),
                nodes =
                    mapOf(
                        NodeId("Init") to node(x = 130f, y = 322f, w = 24f, h = 24f),
                        NodeId("Alpha") to node(x = 24f, y = 382f, w = 236f, h = 44f),
                        NodeId("Beta") to node(x = 235.1f, y = 24f, w = 222f, h = 44f),
                        NodeId("Gamma") to node(x = 297.6f, y = 203f, w = 208f, h = 44f),
                    ),
                edges =
                    mapOf(
                        EdgeId("transition:Init::Alpha") to
                            EdgeRoute.Direct(source = pt(x = 142f, y = 346f), target = pt(x = 142f, y = 382f)),
                        EdgeId("transition:Alpha::Beta") to
                            EdgeRoute.OrthogonalRounded(
                                source = pt(x = 71.2f, y = 382f),
                                target = pt(x = 290.6f, y = 68f),
                                waypoints =
                                    listOf(
                                        pt(x = 71.2f, y = 292f),
                                        pt(x = 64.6f, y = 292f),
                                        pt(x = 64.6f, y = 113f),
                                        pt(x = 290.6f, y = 113f),
                                    ),
                                cornerRadiusPx = 0f,
                            ),
                        EdgeId("transition:Beta::Alpha") to
                            EdgeRoute.OrthogonalRounded(
                                source = pt(x = 346.1f, y = 68f),
                                target = pt(x = 118.4f, y = 382f),
                                waypoints =
                                    listOf(
                                        pt(x = 346.1f, y = 153f),
                                        pt(x = 107.6f, y = 153f),
                                        pt(x = 107.6f, y = 292f),
                                        pt(x = 118.4f, y = 292f),
                                    ),
                                cornerRadiusPx = 0f,
                            ),
                        EdgeId("transition:Beta::Gamma") to
                            EdgeRoute.Direct(source = pt(x = 401.6f, y = 68f), target = pt(x = 401.6f, y = 203f)),
                        EdgeId("transition:Gamma::Alpha") to
                            EdgeRoute.OrthogonalRounded(
                                source = pt(x = 401.6f, y = 247f),
                                target = pt(x = 212.8f, y = 382f),
                                waypoints = listOf(pt(x = 401.6f, y = 292f), pt(x = 212.8f, y = 292f)),
                                cornerRadiusPx = 0f,
                            ),
                    ),
                groups = emptyMap(),
            )

        fun render(): String {
            val m = model()
            val d = m.diagrams.filterIsInstance<StmDiagram>().single()
            return KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = layout(), theme = PlainTheme())
        }

        fun labelRects(svg: String): Map<String, Rect> =
            Regex("""data-label-id="([^"]+)" data-label-rect="([^"]+)"""")
                .findAll(svg)
                .associate { m ->
                    val v = m.groupValues[2].split(" ").map { it.toFloat() }
                    m.groupValues[1] to Rect(origin = pt(x = v[0], y = v[1]), size = Size(width = v[2], height = v[3]))
                }

        fun edgeLines(svg: String): Map<String, List<Point>> =
            Regex("""<g id="(transition:[^"]+)">\s*<(?:path d="([^"]+)"|line x1="([^"]+)" y1="([^"]+)" x2="([^"]+)" y2="([^"]+)")""")
                .findAll(svg)
                .associate { m ->
                    val raw = if (m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.groupValues.drop(3).joinToString(" ")
                    val nums = Regex("""-?\d+(?:\.\d+)?""").findAll(raw).map { it.value.toFloat() }.toList()
                    m.groupValues[1] to nums.chunked(2).map { pt(x = it[0], y = it[1]) }
                }

        fun touches(
            line: List<Point>,
            r: Rect,
            margin: Float,
        ): Boolean {
            val l = r.origin.x - margin
            val t = r.origin.y - margin
            val rr = r.origin.x + r.size.width + margin
            val b = r.origin.y + r.size.height + margin
            for (i in 0 until line.size - 1) {
                val a = line[i]
                val c = line[i + 1]
                val steps = maxOf(1, hypot(c.x - a.x, c.y - a.y).toInt())
                for (s in 0..steps) {
                    val x = a.x + (c.x - a.x) * s / steps
                    val y = a.y + (c.y - a.y) * s / steps
                    if (x in l..rr && y in t..b) return true
                }
            }
            return false
        }

        fun overlap(
            a: Rect,
            b: Rect,
        ) = a.origin.x < b.origin.x + b.size.width &&
            b.origin.x < a.origin.x + a.size.width &&
            a.origin.y < b.origin.y + b.size.height &&
            b.origin.y < a.origin.y + a.size.height

        test("back edge label: no label, widened by 1 px, touches its own line, the counter edge or any other edge") {
            val svg = render()
            val labels = labelRects(svg)
            val lines = edgeLines(svg)
            labels.keys.sorted() shouldBe
                listOf("transition:Alpha::Beta", "transition:Beta::Alpha", "transition:Beta::Gamma", "transition:Gamma::Alpha")
            (lines.size >= 5) shouldBe true
            for ((id, r) in labels) {
                for ((edge, line) in lines) {
                    if (touches(line = line, r = r, margin = 1f)) throw AssertionError("label $id touches the line of $edge")
                }
            }
        }

        test("back edge label: no label covers a state or another label") {
            val labels = labelRects(render()).entries.sortedBy { it.key }
            val pad = 16f
            val states = layout().nodes.values.map { it.bounds }
            for ((id, r) in labels) {
                for (s in states) {
                    val shifted = Rect(origin = pt(x = s.origin.x + pad, y = s.origin.y + pad), size = s.size)
                    if (overlap(a = r, b = shifted)) throw AssertionError("label $id covers a state")
                }
            }
            for (i in labels.indices) {
                for (j in i + 1 until labels.size) {
                    if (overlap(
                            a = labels[i].value,
                            b = labels[j].value,
                        )
                    ) {
                        throw AssertionError("labels ${labels[i].key} and ${labels[j].key} overlap")
                    }
                }
            }
        }

        test("back edge label: the plan is deterministic") {
            render() shouldBe render()
        }
    })
