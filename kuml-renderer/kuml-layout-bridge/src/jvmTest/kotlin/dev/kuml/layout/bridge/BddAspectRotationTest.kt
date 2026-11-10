package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.ln

/**
 * Nacharbeit 60: an explicit aspectRatio / maxWidth in a block definition diagram without an explicit direction may
 * rotate the flow direction (as for state machines, Nacharbeit 58); the generalisation bus and allocation docking
 * follow the direction of the chosen candidate. An explicit direction is never overridden. Geometry only.
 */
class BddAspectRotationTest :
    FunSpec({
        fun layoutOf(
            fan: Boolean,
            aspect: Double?,
            maxWidth: Int?,
            direction: String?,
        ): Pair<SpecLayoutOutcome, BdDiagram> {
            val m =
                sysml2Model(name = "Hub") {
                    if (fan) {
                        partDef(name = "Base")
                        for (i in 1..6) partDef(name = "Sub$i", specializesId = "Base")
                    } else {
                        for (i in 1..8) partDef(name = "P$i")
                        partDef(name = "Hub") {
                            for (i in 1..8) refPart(name = "p$i", typeId = "P$i")
                        }
                    }
                    bdd(name = "BDD") {
                        layout {
                            aspectRatio = aspect
                            this.maxWidth = maxWidth
                            when (direction) {
                                "Down" -> this.direction = Down
                                "Right" -> this.direction = Right
                            }
                        }
                        if (fan) {
                            includeById(id = "Base")
                            for (i in 1..6) includeById(id = "Sub$i")
                        } else {
                            includeById(id = "Hub")
                            for (i in 1..8) includeById(id = "P$i")
                        }
                    }
                }
            val diagram = m.diagrams.filterIsInstance<BdDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = diagram)
            val hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = diagram)
            val outcome =
                LayoutSpecRunner.layout(
                    engine = ElkLayoutEngine(),
                    graph = graph,
                    hints = hints,
                    spec = diagram.layout,
                    kind = SpecDiagramKind.SYSML2_BDD,
                )
            return outcome to diagram
        }

        fun ratio(o: SpecLayoutOutcome) =
            o.result.canvas.width
                .toDouble() / o.result.canvas.height

        fun nodeY(
            o: SpecLayoutOutcome,
            id: String,
        ) = o.result.nodes.entries
            .single { it.key.value == id }
            .value.bounds.origin.y

        test("(a) hub: aspectRatio 1.0 lands nearer to 1.0 than without the option and reports the real ratio") {
            val plain = layoutOf(fan = false, aspect = null, maxWidth = null, direction = null).first
            val o = layoutOf(fan = false, aspect = 1.0, maxWidth = null, direction = null).first
            (abs(ln(ratio(o))) < abs(ln(ratio(plain)))).shouldBeTrue()
            val w003 = o.findings.filter { it.code == LayoutCodes.ASPECT_UNREACHED }
            w003.size shouldBe 1
            w003
                .single()
                .message
                .contains((kotlin.math.round(ratio(o) * 100) / 100).toString())
                .shouldBeTrue()
        }

        test("(b) generalisation fan: aspectRatio 1.0 without direction rotates the flow and lands nearer to 1.0") {
            val plain = layoutOf(fan = true, aspect = null, maxWidth = null, direction = null).first
            val o = layoutOf(fan = true, aspect = 1.0, maxWidth = null, direction = null).first
            (abs(ln(ratio(o))) < abs(ln(ratio(plain)))).shouldBeTrue()
        }

        test("(c) explicit direction Down is never rotated; the hub stays above its children") {
            val plain = layoutOf(fan = false, aspect = null, maxWidth = null, direction = "Down").first
            val o = layoutOf(fan = false, aspect = 3.0, maxWidth = null, direction = "Down").first
            for (i in 1..8) (nodeY(o, "Hub") < nodeY(o, "P$i")).shouldBeTrue()
            // Wrapping may change the canvas, a rotation to a horizontal flow must not happen.
            (
                o.result.canvas.width / o.result.canvas.height <= plain.result.canvas.width
                    .toDouble() / plain.result.canvas.height + 1e-9 ||
                    o.result.canvas.width >= plain.result.canvas.width
            ).shouldBeTrue()
        }

        test("(c2) explicit direction Right is never rotated; the hub stays left of its children") {
            val o = layoutOf(fan = false, aspect = 1.0, maxWidth = null, direction = "Right").first
            val hubX =
                o.result.nodes.entries
                    .single { it.key.value == "Hub" }
                    .value.bounds.origin.x
            for (i in 1..8) {
                (
                    hubX <
                        o.result.nodes.entries
                            .single { it.key.value == "P$i" }
                            .value.bounds.origin.x
                ).shouldBeTrue()
            }
        }

        test("(d) a forced rotation draws the bus for the rotated flow: edge ends sit on the sides facing the flow") {
            val o = layoutOf(fan = true, aspect = 1.0, maxWidth = null, direction = null).first
            val base =
                o.result.nodes.entries
                    .single { it.key.value == "Base" }
                    .value.bounds
            val subs =
                o.result.nodes.entries
                    .filter { it.key.value.startsWith("Sub") }
                    .map { it.value.bounds }
            // The scenario must rotate to a horizontal flow, otherwise the test proves nothing.
            subs.all { it.origin.x + it.size.width <= base.origin.x + 0.5f }.shouldBeTrue()
            o.result.edges
                .isNotEmpty()
                .shouldBeTrue()
            for (route in o.result.edges.values) {
                val ends = listOf(route.source, route.target)
                ends.any { abs(it.x - base.origin.x) < 1.0f }.shouldBeTrue()
                ends.any { p -> subs.any { abs(p.x - (it.origin.x + it.size.width)) < 1.0f } }.shouldBeTrue()
            }
        }

        test("(f) maxWidth 900 is met or reported with the real value") {
            val o = layoutOf(fan = false, aspect = null, maxWidth = 900, direction = null).first
            val w002 = o.findings.filter { it.code == LayoutCodes.MAX_WIDTH_UNREACHED }
            (o.result.canvas.width <= 900 || w002.size == 1).shouldBeTrue()
        }

        test("(e) without options there are no findings and the flow stays vertical") {
            val o = layoutOf(fan = false, aspect = null, maxWidth = null, direction = null).first
            o.findings.size shouldBe 0
            (nodeY(o, "Hub") < nodeY(o, "P1")).shouldBeTrue()
        }
    })
