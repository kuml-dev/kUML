package dev.kuml.io.svg

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
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.renderer.theme.core.StereotypeTheme
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Stereotype rendering tests for the SVG renderer (V1.1 AP-3.4).
 *
 * Each test asserts on the rendered SVG string to verify that stereotype annotations
 * are correctly emitted. "Goldfile" inspection = visual review of the SVG by the ticket author.
 */
class StereotypeRenderTest :
    FunSpec({

        // ── Helpers ───────────────────────────────────────────────────────────────

        fun singleNodeLayout(
            id: String,
            x: Float = 10f,
            y: Float = 10f,
            w: Float = 160f,
            h: Float = 80f,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(w + 20f, h + 20f),
                nodes =
                    mapOf(
                        NodeId(id) to NodeLayout(bounds = Rect(Point(x, y), Size(w, h))),
                    ),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        fun edgeLayout(
            nodeAId: String,
            nodeBId: String,
            edgeId: String,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(400f, 200f),
                nodes =
                    mapOf(
                        NodeId(nodeAId) to NodeLayout(bounds = Rect(Point(10f, 40f), Size(120f, 60f))),
                        NodeId(nodeBId) to NodeLayout(bounds = Rect(Point(220f, 40f), Size(120f, 60f))),
                    ),
                edges =
                    mapOf(
                        EdgeId(edgeId) to
                            EdgeRoute.Direct(
                                source = Point(130f, 70f),
                                target = Point(220f, 70f),
                            ),
                    ),
                groups = emptyMap(),
            )

        val entityApp =
            KumlStereotypeApplication(
                profileNamespace = "test.profile",
                stereotypeName = "Entity",
            )
        val serviceApp =
            KumlStereotypeApplication(
                profileNamespace = "test.profile",
                stereotypeName = "Service",
            )
        val auditedApp =
            KumlStereotypeApplication(
                profileNamespace = "test.profile",
                stereotypeName = "Audited",
            )

        // ── Test 1: «Entity» header above class name ──────────────────────────

        test("renders «Entity» header above class name") {
            val cls =
                UmlClass(
                    id = "cls1",
                    name = "Order",
                    appliedStereotypes = listOf(entityApp),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls1")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "«Entity»"
            svg shouldContain "Order"
            // Stereotype text must appear before the element name in the SVG document order.
            // Both texts appear as raw text nodes (no XML escaping needed for «/»).
            val stereoPos = svg.indexOf("«Entity»")
            val namePos = svg.indexOf("Order")
            (stereoPos < namePos) shouldBe true
        }

        // ── Test 2: comma-joined header for two stereotypes ───────────────────

        test("renders comma-joined header for two stereotypes") {
            val cls =
                UmlClass(
                    id = "cls2",
                    name = "OrderService",
                    appliedStereotypes = listOf(entityApp, serviceApp),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls2")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "«Entity, Service»"
        }

        // ── Test 3: custom joinSeparator changes multi-stereotype output ───────

        test("custom joinSeparator changes multi-stereotype separator") {
            val cls =
                UmlClass(
                    id = "cls3",
                    name = "OrderService",
                    appliedStereotypes = listOf(entityApp, serviceApp),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls3")

            val themeWithPipe =
                PlainTheme().copy(
                    stereotypes = StereotypeTheme(joinSeparator = " | "),
                )
            val svg = KumlSvgRenderer.toSvg(diagram, layout, themeWithPipe)

            svg shouldContain "«Entity | Service»"
            svg shouldNotContain "«Entity, Service»"
        }

        // ── Test 4: tagged-value compartment renders when showTaggedValues=true

        test("tagged-value compartment renders when showTaggedValues=true") {
            val appWithTags =
                KumlStereotypeApplication(
                    profileNamespace = "test.profile",
                    stereotypeName = "Entity",
                    tags =
                        mapOf(
                            "table" to TagValue.StringVal("orders"),
                            "version" to TagValue.IntVal(2),
                        ),
                )
            val cls =
                UmlClass(
                    id = "cls4",
                    name = "Order",
                    appliedStereotypes = listOf(appWithTags),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls4", h = 120f)

            val themeWithTV =
                PlainTheme().copy(
                    stereotypes = StereotypeTheme(showTaggedValues = true),
                )
            val svg = KumlSvgRenderer.toSvg(diagram, layout, themeWithTV)

            svg shouldContain "{table = orders}"
            svg shouldContain "{version = 2}"
            svg shouldContain "kuml-tagged-value"
        }

        // ── Test 5: tagged-value compartment absent by default ────────────────

        test("tagged-value compartment absent by default") {
            val appWithTags =
                KumlStereotypeApplication(
                    profileNamespace = "test.profile",
                    stereotypeName = "Entity",
                    tags = mapOf("table" to TagValue.StringVal("orders")),
                )
            val cls =
                UmlClass(
                    id = "cls5",
                    name = "Order",
                    appliedStereotypes = listOf(appWithTags),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls5")

            // Default PlainTheme: showTaggedValues = false
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // Tagged-value rows must not appear in the SVG content
            svg shouldNotContain "{table = orders}"
            // The CSS class definition is in <defs><style>, but no elements use it
            svg shouldNotContain "class=\"kuml-tagged-value\""
        }

        // ── Test 6: port stereotype accessible via Stereotypable ──────────────

        test("port stereotype data is accessible via Stereotypable interface") {
            // UmlPort implements Stereotypable — verify StereotypeHelper works on it
            val port =
                UmlPort(
                    id = "port1",
                    name = "p1",
                    appliedStereotypes = listOf(serviceApp),
                )
            // StereotypeHelper.headerLabel is pure-Kotlin; test it directly without SVG run
            val label =
                dev.kuml.io.svg.uml.StereotypeHelper
                    .headerLabel(port, StereotypeTheme.Default)
            label shouldBe "«Service»"
        }

        // ── Test 7: edge stereotype renders as middle label on association ─────

        test("edge stereotype renders as middle label on association") {
            val rel =
                UmlAssociation(
                    id = "cls6--cls7",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "cls6"),
                            UmlAssociationEnd(typeId = "cls7"),
                        ),
                    appliedStereotypes = listOf(auditedApp),
                )
            val cls6 = UmlClass(id = "cls6", name = "A")
            val cls7 = UmlClass(id = "cls7", name = "B")
            val diagram = KumlDiagram(name = "D", elements = listOf(cls6, cls7, rel))
            val layout = edgeLayout("cls6", "cls7", "cls6--cls7")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "«Audited»"
        }

        // ── Test 8: class without stereotypes renders byte-identical ──────────

        test("class without stereotypes renders unchanged (no stereotype markup)") {
            val cls =
                UmlClass(
                    id = "cls8",
                    name = "Order",
                    // No appliedStereotypes — default empty list ensures no regression
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls8")
            val theme = PlainTheme()

            val svg1 = KumlSvgRenderer.toSvg(diagram, layout, theme)
            val svg2 = KumlSvgRenderer.toSvg(diagram, layout, theme)

            // Determinism: two calls produce identical output
            svg1 shouldBe svg2
            // No applied-stereotype markup emitted for a plain class (no «...» from appliedStereotypes)
            svg1 shouldNotContain "«"
            // No tagged-value element references (CSS rule exists in <defs> but no elements use it)
            svg1 shouldNotContain "class=\"kuml-tagged-value\""
        }

        // ── Test 9: plain display-label stereotype on UmlAssociation (ADR-0017) ─

        test("edge with plain UmlAssociation.stereotypes renders as middle label") {
            val rel =
                UmlAssociation(
                    id = "cls9--cls10",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "cls9"),
                            UmlAssociationEnd(typeId = "cls10"),
                        ),
                    stereotypes = listOf("FK"),
                )
            val cls9 = UmlClass(id = "cls9", name = "A")
            val cls10 = UmlClass(id = "cls10", name = "B")
            val diagram = KumlDiagram(name = "D", elements = listOf(cls9, cls10, rel))
            val layout = edgeLayout("cls9", "cls10", "cls9--cls10")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "«FK»"
            // Bug-fix V0.27.1: edge-mid stereotype labels need a halo pass so the
            // edge polyline underneath doesn't visually cut through the italic
            // glyphs (observed on the "38 UML Profil – Exposed" vault example's
            // «FK» label). The halo <text> must be emitted before the visible
            // <text class="kuml-stereotype"> copy — same two-pass order as
            // kuml-edge-label-halo / kuml-edge-label.
            svg shouldContain "class=\"kuml-stereotype-halo\""
            val haloIndex = svg.indexOf("class=\"kuml-stereotype-halo\"")
            val fillIndex = svg.indexOf("class=\"kuml-stereotype\"")
            (haloIndex in 0 until fillIndex) shouldBe true
        }

        // ── Test 10: applied + plain stereotypes on UmlAssociation merge, dedupe ─

        test("edge with both applied and plain UmlAssociation.stereotypes merges them") {
            val rel =
                UmlAssociation(
                    id = "cls11--cls12",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "cls11"),
                            UmlAssociationEnd(typeId = "cls12"),
                        ),
                    stereotypes = listOf("FK", "Audited"),
                    appliedStereotypes = listOf(auditedApp),
                )
            val cls11 = UmlClass(id = "cls11", name = "A")
            val cls12 = UmlClass(id = "cls12", name = "B")
            val diagram = KumlDiagram(name = "D", elements = listOf(cls11, cls12, rel))
            val layout = edgeLayout("cls11", "cls12", "cls11--cls12")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // Applied name first, then remaining plain names; duplicate "Audited" collapsed once
            svg shouldContain "«Audited, FK»"
        }

        // ── Test 11: UmlAssociation.stereotypes via headerLabel directly ────────

        test("headerLabel reads UmlAssociation.stereotypes even without appliedStereotypes") {
            val rel =
                UmlAssociation(
                    id = "cls13--cls14",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "cls13"),
                            UmlAssociationEnd(typeId = "cls14"),
                        ),
                    stereotypes = listOf("FK"),
                )
            val label =
                dev.kuml.io.svg.uml.StereotypeHelper
                    .headerLabel(rel, StereotypeTheme.Default)
            label shouldBe "«FK»"
        }
    })
