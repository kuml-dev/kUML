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
import dev.kuml.uml.UmlAssociationClass
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * End-to-end SVG rendering tests for [UmlAssociationClass] (ADR-0017 Wave D,
 * §6 of the plan): the classifier box, the association line inheriting
 * Welle-C's full navigability logic, and the dashed tether pass — all driven
 * through a hand-built [LayoutResult] where the same String ID keys both
 * `nodes` and `edges`, exactly as `UmlLayoutBridge.addAssociationClass`
 * produces it.
 */
class UmlAssociationClassSvgTest :
    FunSpec({

        val padding = 16f // KumlSvgRenderer's default diagram padding

        /**
         * Builds a minimal diagram: Party --- Tally --- District, where Tally
         * is a [UmlAssociationClass] with one attribute and one operation
         * (to exercise compartment rendering), plus a hand-built [LayoutResult]
         * registering "Tally" under both `nodes` and `edges` — and, unless
         * [includeAnchorEdge] is `false`, an extra `Tally#assocClassAnchor`
         * edge entry with NO corresponding diagram element (mirroring exactly
         * what `UmlLayoutBridge` emits).
         */
        fun buildSvg(
            sourceNavigable: Boolean = true,
            targetNavigable: Boolean = true,
            includeAnchorEdge: Boolean = true,
            extraElements: List<dev.kuml.core.model.KumlElement> = emptyList(),
            extraNodes: Map<NodeId, NodeLayout> = emptyMap(),
            extraEdges: Map<EdgeId, EdgeRoute> = emptyMap(),
        ): String {
            val party = UmlClass(id = "Party", name = "Party")
            val district = UmlClass(id = "District", name = "District")
            val tally =
                UmlAssociationClass(
                    id = "Tally",
                    name = "Tally",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Party", navigable = sourceNavigable),
                            UmlAssociationEnd(typeId = "District", navigable = targetNavigable),
                        ),
                    attributes = listOf(UmlProperty(id = "Tally::votes", name = "votes", type = UmlTypeRef(name = "Int"))),
                    operations = listOf(UmlOperation(id = "Tally::recount()", name = "recount")),
                )
            val diagram =
                KumlDiagram(
                    name = "T",
                    type = DiagramType.CLASS,
                    elements = listOf(party, district, tally) + extraElements,
                )

            val baseNodes =
                mapOf(
                    NodeId("Party") to NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 160f, height = 80f))),
                    NodeId("District") to
                        NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 300f), size = Size(width = 160f, height = 80f))),
                    NodeId("Tally") to
                        NodeLayout(bounds = Rect(origin = Point(x = 300f, y = 140f), size = Size(width = 160f, height = 100f))),
                )
            val baseEdges =
                buildMap {
                    put(
                        EdgeId("Tally"),
                        EdgeRoute.Direct(source = Point(x = 80f, y = 80f), target = Point(x = 80f, y = 300f)),
                    )
                    if (includeAnchorEdge) {
                        // No corresponding diagram element for this ID — mirrors
                        // UmlLayoutBridge's invisible gravity edge exactly.
                        put(
                            EdgeId("Tally#assocClassAnchor"),
                            EdgeRoute.Direct(source = Point(x = 300f, y = 190f), target = Point(x = 80f, y = 190f)),
                        )
                    }
                }

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 600f, height = 500f),
                    nodes = baseNodes + extraNodes,
                    edges = baseEdges + extraEdges,
                    groups = emptyMap(),
                )
            return KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layout, theme = PlainTheme())
        }

        // ── Classifier box ────────────────────────────────────────────────────

        test("the association class renders as a kuml-class box with title and compartments") {
            val svg = buildSvg()
            svg shouldContain """id="Tally""""
            svg shouldContain """class="kuml-class""""
            svg shouldContain "Tally"
            svg shouldContain "votes"
            svg shouldContain "recount"
        }

        // ── Association line (inherits Welle-C navigability) ────────────────

        test("both ends navigable draws the association line with no arrowhead") {
            val svg = buildSvg(sourceNavigable = true, targetNavigable = true)
            svg shouldContain """class="kuml-edge""""
            svg shouldNotContain "fill:none;stroke-linejoin:round"
        }

        test("target not navigable draws exactly one open-chevron arrowhead") {
            val svg = buildSvg(sourceNavigable = true, targetNavigable = false)
            Regex("""fill:none;stroke-linejoin:round""").findAll(svg).count() shouldBe 1
        }

        // ── Tether pass ───────────────────────────────────────────────────────

        test("exactly one kuml-edge-dashed tether is drawn between the route midpoint and the box border") {
            val svg = buildSvg()
            val dashedCount = Regex("""class="kuml-edge-dashed"""").findAll(svg).count()
            dashedCount shouldBe 1

            // Route: (80,80) -> (80,300), shifted by padding -> (96,96) -> (96,316).
            // Arc-length midpoint: (96, 206).
            val expectedMidX = 80f + padding
            val expectedMidY = (80f + 300f) / 2f + padding
            val tetherLine =
                Regex("""<line[^>]*class="kuml-edge-dashed"[^>]*/>""").find(svg)?.value
                    ?: error("no tether <line> found")
            tetherLine shouldContain """x1="${fmtCheck(expectedMidX)}""""
            tetherLine shouldContain """y1="${fmtCheck(expectedMidY)}""""

            // The tether must NOT carry an arrowhead marker or a label — it is a
            // plain dashed line, per the class's tether-style contract.
            tetherLine shouldNotContain "marker-end"
        }

        test("the tether's box-side endpoint lands on the border, not the box center") {
            val svg = buildSvg()
            val tetherLine =
                Regex("""<line[^>]*class="kuml-edge-dashed"[^>]*/>""").find(svg)?.value
                    ?: error("no tether <line> found")
            // Tally box: origin (300,140), size 160x100, shifted by padding -> (316,156) to (476,256).
            // Center would be (396, 206) — the tether's box-side endpoint must NOT be there
            // (mid is roughly straight left of the box, so it should land on the left border x=316).
            tetherLine shouldNotContain """x2="${fmtCheck(316f + 80f)}""" // not the center x
        }

        test("the invisible #assocClassAnchor edge is never rendered") {
            val svg = buildSvg(includeAnchorEdge = true)
            svg shouldNotContain "assocClassAnchor"
            // Only ONE edge line/path references the Tally<->District/Party geometry
            // besides the tether — i.e. no stray third edge got drawn for the anchor.
            val dashedCount = Regex("""class="kuml-edge-dashed"""").findAll(svg).count()
            dashedCount shouldBe 1
        }

        test("without the anchor edge entry at all, rendering is unaffected (defensive)") {
            val svg = buildSvg(includeAnchorEdge = false)
            svg shouldContain """class="kuml-class""""
            Regex("""class="kuml-edge-dashed"""").findAll(svg).count() shouldBe 1
        }

        // ── Combination with UmlComment/UmlCommentLink ───────────────────────

        test("an association class tether coexists with a UmlComment anchor line — both dashed lines present") {
            val comment = UmlComment(id = "note1", body = "Audited quarterly.")
            val link = UmlCommentLink(id = "link1", commentId = "note1", annotatedElementId = "Party")
            val svg =
                buildSvg(
                    extraElements = listOf(comment, link),
                    extraNodes =
                        mapOf(
                            NodeId("note1") to
                                NodeLayout(bounds = Rect(origin = Point(x = 300f, y = 0f), size = Size(width = 140f, height = 60f))),
                        ),
                    extraEdges =
                        mapOf(
                            EdgeId("link1") to EdgeRoute.Direct(source = Point(x = 300f, y = 30f), target = Point(x = 80f, y = 40f)),
                        ),
                )
            Regex("""class="kuml-edge-dashed"""").findAll(svg).count() shouldBe 2
        }

        // ── Regressions: tether pass reaching association classes the edge
        //    loop above already handles specially (self-loops, package nesting) ──

        test("a self-association-class tether targets the widened C-loop route, not ELK's raw self-loop route") {
            val employee = UmlClass(id = "Employee", name = "Employee")
            val reports =
                UmlAssociationClass(
                    id = "Reports",
                    name = "Reports",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Employee"),
                            UmlAssociationEnd(typeId = "Employee"),
                        ),
                )
            val diagram = KumlDiagram(name = "T", type = DiagramType.CLASS, elements = listOf(employee, reports))
            val nodes =
                mapOf(
                    NodeId("Employee") to
                        NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 160f, height = 80f))),
                    NodeId("Reports") to
                        NodeLayout(bounds = Rect(origin = Point(x = 400f, y = 0f), size = Size(width = 160f, height = 80f))),
                )
            // ELK's raw, cramped self-loop U-shape sits to the LEFT of the Employee
            // box — deliberately far from SelfLoopRouter's widened C-loop (32px to
            // the RIGHT of the box), so the two routes are trivially distinguishable.
            val rawSelfLoopRoute =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = -10f, y = 20f),
                    target = Point(x = -10f, y = 60f),
                    waypoints = listOf(Point(x = -30f, y = 20f), Point(x = -30f, y = 60f)),
                    cornerRadiusPx = 4f,
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 600f, height = 500f),
                    nodes = nodes,
                    edges = mapOf(EdgeId("Reports") to rawSelfLoopRoute),
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layout, theme = PlainTheme())
            val tetherLine =
                Regex("""<line[^>]*class="kuml-edge-dashed"[^>]*/>""").find(svg)?.value
                    ?: error("no tether <line> found")
            val x1 =
                Regex("""x1="(-?[0-9.]+)"""")
                    .find(tetherLine)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloat()
                    ?: error("tether <line> has no x1")
            // The C-loop sits well to the right of the Employee box (right edge
            // 160 + 32px extent + 16px padding shift) — nowhere near the raw
            // route's negative-x coordinates. If the tether pass ever regresses
            // to using the raw ELK route again, x1 falls back into single/negative digits.
            (x1 > 150f) shouldBe true
        }

        test("an association class nested inside packageOf { ... } still gets its dashed tether drawn") {
            val party = UmlClass(id = "Party", name = "Party")
            val district = UmlClass(id = "District", name = "District")
            val tally =
                UmlAssociationClass(
                    id = "Tally",
                    name = "Tally",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Party"),
                            UmlAssociationEnd(typeId = "District"),
                        ),
                )
            val pkg = UmlPackage(id = "election", name = "election", members = listOf(party, district, tally))
            val diagram = KumlDiagram(name = "T", type = DiagramType.CLASS, elements = listOf(pkg))

            val nodes =
                mapOf(
                    NodeId("Party") to
                        NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 160f, height = 80f))),
                    NodeId("District") to
                        NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 300f), size = Size(width = 160f, height = 80f))),
                    NodeId("Tally") to
                        NodeLayout(bounds = Rect(origin = Point(x = 300f, y = 140f), size = Size(width = 160f, height = 100f))),
                )
            val edges =
                mapOf(
                    EdgeId("Tally") to EdgeRoute.Direct(source = Point(x = 80f, y = 80f), target = Point(x = 80f, y = 300f)),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 600f, height = 500f),
                    nodes = nodes,
                    edges = edges,
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layout, theme = PlainTheme())
            // Before the fix, the tether pass filtered `diagram.elements` directly
            // and never saw "Tally" (nested inside the package's members, not
            // top-level) — this asserts the dashed tether is drawn regardless.
            Regex("""class="kuml-edge-dashed"""").findAll(svg).count() shouldBe 1
        }
    })

/**
 * Mirrors `fmt2` from `dev.kuml.io.svg` (private there) closely enough for
 * these whole-number test fixture coordinates: no fractional part, no
 * trailing zeros. All coordinates in this test file are deliberately chosen
 * to be integers so this simplified formatter matches exactly.
 */
private fun fmtCheck(v: Float): String {
    val rounded = kotlin.math.round(v)
    return if (rounded == v) rounded.toInt().toString() else v.toString()
}
