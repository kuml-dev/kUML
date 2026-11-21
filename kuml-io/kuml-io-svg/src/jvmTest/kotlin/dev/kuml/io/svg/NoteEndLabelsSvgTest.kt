package dev.kuml.io.svg

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.io.svg.uml.UmlAssociationLabelPlan
import dev.kuml.io.svg.uml.UmlLabelKeys
import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.erm.ErmLayoutBridge
import dev.kuml.layout.bridge.note.ErmNotePlacement
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import dev.kuml.layout.bridge.note.UmlNotePlacement
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun Rect.hits(o: Rect) =
    origin.x < o.origin.x + o.size.width &&
        o.origin.x < origin.x + size.width &&
        origin.y < o.origin.y + o.size.height &&
        o.origin.y < origin.y + size.height

private val sides = listOf(NotePlacement.ABOVE, NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)

/**
 * Nacharbeit 124, end to end with the real ELK engine: after the note placement the final label plan (computed on the
 * placed layout, notes as obstacles) must not put any end label (multiplicity, role, cardinality glyph) on a hinted note.
 * Structural geometry checks only (no byte comparison, no fonts).
 */
class NoteEndLabelsSvgTest :
    FunSpec({
        fun umlDiagram(placement: NotePlacement?) =
            KumlDiagram(
                id = "d",
                name = "D",
                type = DiagramType.CLASS,
                elements =
                    listOf(
                        UmlClass(id = "a", name = "Customer"),
                        UmlClass(id = "b", name = "Order"),
                        UmlAssociation(
                            id = "e1",
                            name = null,
                            ends =
                                listOf(
                                    UmlAssociationEnd(typeId = "a", role = "buyer", multiplicity = Multiplicity(lower = 1, upper = 1)),
                                    UmlAssociationEnd(typeId = "b", role = "orders", multiplicity = Multiplicity(lower = 0, upper = null)),
                                ),
                        ),
                        UmlComment(id = "n1", body = "About the customer", placement = placement),
                        UmlCommentLink(id = "l1", commentId = "n1", annotatedElementId = "a"),
                    ),
            )

        fun umlLayout(d: KumlDiagram): LayoutResult =
            LayoutSpecRunner
                .layout(
                    engine = ElkLayoutEngine(),
                    graph = UmlLayoutBridge.toLayoutGraph(diagram = d),
                    hints = LayoutHints.DEFAULT,
                    spec = null,
                    kind = SpecDiagramKind.UML_CLASS,
                ).result

        test("UML: no end label of the final plan lies on a hinted note, and the end labels exist") {
            var checked = 0
            for (side in sides) {
                val d = umlDiagram(side)
                val raw = umlLayout(d)
                val placed =
                    NotePlacementMissCollector
                        .collecting {
                            UmlNotePlacement.place(
                                diagram = d,
                                layout = raw,
                                endLabelRects = KumlSvgRenderer.umlEndLabelRects(diagram = d, theme = PlainTheme()),
                            )
                        }.first.layout
                val note = placed.nodes.getValue(NodeId("n1")).bounds
                val plan = UmlAssociationLabelPlan.planFor(diagram = d, layout = placed, theme = PlainTheme())
                val ends = plan.getValue("e1").filterKeys { it != UmlLabelKeys.CENTER }
                ends.isNotEmpty() shouldBe true
                for ((_, label) in ends) {
                    note.hits(label.rect) shouldBe false
                    checked++
                }
            }
            (checked > 0) shouldBe true
        }

        test("UML: the renderer applies it, the diagram still renders note and link") {
            for (side in sides) {
                val d = umlDiagram(side)
                val svg = KumlSvgRenderer.toSvg(diagram = d, layoutResult = umlLayout(d), theme = PlainTheme())
                (svg.contains("id=\"n1\"")) shouldBe true
            }
        }

        fun ermModelOf(hint: NotePlacement?): ErmModel {
            val m =
                ermModel(name = "Shop") {
                    val c = entity(name = "Customer") { id(name = "id", type = ErmDataType.Integer(64)) }
                    val o = entity(name = "Order") { id(name = "id", type = ErmDataType.Integer(64)) }
                    c oneToMany o
                    note(text = "About the customer", c, id = "n1", placement = hint)
                    diagram(name = "Shop", notation = ErmNotation.MARTIN)
                }
            return m.copy(relationships = m.relationships.map { it.copy(sourceRole = "buyer", targetRole = "orders") })
        }

        test("ERM Martin: no role or glyph rectangle of the final plan lies on a hinted note") {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.get("elk.layered")!!
            var checked = 0
            for (side in sides) {
                val m = ermModelOf(side)
                val d = m.diagrams.single()
                val raw = engine.layout(graph = ErmLayoutBridge.toLayoutGraph(model = m, diagram = d), hints = LayoutHints.DEFAULT)
                val rels = m.relationships.associateBy { it.id }
                val placed =
                    NotePlacementMissCollector
                        .collecting {
                            ErmNotePlacement.place(
                                model = m,
                                layout = raw,
                                endLabelRects = { l -> KumlSvgRenderer.ermEndLabelRects(layoutResult = l, relationshipsById = rels) },
                            )
                        }.first.layout
                val note = placed.nodes.getValue(NodeId("n1")).bounds
                // roles and glyphs on the placed layout (the final plan the renderer draws)
                val finalRects = KumlSvgRenderer.ermEndLabelRects(layoutResult = placed, relationshipsById = rels)
                (EdgeId(m.relationships.single().id).value in finalRects) shouldBe true
                for ((_, rs) in finalRects) {
                    for (rect in rs) {
                        note.hits(rect) shouldBe false
                        checked++
                    }
                }
            }
            (checked > 0) shouldBe true
        }
    })
