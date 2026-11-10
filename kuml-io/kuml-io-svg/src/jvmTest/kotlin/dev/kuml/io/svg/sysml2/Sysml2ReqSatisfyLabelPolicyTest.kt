@file:Suppress("RequireNamedArguments") // geometry fixtures with rectangle literals

package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.EdgeId
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
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.ReqSatisfy
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.sqrt

/**
 * Nacharbeit 59: in a requirement diagram the «satisfy» label sits just after the satisfier end of its line, never
 * covers a requirement box, and keeps off the group frame strips. Geometry only (hand-built layouts, label widths are
 * estimated), so nothing here depends on fonts.
 */
class Sysml2ReqSatisfyLabelPolicyTest :
    StringSpec({
        val padding = 16f

        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x, y), size = Size(w, h))

        fun hit(
            a: Rect,
            b: Rect,
        ) = a.origin.x < b.origin.x + b.size.width &&
            b.origin.x < a.origin.x + a.size.width &&
            a.origin.y < b.origin.y + b.size.height &&
            b.origin.y < a.origin.y + a.size.height

        fun labelRects(svg: String): Map<String, Rect> =
            Regex("""data-edge-id="([^"]+)" data-label-id="[^"]+" data-label-rect="([^"]+)"""")
                .findAll(svg)
                .associate { m ->
                    val (x, y, w, h) = m.groupValues[2].split(" ").map { it.toFloat() }
                    m.groupValues[1] to rect(x, y, w, h)
                }

        class Scene(
            val model: Sysml2Model,
            val diagram: ReqDiagram,
            val layout: LayoutResult,
        )

        /** One satisfier `Ctrl` at the left, [reqs] requirement boxes in a column at x = 520, one satisfy edge each. */
        fun scene(
            reqCount: Int,
            extraNodes: Map<NodeId, Rect> = emptyMap(),
            groups: Map<GroupId, GroupLayout> = emptyMap(),
            reqHeight: Float = 70f,
        ): Scene {
            val ids = (1..reqCount).map { "Req-%03d".format(it) }
            val model =
                Sysml2Model(
                    name = "Alpha",
                    definitions =
                        listOf(PartDefinition(id = "Ctrl", name = "Ctrl")) +
                            ids.map { RequirementDefinition(id = it, name = it, reqId = it, text = "Alpha shall do $it") },
                )
            val sat = ids.map { ReqSatisfy(id = "satisfy:Ctrl::$it", sourceId = "Ctrl", requirementId = it) }
            val diagram = ReqDiagram(name = "Alpha", elementIds = listOf("Ctrl") + ids, satisfies = sat)
            val nodes = LinkedHashMap<NodeId, NodeLayout>()
            nodes[NodeId("Ctrl")] = NodeLayout(bounds = rect(20f, 200f, 120f, 80f))
            val edges = LinkedHashMap<EdgeId, EdgeRoute>()
            ids.forEachIndexed { i, id ->
                val y = 20f + i * 130f
                nodes[NodeId(id)] = NodeLayout(bounds = rect(520f, y, 160f, reqHeight))
                edges[EdgeId("satisfy:Ctrl::$id")] =
                    EdgeRoute.Direct(source = Point(140f, 240f), target = Point(520f, y + reqHeight / 2f))
            }
            extraNodes.forEach { (k, v) -> nodes[k] = NodeLayout(bounds = v) }
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(800f, 20f + reqCount * 130f + 40f),
                    nodes = nodes,
                    edges = edges,
                    groups = groups,
                )
            return Scene(model, diagram, layout)
        }

        fun render(s: Scene) = KumlSvgRenderer.toSvg(model = s.model, diagram = s.diagram, layoutResult = s.layout, theme = PlainTheme())

        fun canvasNodes(s: Scene): List<Rect> =
            s.layout.nodes.values.map {
                rect(
                    it.bounds.origin.x + padding,
                    it.bounds.origin.y + padding,
                    it.bounds.size.width,
                    it.bounds.size.height,
                )
            }

        "T1 the label sits near the satisfier end, not at the midpoint of a long path" {
            val s = scene(reqCount = 4)
            val labels = labelRects(render(s))
            val id = "satisfy:Ctrl::Req-001"
            val r = labels.getValue(id)
            val src = Point(140f + padding, 240f + padding)
            val cx = r.origin.x + r.size.width / 2f
            val cy = r.origin.y + r.size.height / 2f
            val dist = sqrt((cx - src.x) * (cx - src.x) + (cy - src.y) * (cy - src.y))
            val a = r.size.width + r.size.height // upper bound of the along extent
            dist shouldBeLessThanOrEqual (12f + a / 2f + 1 * (a + 8f) + 2f)
            (dist < 190f) shouldBe true // the midpoint of the 380 px path would be ~190 px away
        }

        "T2 the label never covers a requirement box, even a two-line one next to the bundle" {
            // A box beside the first third of the bundle, as in PAL-FB-054.
            val tall = rect(220f, 120f, 150f, 60f)
            val s = scene(reqCount = 4, extraNodes = mapOf(NodeId("Req-900") to tall))
            val svg = render(s)
            val labels = labelRects(svg)
            labels.keys.contains("satisfy:Ctrl::Req-001") shouldBe true // never dropped
            val nodes = canvasNodes(s)
            for ((id, r) in labels) {
                nodes.none { hit(r, it) } shouldBe true
                id.startsWith("satisfy:") shouldBe true
            }
        }

        "T3 the label keeps off the group frame strips" {
            val frame = rect(10f, 10f, 700f, 300f)
            val s = scene(reqCount = 2, groups = mapOf(GroupId("g1") to GroupLayout(bounds = frame)))
            val labels = labelRects(render(s))
            val f = rect(frame.origin.x + padding, frame.origin.y + padding, frame.size.width, frame.size.height)
            val d = 6f
            val strips =
                listOf(
                    rect(f.origin.x - d, f.origin.y - d, f.size.width + 2 * d, 28f + d),
                    rect(f.origin.x - d, f.origin.y + f.size.height - d, f.size.width + 2 * d, 2 * d),
                    rect(f.origin.x - d, f.origin.y - d, 2 * d, f.size.height + 2 * d),
                    rect(f.origin.x + f.size.width - d, f.origin.y - d, 2 * d, f.size.height + 2 * d),
                )
            labels.isNotEmpty() shouldBe true
            for (r in labels.values) strips.none { hit(r, it) } shouldBe true
        }
    })
