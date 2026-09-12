package dev.kuml.io.svg

import dev.kuml.blueprint.model.ConnectionStyle
import dev.kuml.bpmn.model.ChoreographySequenceFlow
import dev.kuml.bpmn.model.ConversationLink
import dev.kuml.c4.model.C4Interaction
import dev.kuml.c4.model.C4Relationship
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.io.svg.blueprint.edge.renderConnection
import dev.kuml.io.svg.bpmn.renderChoreoSequenceFlow
import dev.kuml.io.svg.bpmn.renderConversationLink
import dev.kuml.io.svg.c4.renderC4Interaction
import dev.kuml.io.svg.erm.ConnectorEntitySide
import dev.kuml.io.svg.erm.renderChenConnector
import dev.kuml.io.svg.erm.renderErmBachmanRelationship
import dev.kuml.io.svg.erm.renderErmIdef1xRelationship
import dev.kuml.io.svg.erm.renderErmMartinRelationship
import dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer
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
import dev.kuml.sysml2.edge.Sysml2ArrowHead
import dev.kuml.sysml2.edge.Sysml2EdgeMetadata
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlGeneralization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * fix/edge-svg-element-ids — regression coverage for the `<g id="…">` wrapper
 * every edge/relationship renderer now emits around its rendered fragment.
 *
 * Before this fix, only *node* renderers wrapped their output in an
 * identified `<g>` group (see [NodeRendererDispatcher] and e.g.
 * [dev.kuml.io.svg.uml.UmlClassSvg]); edges rendered as bare `<line>`/`<path>`
 * elements with no addressable id, so consumers like kUML Portal's
 * click-to-select could select nodes but never edges.
 *
 * These tests exercise the renderer functions directly (they are `internal`,
 * reachable from this test source set the same way many existing tests
 * already call `renderErmMartinRelationship` etc.) rather than only through
 * full-diagram [KumlSvgRenderer.toSvg] fixtures — this keeps each test
 * minimal and pins the id on the *specific* renderer that owns it.
 */
