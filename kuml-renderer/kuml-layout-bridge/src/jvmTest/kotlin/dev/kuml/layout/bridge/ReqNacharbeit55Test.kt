package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutLimits
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.GroupId
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutCapabilities
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.Sysml2NotePlacement
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.ReqDiagramBuilder
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** The grid never asks the engine: if it were not applied, this engine fails the test. */
private object Nach55NoEngine : KumlLayoutEngine {
    override val id = LayoutEngineId("none")
    override val capabilities =
        LayoutCapabilities(
            deterministic = true,
            supportedDiagramKinds = setOf(DiagramKind.Generic),
            supportedEdgeStyles = setOf(EdgeRouteStyle.Direct),
            respectsGridHints = false,
            respectsRelativeConstraints = false,
            maxRecommendedNodes = 100,
            supportsLayerHints = false,
        )

    override fun layout(
        graph: LayoutGraph,
        hints: LayoutHints,
    ): LayoutResult = error("the engine must not be called when the group grid applies")
}

private class Laid(
    val model: Sysml2Model,
    val diagram: ReqDiagram,
    val graph: LayoutGraph,
    val result: LayoutResult,
    val findings: List<String>,
) {
    fun frame(name: String): Rect = result.groups.getValue(GroupId("group:$name")).bounds

    fun card(name: String): Rect = result.nodes.getValue(NodeId(name)).bounds
}

private fun laidOut(model: Sysml2Model): Laid {
    val diagram = model.diagrams.filterIsInstance<ReqDiagram>().single()
    val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
    val outcome =
        LayoutSpecRunner.layout(
            engine = Nach55NoEngine,
            graph = graph,
            hints = LayoutHints(),
            spec = diagram.layout,
            kind = SpecDiagramKind.SYSML2_REQ,
        )
    return Laid(model = model, diagram = diagram, graph = graph, result = outcome.result, findings = outcome.findings.map { it.code })
}

private fun Rect.right() = origin.x + size.width

private fun Rect.bottom() = origin.y + size.height

private fun Rect.overlaps(o: Rect) = origin.x < o.right() && o.origin.x < right() && origin.y < o.bottom() && o.origin.y < bottom()

private fun Rect.inside(o: Rect) = origin.x >= o.origin.x && origin.y >= o.origin.y && right() <= o.right() && bottom() <= o.bottom()

/** The four strips of [ReqNoteRoom.BAND_PX] on each side of the frame line. */
private fun bandOf(f: Rect): List<Rect> {
    val b = ReqNoteRoom.BAND_PX
    val x = f.origin.x
    val y = f.origin.y
    return listOf(
        Rect(origin = Point(x = x - b, y = y - b), size = Size(width = f.size.width + 2 * b, height = 2 * b)),
        Rect(origin = Point(x = x - b, y = f.bottom() - b), size = Size(width = f.size.width + 2 * b, height = 2 * b)),
        Rect(origin = Point(x = x - b, y = y - b), size = Size(width = 2 * b, height = f.size.height + 2 * b)),
        Rect(origin = Point(x = f.right() - b, y = y - b), size = Size(width = 2 * b, height = f.size.height + 2 * b)),
    )
}

