package dev.kuml.io.svg

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
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
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

private fun Rect.intersects(o: Rect) =
    origin.x < o.origin.x + o.size.width &&
        o.origin.x < origin.x + size.width &&
        origin.y < o.origin.y + o.size.height &&
        o.origin.y < origin.y + size.height

private val hintSides = listOf(NotePlacement.ABOVE, NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)

/**
 * Nacharbeit 131: a hinted note avoids the centre label at its real (planned) rectangle, not at an estimate at the edge
 * midpoint. Geometry checks only, real ELK layout, no byte comparison.
 */
class NoteCentreLabelRectTest :
    FunSpec({
        fun umlDiagram(
            placement: NotePlacement?,
            name: String?,
            type: DiagramType = DiagramType.CLASS,
        ) = KumlDiagram(
            id = "d",
            name = "D",
            type = type,
            elements =
                listOf(
                    UmlClass(id = "a", name = "Customer"),
                    UmlClass(id = "b", name = "Order"),
                    UmlAssociation(
                        id = "e1",
                        name = name,
                        ends = listOf(UmlAssociationEnd(typeId = "a"), UmlAssociationEnd(typeId = "b")),
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

        fun place(
            d: KumlDiagram,
            raw: LayoutResult,
            centre: ((LayoutResult) -> Map<String, Rect?>)?,
        ): LayoutResult =
            NotePlacementMissCollector
                .collecting {
                    if (centre == null) {
                        UmlNotePlacement.place(diagram = d, layout = raw)
                    } else {
                        UmlNotePlacement.place(diagram = d, layout = raw, centreLabelRects = centre)
                    }
                }.first.layout

        fun noteOf(l: LayoutResult) = l.nodes.getValue(NodeId("n1")).bounds

        fun midpoint(l: LayoutResult): Point {
            val r =
                l.edges.entries
                    .single { it.key.value == "e1" }
                    .value
            return Point(x = (r.source.x + r.target.x) / 2f, y = (r.source.y + r.target.y) / 2f)
        }

        test("UML (a): the note avoids a stubbed real centre rect, 30 px away from the midpoint") {
            for (side in hintSides) {
                val d = umlDiagram(side, name = "places")
                val raw = umlLayout(d)
                val mid = midpoint(raw)
                val rect = Rect(origin = Point(x = mid.x + 30f, y = mid.y + 30f), size = Size(width = 60f, height = 15f))
                val placed = place(d, raw, centre = { _ -> mapOf("e1" to rect) })
                noteOf(placed).intersects(rect) shouldBe false
            }
        }

        test("UML (a): a null entry drops the estimate, equal to the diagram without a name") {
            for (side in hintSides) {
                val named = umlDiagram(side, name = "places")
                val unnamed = umlDiagram(side, name = null)
                val raw = umlLayout(named)
                val withNull = place(named, raw, centre = { _ -> mapOf("e1" to null) })
                val plain = place(unnamed, raw, centre = null)
                noteOf(withNull) shouldBe noteOf(plain)
            }
        }

        test("UML (b): no label at all gives the same result with and without the callback") {
            for (side in hintSides) {
                val d = umlDiagram(side, name = null)
                val raw = umlLayout(d)
                val real = place(d, raw, centre = KumlSvgRenderer.umlCentreLabelRects(diagram = d, theme = PlainTheme()))
                noteOf(real) shouldBe noteOf(place(d, raw, centre = null))
            }
        }

        test("UML (c): a component diagram has no plan, so no centre rects are passed") {
            val d = umlDiagram(NotePlacement.ABOVE, name = "places", type = DiagramType.COMPONENT)
            val raw = umlLayout(d)
            KumlSvgRenderer.umlCentreLabelRects(diagram = d, theme = PlainTheme())(raw) shouldBe emptyMap()
            noteOf(place(d, raw, centre = KumlSvgRenderer.umlCentreLabelRects(diagram = d, theme = PlainTheme()))) shouldBe
                noteOf(place(d, raw, centre = null))
        }

        test("UML: a named association yields a real centre rect for its edge") {
            val d = umlDiagram(NotePlacement.ABOVE, name = "places")
            val raw = umlLayout(d)
            val rects = KumlSvgRenderer.umlCentreLabelRects(diagram = d, theme = PlainTheme())(raw)
            (rects["e1"] != null) shouldBe true
        }

        test("UML (e): the plan centre rect is where the centre label text is drawn") {
            val d = umlDiagram(null, name = "places")
            val raw = umlLayout(d)
            val rect = KumlSvgRenderer.umlCentreLabelRects(diagram = d, theme = PlainTheme())(raw).getValue("e1")!!
            val opts = SvgRenderOptions.DEFAULT
            val svg = KumlSvgRenderer.toSvg(diagram = d, layoutResult = raw, theme = PlainTheme(), options = opts)
            val m = Regex("""<text[^>]*x="([\d.]+)"[^>]*y="([\d.]+)"[^>]*>\s*places\s*</text>""").find(svg)
            (m != null) shouldBe true
            val x = m!!.groupValues[1].toFloat()
            val y = m.groupValues[2].toFloat()
            val p = opts.paddingPx
            (abs(x - (rect.origin.x + rect.size.width / 2f + p)) <= 0.5f) shouldBe true
            (y >= rect.origin.y + p - 0.5f && y <= rect.origin.y + rect.size.height + p + 0.5f) shouldBe true
        }

        fun ermModelOf(
            hint: NotePlacement?,
            name: String?,
        ): ErmModel {
            val m =
                ermModel(name = "Shop") {
                    val c = entity(name = "Customer") { id(name = "id", type = ErmDataType.Integer(64)) }
                    val o = entity(name = "Order") { id(name = "id", type = ErmDataType.Integer(64)) }
                    c oneToMany o
                    note(text = "About the customer", c, id = "n1", placement = hint)
                    diagram(name = "Shop", notation = ErmNotation.MARTIN)
                }
            return m.copy(relationships = m.relationships.map { it.copy(name = name) })
        }

        fun ermLayout(m: ErmModel): LayoutResult {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.get("elk.layered")!!
            return engine.layout(
                graph = ErmLayoutBridge.toLayoutGraph(model = m, diagram = m.diagrams.single()),
                hints = LayoutHints.DEFAULT,
            )
        }

        fun placeErm(
            m: ErmModel,
            raw: LayoutResult,
            centre: ((LayoutResult) -> Map<String, Rect?>)?,
        ): LayoutResult =
            NotePlacementMissCollector
                .collecting {
                    if (centre == null) {
                        ErmNotePlacement.place(model = m, layout = raw)
                    } else {
                        ErmNotePlacement.place(model = m, layout = raw, centreLabelRects = centre)
                    }
                }.first.layout

        test("ERM (d): the note avoids a stubbed real centre rect") {
            for (side in hintSides) {
                val m = ermModelOf(side, name = "places")
                val raw = ermLayout(m)
                val rid = m.relationships.single().id
                val r =
                    raw.edges.entries
                        .single { it.key.value == rid }
                        .value
                val mx = (r.source.x + r.target.x) / 2f
                val my = (r.source.y + r.target.y) / 2f
                val rect = Rect(origin = Point(x = mx + 30f, y = my + 30f), size = Size(width = 60f, height = 15f))
                val placed = placeErm(m, raw, centre = { _ -> mapOf(rid to rect) })
                placed.nodes
                    .getValue(NodeId("n1"))
                    .bounds
                    .intersects(rect) shouldBe false
            }
        }

        test("ERM (d): null entry equals the model without a name; no label equals without callback") {
            for (side in hintSides) {
                val named = ermModelOf(side, name = "places")
                val unnamed = ermModelOf(side, name = null)
                val raw = ermLayout(named)
                val rid = named.relationships.single().id
                val withNull = placeErm(named, raw, centre = { _ -> mapOf(rid to null) })
                noteOf(withNull) shouldBe noteOf(placeErm(unnamed, raw, centre = null))
                val real =
                    placeErm(
                        unnamed,
                        raw,
                        centre = { l ->
                            KumlSvgRenderer.ermCentreLabelRects(
                                layoutResult = l,
                                relationshipsById = unnamed.relationships.associateBy { it.id },
                            )
                        },
                    )
                noteOf(real) shouldBe noteOf(placeErm(unnamed, raw, centre = null))
            }
        }

        test("ERM (e): the plan centre rect is where the relationship name is drawn") {
            val m = ermModelOf(null, name = "places")
            val raw = ermLayout(m)
            val rid = m.relationships.single().id
            val rect =
                KumlSvgRenderer
                    .ermCentreLabelRects(
                        layoutResult = raw,
                        relationshipsById =
                            m.relationships.associateBy {
                                it.id
                            },
                    ).getValue(rid)!!
            val svg =
                KumlSvgRenderer.toSvg(
                    model = m,
                    diagram = m.diagrams.single(),
                    layoutResult = raw,
                    theme = PlainTheme(),
                    notation = ErmNotation.MARTIN,
                )
            val mt = Regex("""<text[^>]*x="([\d.]+)"[^>]*y="([\d.]+)"[^>]*>\s*places\s*</text>""").find(svg)
            (mt != null) shouldBe true
            val x = mt!!.groupValues[1].toFloat()
            val p = SvgRenderOptions.DEFAULT.paddingPx
            (abs(x - (rect.origin.x + rect.size.width / 2f + p)) <= 0.5f) shouldBe true
        }
    })
