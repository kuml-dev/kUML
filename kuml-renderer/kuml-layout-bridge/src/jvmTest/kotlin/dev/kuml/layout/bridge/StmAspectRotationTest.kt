package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.ln

/**
 * Nacharbeit 58 (a): an explicit aspectRatio / maxWidth in a state machine without an explicit direction may rotate the
 * flow direction when that lands closer to the target. An explicit direction is never overridden. Geometry only.
 */
class StmAspectRotationTest :
    FunSpec({
        fun outcome(
            aspect: Double?,
            maxWidth: Int?,
            direction: String?,
        ): SpecLayoutOutcome {
            val m =
                sysml2Model(name = "Cycle") {
                    val i = stateDef(name = "Start", isInitial = true)
                    val off = stateDef(name = "Off")
                    val run = stateDef(name = "Running")
                    val stp = stateDef(name = "Stopped")
                    transition(name = "i", source = i, target = off)
                    transition(name = "t1", source = off, target = run, trigger = "start")
                    transition(name = "t2", source = run, target = stp, trigger = "stop")
                    transition(name = "t3", source = stp, target = off, trigger = "reset")
                    stmDiagram(name = "Cycle") {
                        layout {
                            aspectRatio = aspect
                            this.maxWidth = maxWidth
                            if (direction == "Down") this.direction = Down
                        }
                        include(state = i)
                        include(state = off)
                        include(state = run)
                        include(state = stp)
                    }
                }
            val diagram = m.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = diagram)
            val hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = diagram)
            return LayoutSpecRunner.layout(
                engine = ElkLayoutEngine(),
                graph = graph,
                hints = hints,
                spec = diagram.layout,
                kind = SpecDiagramKind.SYSML2_STM,
            )
        }

        fun ratio(o: SpecLayoutOutcome) =
            o.result.canvas.width
                .toDouble() / o.result.canvas.height

        test("explicit aspectRatio 3.0 without direction rotates the flow and lands closer to the target") {
            val plain = outcome(aspect = null, maxWidth = null, direction = null)
            val withTarget = outcome(aspect = 3.0, maxWidth = null, direction = null)
            (abs(ln(ratio(withTarget) / 3.0)) < abs(ln(ratio(plain) / 3.0))).shouldBeTrue()
        }

        test("explicit direction Down is never rotated; W003 reports the real ratio") {
            val plain = outcome(aspect = null, maxWidth = null, direction = "Down")
            val o = outcome(aspect = 1.0, maxWidth = null, direction = "Down")
            o.result.canvas shouldBe plain.result.canvas
            val w003 = o.findings.filter { it.code == LayoutCodes.ASPECT_UNREACHED }
            w003.size shouldBe 1
            w003
                .single()
                .message
                .contains((kotlin.math.round(ratio(o) * 100) / 100).toString())
                .shouldBeTrue()
        }

        test("without any spec the result is unchanged and carries no finding") {
            val a = outcome(aspect = null, maxWidth = null, direction = null)
            val b = outcome(aspect = null, maxWidth = null, direction = null)
            a.result.canvas shouldBe b.result.canvas
            a.findings.isEmpty().shouldBeTrue()
        }

        test("maxWidth 200 without direction: narrower than the unrotated run, or W003") {
            val plain = outcome(aspect = null, maxWidth = null, direction = null)
            val o = outcome(aspect = null, maxWidth = 200, direction = null)
            (
                o.result.canvas.width < plain.result.canvas.width ||
                    o.findings.any { it.code == LayoutCodes.MAX_WIDTH_UNREACHED }
            ).shouldBeTrue()
        }
    })
