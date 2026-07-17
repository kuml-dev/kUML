package dev.kuml.io.svg.uml

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.io.svg.SvgRenderOptions
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
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlInstanceSpecification
import dev.kuml.uml.UmlLink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.sqrt

/**
 * Regression coverage for the converging-endpoint label-overlap bug
 * (fix/uml-association-label-overlap), reported from a real Obsidian vault
 * kUML example: four associations (`Verein`, `Vorstand`, `Kassenpruefung`,
 * `Mitgliederversammlung`) converge on one target class (`Mitglied`), each
 * carrying a role name + multiplicity at the `Mitglied` end. Pre-fix, all
 * four role-name labels landed on the exact same fixed 30 px-along-edge /
 * 10 px-perpendicular offset from `route.target` — since the four routes'
 * target points sit only a few px apart on `Mitglied`'s border (ELK packs
 * multiple incoming anchors on a compact node tightly), the labels piled on
 * top of each other into unreadable overlapping text.
 *
 * This is the identical bug class already fixed for ERM/Chen cardinality
 * labels converging on a hub entity (`ErmChenSvgTest`, "cardinality labels
 * on a converging hub clear the entity box, come off the line, and fan
 * apart") — this test mirrors that test's structure (pairwise-distance +
 * determinism assertions) for the UML class-diagram analog.
 *
 * `UmlAssociationDecorationSvgTest` (single association, no siblings) stays
 * green and unmodified by this fix: with only one sibling per (node, face)
 * bucket, `stackIndex` is always `0`, and `stackIndex == 0` reproduces the
 * pre-fix geometry byte-for-byte (see `renderUmlAssociation`'s KDoc).
 */