class EdgeSvgElementIdTest :
    FunSpec({

        val theme = PlainTheme()
        val directRoute = EdgeRoute.Direct(source = Point(x = 0f, y = 0f), target = Point(x = 100f, y = 0f))

        // ── EdgeRendererDispatcher (UML / C4-static / BPMN Process — V1.1) ──

        test("EdgeRendererDispatcher wraps a UmlAssociation edge in <g id>") {
            val assoc = UmlAssociation(id = "assoc::A-B", ends = listOf(UmlAssociationEnd(typeId = "A"), UmlAssociationEnd(typeId = "B")))
            val b = SvgBuilder(pretty = false)
            EdgeRendererDispatcher.dispatch(relationship = assoc, route = directRoute, theme = theme, builder = b)
            b.toString() shouldContain "<g id=\"${assoc.id}\">"
        }

        test("EdgeRendererDispatcher wraps a UmlGeneralization edge in <g id>") {
            val gen = UmlGeneralization(id = "gen::A-B", specificId = "A", generalId = "B")
            val b = SvgBuilder(pretty = false)
            EdgeRendererDispatcher.dispatch(relationship = gen, route = directRoute, theme = theme, builder = b)
            b.toString() shouldContain "<g id=\"${gen.id}\">"
        }

        test("EdgeRendererDispatcher wraps a UmlCommentLink edge in <g id>") {
            val link = UmlCommentLink(id = "noteanchor::c1-o1", commentId = "c1", annotatedElementId = "o1")
            val b = SvgBuilder(pretty = false)
            EdgeRendererDispatcher.dispatch(relationship = link, route = directRoute, theme = theme, builder = b)
            b.toString() shouldContain "<g id=\"${xmlEscapeAttr(link.id)}\">"
        }

        test("EdgeRendererDispatcher wraps a C4Relationship edge in <g id>") {
            val rel = C4Relationship(id = "rel1", source = "sys1", target = "sys2", label = "calls")
            val b = SvgBuilder(pretty = false)
            EdgeRendererDispatcher.dispatch(relationship = rel, route = directRoute, theme = theme, builder = b)
            b.toString() shouldContain "<g id=\"${xmlEscapeAttr(rel.id)}\">"
        }

        test("EdgeRendererDispatcher wraps the else/fallback branch in <g id>") {
            val unknown =
                object : KumlElement {
                    override val id: String = "unknown-edge-1"
                    override val metadata: Map<String, KumlMetaValue> = emptyMap()
                }
            val b = SvgBuilder(pretty = false)
            EdgeRendererDispatcher.dispatch(relationship = unknown, route = directRoute, theme = theme, builder = b)
            b.toString() shouldContain "<g id=\"unknown-edge-1\">"
        }

        test("EdgeRendererDispatcher escapes special characters in the relationship id (XSS guard)") {
            val malicious = C4Relationship(id = "\"><script>alert(1)</script>", source = "a", target = "b", label = "x")
            val b = SvgBuilder(pretty = false)
            EdgeRendererDispatcher.dispatch(relationship = malicious, route = directRoute, theme = theme, builder = b)
            val svg = b.toString()
            svg shouldNotContain "<script>alert(1)</script>"
        }

        // ── ERM — Martin / Bachman / IDEF1X (each rel gets exactly one <g>) ──

        fun ermRel(id: String) =
            ErmRelationship(
                id = id,
                name = "has",
                sourceEntityId = "e1",
                targetEntityId = "e2",
                sourceCardinality = Cardinality.ONE,
                targetCardinality = Cardinality.ZERO_MANY,
            )

        test("renderErmMartinRelationship wraps its output in <g id=rel.id>") {
            val rel = ermRel("rel-martin-1")
            val b = SvgBuilder(pretty = false)
            renderErmMartinRelationship(rel = rel, route = directRoute, theme = theme, b = b)
            b.toString() shouldContain "<g id=\"rel-martin-1\">"
        }

        test("renderErmBachmanRelationship wraps its output in <g id=rel.id>") {
            val rel = ermRel("rel-bachman-1")
            val b = SvgBuilder(pretty = false)
            renderErmBachmanRelationship(rel = rel, route = directRoute, theme = theme, b = b)
            b.toString() shouldContain "<g id=\"rel-bachman-1\">"
        }

        test("renderErmIdef1xRelationship wraps its output in <g id=rel.id>") {
            val rel = ermRel("rel-idef1x-1")
            val b = SvgBuilder(pretty = false)
            renderErmIdef1xRelationship(rel = rel, route = directRoute, theme = theme, b = b)
            b.toString() shouldContain "<g id=\"rel-idef1x-1\">"
        }

        // ── ERM — Chen (two diamond-connector halves must get DISTINCT ids) ──

        test("renderChenConnector wraps each call in <g id> using the caller-supplied synthetic id") {
            val b = SvgBuilder(pretty = false)
            renderChenConnector(
                id = "reledge-src::rel1",
                route = directRoute,
                cardinality = Cardinality.ONE,
                b = b,
                entitySide = ConnectorEntitySide.SOURCE,
            )
            renderChenConnector(
                id = "reledge-tgt::rel1",
                route = directRoute,
                cardinality = Cardinality.ZERO_MANY,
                b = b,
                entitySide = ConnectorEntitySide.TARGET,
            )
            val svg = b.toString()
            svg shouldContain "<g id=\"reledge-src::rel1\">"
            svg shouldContain "<g id=\"reledge-tgt::rel1\">"
            // The whole point of the fix: the source-side and target-side halves of
            // the SAME ErmRelationship must not collide on one shared <g id>.
            val ids = Regex("""<g id="([^"]*)">""").findAll(svg).map { it.groupValues[1] }.toList()
            ids.distinct() shouldBe ids
        }

        // ── C4 Dynamic Diagram interaction ──

        test("renderC4Interaction wraps its output in <g id=interaction.id>") {
            val interaction = C4Interaction(id = "interaction-1", source = "a", target = "b", description = "Submit order", sequence = 1)
            val b = SvgBuilder(pretty = false)
            renderC4Interaction(interaction = interaction, route = directRoute, theme = theme, builder = b)
            b.toString() shouldContain "<g id=\"interaction-1\">"
        }

        // ── BPMN Choreography / Conversation ──

        test("renderChoreoSequenceFlow wraps its output in <g id=flow.id>") {
            val flow = ChoreographySequenceFlow(id = "choreo-flow-1", sourceRef = "t1", targetRef = "t2")
            val b = SvgBuilder(pretty = false)
            renderChoreoSequenceFlow(flow = flow, route = directRoute, builder = b, theme = theme)
            b.toString() shouldContain "<g id=\"choreo-flow-1\">"
        }

        test("renderConversationLink wraps its output in <g id=link.id>") {
            val link = ConversationLink(id = "conv-link-1", participantRef = "p1", conversationNodeRef = "n1")
            val b = SvgBuilder(pretty = false)
            renderConversationLink(link = link, route = directRoute, builder = b, theme = theme)
            b.toString() shouldContain "<g id=\"conv-link-1\">"
        }

        // ── Service Blueprint connection ──

        test("Blueprint renderConnection wraps its output in <g id=conn.id>") {
            val b = SvgBuilder(pretty = false)
            b.renderConnection(id = "conn-1", from = 0.0 to 0.0, to = 100.0 to 0.0, style = ConnectionStyle.SOLID)
            b.toString() shouldContain "<g id=\"conn-1\">"
        }

        // ── SysML 2 synthetic/adapter-metadata edges ──

        test("Sysml2EdgeRenderer.render wraps its output in <g id> using the caller-supplied edge id") {
            val meta = Sysml2EdgeMetadata(stereotype = null, label = null, dashArray = null, arrowHead = Sysml2ArrowHead.OpenAngle)
            val b = SvgBuilder(pretty = false)
            Sysml2EdgeRenderer.render(id = "sysml2-edge-1", route = directRoute, metadata = meta, theme = theme, builder = b)
            b.toString() shouldContain "<g id=\"sysml2-edge-1\">"
        }

        // ── Full-diagram regression: no duplicate ids across a real render ──

        test("KumlSvgRenderer.toSvg: node ids and edge ids never collide in one document") {
            val cls1 = UmlClass(id = "cls1", name = "Order")
            val cls2 = UmlClass(id = "cls2", name = "Customer")
            val assoc = UmlAssociation(id = "assoc1", ends = listOf(UmlAssociationEnd(typeId = "cls1"), UmlAssociationEnd(typeId = "cls2")))
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls1, cls2, assoc))
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 400f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("cls1") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 40f), size = Size(width = 120f, height = 80f))),
                            NodeId("cls2") to
                                NodeLayout(bounds = Rect(origin = Point(x = 200f, y = 40f), size = Size(width = 120f, height = 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("assoc1") to
                                EdgeRoute.Direct(source = Point(x = 140f, y = 80f), target = Point(x = 200f, y = 80f)),
                        ),
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layoutResult, theme = theme)

            svg shouldContain "<g id=\"cls1\""
            svg shouldContain "<g id=\"cls2\""
            svg shouldContain "<g id=\"assoc1\">"

            val ids = Regex("""id="([^"]*)"""").findAll(svg).map { it.groupValues[1] }.toList()
            ids.distinct().size shouldBe ids.size
        }
    })