/** Nacharbeit 55 - frames that never overlap and the duplicate membership finding (g), note room in group frames (e). Geometry only. */
class ReqNacharbeit55Test :
    FunSpec({
        timeout = 60.seconds.inWholeMilliseconds

        fun nested(builder: ReqDiagramBuilder.(List<dev.kuml.sysml2.RequirementDefinition>) -> Unit) =
            sysml2Model(name = "M") {
                val reqs = (1..8).map { requirementDef(name = "R$it", text = "r$it") }
                reqDiagram(name = "D") { builder(reqs) }
            }

        val nestedModel =
            nested { r ->
                group(name = "1.1 und 1.2") {
                    include(r[0])
                    include(r[1])
                    group(name = "Sub A") {
                        include(r[2])
                        include(r[3])
                    }
                    group(name = "Sub B") { include(r[4]) }
                }
                group(name = "Other") {
                    include(r[5])
                    include(r[6])
                    include(r[7])
                    layout { wrapColumns = 1 }
                }
                layout { wrapColumns = 2 }
            }

        test("(g) sibling frames are disjoint by the gutter, child frames sit inside the parent minus its padding") {
            val t = laidOut(nestedModel)
            val a = t.frame("1.1 und 1.2")
            val b = t.frame("Other")
            val gutter = LayoutLimits.GRID_GUTTER_PX
            val apart = maxOf(b.origin.x - a.right(), a.origin.x - b.right(), b.origin.y - a.bottom(), a.origin.y - b.bottom())
            apart shouldBeGreaterThanOrEqual gutter - 0.01f
            val subA = t.frame("1.1 und 1.2/Sub A")
            val subB = t.frame("1.1 und 1.2/Sub B")
            subA.overlaps(subB) shouldBe false
            val pad = Sysml2LayoutBridge.REQ_GROUP_PADDING
            for (child in listOf(subA, subB)) {
                (child.origin.x >= a.origin.x + pad.left - 0.01f) shouldBe true
                (child.origin.y >= a.origin.y + pad.top - 0.01f) shouldBe true
                (child.right() <= a.right() - pad.right + 0.01f) shouldBe true
                (child.bottom() <= a.bottom() - pad.bottom + 0.01f) shouldBe true
            }
        }

        test("(g) no frame contains a card outside its own subtree") {
            val t = laidOut(nestedModel)

            fun subtree(id: GroupId): Set<GroupId> =
                setOf(id) +
                    t.graph.groups
                        .filter { it.parent == id }
                        .flatMap { subtree(it.id) }
            for (g in t.graph.groups) {
                val own = subtree(g.id)
                val frame =
                    t.result.groups
                        .getValue(g.id)
                        .bounds
                for (n in t.graph.nodes) {
                    val card =
                        t.result.nodes
                            .getValue(n.id)
                            .bounds
                    if (n.groupId in own) {
                        card.inside(frame) shouldBe true
                    } else {
                        card.overlaps(frame) shouldBe false
                    }
                }
            }
        }

        test("(g) a requirement in two groups stands in the first one only and the layout says so") {
            val model =
                nested { r ->
                    group(name = "A") {
                        include(r[0])
                        include(r[1])
                    }
                    group(name = "B") {
                        include(r[0])
                        include(r[2])
                    }
                    layout { wrapColumns = 2 }
                }
            val t = laidOut(model)
            t.graph.nodes
                .single { it.id == NodeId("R1") }
                .groupId shouldBe GroupId("group:A")
            t.graph.ignoredMemberships
                .single()
                .ignoredGroup shouldBe GroupId("group:B")
            t.findings shouldContain LayoutCodes.DUPLICATE_GROUP_MEMBERSHIP
            t.card("R1").inside(t.frame("A")) shouldBe true
            t.card("R1").overlaps(t.frame("B")) shouldBe false
        }

        fun withNotes() =
            sysml2Model(name = "M") {
                val reqs = (1..6).map { requirementDef(name = "R$it", text = "r$it") }
                comment(text = "Only about the first group, with a few more words so it wraps", firstAnchor = reqs[2], id = "own")
                comment(text = "Second owned note of the first group", firstAnchor = reqs[1], id = "own2")
                comment(text = "About two groups", firstAnchor = reqs[1], moreAnchors = arrayOf(reqs[4]), id = "cross")
                reqDiagram(name = "D") {
                    group(name = "G1") {
                        include(reqs[0])
                        include(reqs[1])
                        include(reqs[2])
                    }
                    group(name = "G2") {
                        include(reqs[3])
                        include(reqs[4])
                        include(reqs[5])
                    }
                    layout { wrapColumns = 2 }
                }
            }

        fun placed(t: Laid): LayoutResult {
            val out = Sysml2NotePlacement.place(model = t.model, diagram = t.diagram, layout = t.result).layout
            return out
        }

        test("(e) no note covers a group frame line (8 px band on each side)") {
            val t = laidOut(withNotes())
            val out = placed(t)
            for (id in listOf("own", "own2", "cross")) {
                val note = out.nodes.getValue(NodeId(id)).bounds
                for (f in out.groups.values) {
                    for (strip in bandOf(f.bounds)) note.overlaps(strip) shouldBe false
                }
            }
        }

        test("(e) notes owned by one group stand inside its frame and the frame reserved the room") {
            val t = laidOut(withNotes())
            val g1 = t.graph.groups.single { it.id == GroupId("group:G1") }
            (g1.noteStrip != null) shouldBe true
            t.graph.groups
                .single { it.id == GroupId("group:G2") }
                .noteStrip shouldBe null
            val out = placed(t)
            val frame = out.groups.getValue(GroupId("group:G1")).bounds
            for (id in listOf("own", "own2")) {
                out.nodes
                    .getValue(NodeId(id))
                    .bounds
                    .inside(frame) shouldBe true
            }
            // the owned notes do not cover a card or each other
            val a = out.nodes.getValue(NodeId("own")).bounds
            val b = out.nodes.getValue(NodeId("own2")).bounds
            a.overlaps(b) shouldBe false
            for (c in listOf("R1", "R2", "R3")) {
                a.overlaps(out.nodes.getValue(NodeId(c)).bounds) shouldBe false
                b.overlaps(out.nodes.getValue(NodeId(c)).bounds) shouldBe false
            }
        }

        test("(e) a note with anchors in two groups reserves no room in either frame") {
            val t = laidOut(withNotes())
            ReqNoteRoom
                .ownedNotes(
                    model = t.model,
                    diagram = t.diagram,
                    groupOfNode =
                        ReqNoteRoom
                            .membership(
                                diagram = t.diagram,
                                liveGroupIds =
                                    t.graph.groups
                                        .map { it.id.value }
                                        .toSet(),
                                visibleNodeIds =
                                    t.graph.nodes
                                        .map { it.id.value }
                                        .toSet(),
                            ).groupOfNode,
                    visibleNodeIds =
                        t.graph.nodes
                            .map { it.id.value }
                            .toSet(),
                ).values
                .flatten()
                .map { it.first } shouldHaveSize 2
        }
    })
