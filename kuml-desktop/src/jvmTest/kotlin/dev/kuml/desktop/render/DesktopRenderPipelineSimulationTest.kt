package dev.kuml.desktop.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.collections.shouldContain as shouldContainElement

/**
 * V3.x — Live-Simulation von Zustandsautomaten im Editor.
 *
 * Covers [DesktopRenderPipeline.prepareSimulation] and the `simulatable` flag on
 * [DesktopRenderPipeline.render]'s [DesktopRenderResult.Svg].
 */
class DesktopRenderPipelineSimulationTest :
    FunSpec({

        val umlScript =
            """
            stateDiagram(name = "OrderLifecycle") {
                val init = initialState()
                val draft = state(name = "Draft") { entry = "validate()" }
                val confirmed = state(name = "Confirmed") { entry = "reserveStock()" }
                val done = finalState(name = "Done")

                transition(source = init, target = draft)
                transition(source = draft, target = confirmed) { trigger = "confirm()"; effect = "log()" }
                transition(source = confirmed, target = done) { trigger = "deliver()" }
            }
            """.trimIndent()

        val sysml2Script =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("TrafficLight") {
                val initial = stateDef("Initial", isInitial = true)
                val red = stateDef("Red")
                val green = stateDef("Green")

                transition("init", initial, red)
                transition("redToGreen", red, green, trigger = "next")

                stmDiagram("Phase cycle") {
                    include(initial)
                    include(red)
                    include(green)
                }
            }
            """.trimIndent()

        val classScript =
            """
            classDiagram(name = "Test") {
                classOf(name = "A") { }
            }
            """.trimIndent()

        // ── prepareSimulation ────────────────────────────────────────────────

        test("prepareSimulation on a UML STATE script returns Ready with a hidden ring per vertex") {
            val result = DesktopRenderPipeline.prepareSimulation(script = umlScript, themeName = "plain")
            result.shouldBeInstanceOf<SimulationPrepareResult.Ready>()
            result.svg shouldContain "visibility=\"hidden\""
            // The stateDiagram DSL qualifies vertex IDs as "<stateMachineId>::<name>"
            // (UmlIds.vertex) rather than using the bare display name.
            result.stateMachine.vertices.map { it.name } shouldContainElement "Draft"
        }

        test("prepareSimulation on a SysML-2 STM script returns Ready") {
            val result = DesktopRenderPipeline.prepareSimulation(script = sysml2Script, themeName = "plain")
            result.shouldBeInstanceOf<SimulationPrepareResult.Ready>()
        }

        test("prepareSimulation on a non-simulatable diagram returns Unsupported") {
            DesktopRenderPipeline
                .prepareSimulation(script = classScript, themeName = "plain")
                .shouldBeInstanceOf<SimulationPrepareResult.Unsupported>()
        }

        test("prepareSimulation on a syntax error returns Failed") {
            DesktopRenderPipeline
                .prepareSimulation(script = "not valid kotlin @@@", themeName = "plain")
                .shouldBeInstanceOf<SimulationPrepareResult.Failed>()
        }

        // ── padding parity guard against the "preview jumps at simulation start" bug ──

        test("prepareSimulation's SysML-2 STM base SVG uses the same viewBox as a regular render of the same script") {
            val renderResult = DesktopRenderPipeline.render(script = sysml2Script, themeName = "plain") as DesktopRenderResult.Svg
            val simResult = DesktopRenderPipeline.prepareSimulation(script = sysml2Script, themeName = "plain")
            simResult.shouldBeInstanceOf<SimulationPrepareResult.Ready>()

            fun viewBox(svg: String): String = Regex("viewBox=\"[^\"]*\"").find(svg)?.value ?: "MISSING"
            viewBox(simResult.svg) shouldBe viewBox(renderResult.svg)
        }

        // ── DesktopRenderResult.Svg.simulatable ──────────────────────────────

        test("render() sets simulatable = true for a UML STATE diagram") {
            val result = DesktopRenderPipeline.render(script = umlScript, themeName = "plain") as DesktopRenderResult.Svg
            result.simulatable shouldBe true
        }

        test("render() sets simulatable = false for a class diagram") {
            val result = DesktopRenderPipeline.render(script = classScript, themeName = "plain") as DesktopRenderResult.Svg
            result.simulatable shouldBe false
        }

        // ── Export cleanliness at the pipeline level (Jony Ive) ──────────────

        test("a regular render() of a simulatable diagram contains no highlight-ring markup") {
            val result = DesktopRenderPipeline.render(script = umlScript, themeName = "plain") as DesktopRenderResult.Svg
            result.svg shouldNotContain "kuml-highlight-ring"
        }
    })