class UmlConvergingAssociationLabelsSvgTest :
    FunSpec({

        /** One `<text class="kuml-small" ...>` element (the coloured pass, not its halo twin). */
        data class SmallLabel(val x: Float, val y: Float, val text: String)

        fun smallLabels(svg: String): List<SmallLabel> {
            val regex =
                Regex("""<text class="kuml-small" x="([^"]+)" y="([^"]+)" text-anchor="middle">([^<]*)</text>""")
            return regex.findAll(svg).map { m ->
                val (x, y, text) = m.destructured
                SmallLabel(x.toFloat(), y.toFloat(), text)
            }.toList()
        }

        fun distance(
            a: SmallLabel,
            b: SmallLabel,
        ): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return sqrt(dx * dx + dy * dy)
        }

        test("four associations converging on one target class fan their role/multiplicity labels apart") {
            val mitglied =
                UmlClass(
                    id = "Mitglied",
                    name = "Mitglied",
                    attributes = emptyList(),
                )
            val verein = UmlClass(id = "Verein", name = "Verein")
            val vorstand = UmlClass(id = "Vorstand", name = "Vorstand")
            val kassenpruefung = UmlClass(id = "Kassenpruefung", name = "Kassenpruefung")
            val mv = UmlClass(id = "Mitgliederversammlung", name = "Mitgliederversammlung")

            fun assoc(
                id: String,
                sourceId: String,
                role: String,
                lower: Int,
                upper: Int?,
            ) = UmlAssociation(
                id = id,
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = sourceId, multiplicity = Multiplicity(lower = 1, upper = 1)),
                        UmlAssociationEnd(
                            typeId = "Mitglied",
                            role = role,
                            multiplicity = Multiplicity(lower = lower, upper = upper),
                        ),
                    ),
            )

            val a1 = assoc("assocVerein", "Verein", "mitglieder", 7, null)
            val a2 = assoc("assocVorstand", "Vorstand", "vorstandsmitglieder", 2, 5)
            val a3 = assoc("assocKassenpruefung", "Kassenpruefung", "kassenpruefer", 2, 2)
            val a4 = assoc("assocMv", "Mitgliederversammlung", "stimmberechtigte", 1, null)

            val diagram =
                KumlDiagram(
                    name = "Organe und Mitgliedschaft",
                    type = DiagramType.CLASS,
                    elements = listOf(mitglied, verein, vorstand, kassenpruefung, mv, a1, a2, a3, a4),
                )

            // Mitglied box: x in [300, 520], y in [40, 150]. All four routes come
            // from below (y = 300) and land on the bottom border (y = 150) at
            // slightly different x — the exact scenario ELK produces for a
            // compact hub node: distinct-but-close anchor points, all with a
            // near-vertical, upward-pointing final segment (target tangent
            // bucket "N").
            val mitgliedBounds = Rect(Point(300f, 40f), Size(220f, 110f))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(600f, 400f),
                    nodes = mapOf(NodeId("Mitglied") to NodeLayout(bounds = mitgliedBounds)),
                    edges =
                        mapOf(
                            EdgeId("assocVerein") to EdgeRoute.Direct(Point(345f, 300f), Point(335f, 150f)),
                            EdgeId("assocVorstand") to EdgeRoute.Direct(Point(385f, 300f), Point(375f, 150f)),
                            EdgeId("assocKassenpruefung") to EdgeRoute.Direct(Point(425f, 300f), Point(415f, 150f)),
                            EdgeId("assocMv") to EdgeRoute.Direct(Point(465f, 300f), Point(455f, 150f)),
                        ),
                    groups = emptyMap(),
                )

            // Non-pretty output so label text sits directly between its tag
            // brackets (`>mitglieder<`) — with pretty-printing the text would be
            // on its own indented line, making the extraction regex unreliable.
            val options = SvgRenderOptions(prettyPrint = false, paddingPx = 0f)
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme(), options)

            // All four role names and multiplicities are present — none dropped.
            svg shouldContain "mitglieder"
            svg shouldContain "vorstandsmitglieder"
            svg shouldContain "kassenpruefer"
            svg shouldContain "stimmberechtigte"
            svg shouldContain "7..*"
            svg shouldContain "2..5"
            svg shouldContain ">2<"
            svg shouldContain "1..*"

            val roleNames = setOf("mitglieder", "vorstandsmitglieder", "kassenpruefer", "stimmberechtigte")
            val roleLabels = smallLabels(svg).filter { it.text in roleNames }
            roleLabels shouldHaveSize 4

            // Assertion 1 — hub separation: no two of the four converging
            // role-name labels land on top of (or very near) each other. Fails
            // on pre-fix master: all four collapse to the same fixed 30 px/
            // 10 px offset from their (nearly identical) route.target.
            for (i in roleLabels.indices) {
                for (j in i + 1 until roleLabels.size) {
                    val dist = distance(roleLabels[i], roleLabels[j])
                    (dist >= 12f) shouldBe true
                }
            }

            // Assertion 2 — labels clear the Mitglied box: the along-edge
            // offset for a TARGET end walks backward from `route.target`
            // along the incoming tail (back toward the source, i.e. further
            // DOWN since the source classes sit below Mitglied) — so every
            // role-name label must sit strictly BELOW the box's bottom
            // border (y > 150), in the whitespace between Mitglied and the
            // source classes, never on top of the title inside the box.
            for (l in roleLabels) {
                (l.y > mitgliedBounds.origin.y + mitgliedBounds.size.height) shouldBe true
            }

            // Assertion 3 — determinism: identical output across renders,
            // guarding the sort-by-edge-id stability of the stack-index
            // assignment.
            val again = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme(), options)
            svg shouldBe again

            SampleOutput.write("uml/converging-association-labels.svg", svg)
        }

        test("three links converging on one instance fan their role labels apart") {
            val order = UmlInstanceSpecification(id = "order1", name = "order1", classifierId = "Order", classifierName = "Order")
            val item1 =
                UmlInstanceSpecification(id = "item1", name = "item1", classifierId = "OrderItem", classifierName = "OrderItem")
            val item2 =
                UmlInstanceSpecification(id = "item2", name = "item2", classifierId = "OrderItem", classifierName = "OrderItem")
            val item3 =
                UmlInstanceSpecification(id = "item3", name = "item3", classifierId = "OrderItem", classifierName = "OrderItem")

            fun link(
                id: String,
                sourceId: String,
            ) = UmlLink(
                id = id,
                associationId = "",
                sourceInstanceId = sourceId,
                targetInstanceId = "order1",
                targetRoleName = "order",
            )

            val l1 = link("linkItem1", "item1")
            val l2 = link("linkItem2", "item2")
            val l3 = link("linkItem3", "item3")

            val diagram =
                KumlDiagram(
                    name = "Order Snapshot",
                    type = DiagramType.OBJECT,
                    elements = listOf(order, item1, item2, item3, l1, l2, l3),
                )

            val orderBounds = Rect(Point(300f, 40f), Size(160f, 60f))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(600f, 300f),
                    nodes = mapOf(NodeId("order1") to NodeLayout(bounds = orderBounds)),
                    edges =
                        mapOf(
                            EdgeId("linkItem1") to EdgeRoute.Direct(Point(335f, 220f), Point(325f, 100f)),
                            EdgeId("linkItem2") to EdgeRoute.Direct(Point(375f, 220f), Point(370f, 100f)),
                            EdgeId("linkItem3") to EdgeRoute.Direct(Point(415f, 220f), Point(415f, 100f)),
                        ),
                    groups = emptyMap(),
                )

            val options = SvgRenderOptions(prettyPrint = false, paddingPx = 0f)
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme(), options)

            val orderLabels = smallLabels(svg).filter { it.text == "order" }
            orderLabels shouldHaveSize 3
            for (i in orderLabels.indices) {
                for (j in i + 1 until orderLabels.size) {
                    val dist = distance(orderLabels[i], orderLabels[j])
                    (dist >= 12f) shouldBe true
                }
            }

            SampleOutput.write("uml/converging-link-labels.svg", svg)
        }
    })
