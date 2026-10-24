package dev.kuml.desktop.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * One sample per branch of [DesktopRenderPipeline.render] (every UML/C4/SysML 2/BPMN diagram type,
 * Blueprint and the unsupported ERM family), so a refactoring of the branch structure cannot silently drop or
 * mis-wire one. Assertions are deliberately structural (no byte-for-byte SVG comparison): the layout depends on
 * font metrics, which differ between machines.
 */
class DesktopRenderPipelineFamiliesTest :
    FunSpec({

        fun sample(name: String): String =
            DesktopRenderPipelineFamiliesTest::class.java
                .getResourceAsStream("/render-samples/$name.kuml.kts")!!
                .readBytes()
                .toString(Charsets.UTF_8)

        /** name -> whether the diagram can be simulated. */
        val renderable =
            mapOf(
                "uml-class-order-domain" to false,
                "uml-sequence-place-order" to false,
                "uml-state-order-lifecycle" to true,
                "c4-context-internet-banking" to false,
                "sysml2-hybrid-vehicle" to false, // BDD
                "sysml2-ibd-vehicle" to false, // IBD
                "sysml2-library-system-uc" to false, // UC
                "sysml2-vehicle-requirements" to false, // REQ
                "sysml2-traffic-light-stm" to true, // STM
                "sysml2-order-processing-act" to false, // ACT
                "sysml2-login-flow-seq" to false, // SEQ
                "sysml2-newton-second-law-par" to false, // PAR
                "bpmn-order-fulfillment" to false, // process
                "bpmn-collaboration-customer-supplier" to false,
                "bpmn-choreography-bestellprozess" to false,
                "bpmn-conversation-pdv" to false,
                "blueprint-pdv-mitglieder-journey" to false,
            )

        for ((name, simulatable) in renderable) {
            test("$name renders to an SVG (simulatable=$simulatable)") {
                val result = DesktopRenderPipeline.render(script = sample(name), themeName = "kuml")
                result.shouldBeInstanceOf<DesktopRenderResult.Svg>()
                result.svg shouldContain "<svg"
                result.simulatable shouldBe simulatable
            }

            test("$name: the watermark toggle ${if (name.startsWith("blueprint")) "is a known no-op" else "adds the label"}") {
                val plain = DesktopRenderPipeline.render(script = sample(name), themeName = "kuml", watermark = false)
                val marked = DesktopRenderPipeline.render(script = sample(name), themeName = "kuml", watermark = true)
                plain.shouldBeInstanceOf<DesktopRenderResult.Svg>().svg shouldNotContain "Powered by kUML"
                val markedSvg = marked.shouldBeInstanceOf<DesktopRenderResult.Svg>().svg
                if (name.startsWith("blueprint")) {
                    // KNOWN GAP: the Blueprint renderer takes no SvgRenderOptions, so the watermark cannot be threaded through.
                    markedSvg shouldNotContain "Powered by kUML"
                } else {
                    markedSvg shouldContain "Powered by kUML"
                }
            }
        }

        test("an ERM script is reported as not yet supported, not as a crash") {
            val result = DesktopRenderPipeline.render(script = sample("erm-martin-ecommerce"), themeName = "kuml")
            result.shouldBeInstanceOf<DesktopRenderResult.Error>().message shouldContain "ERM-Rendering"
        }

        test("the two SysML 2 flavours that use the wider canvas padding differ from the default padding") {
            // STM and ACT use paddingPx = 64 (same as the simulation preview); the viewBox grows accordingly.
            val stm = DesktopRenderPipeline.render(script = sample("sysml2-traffic-light-stm"), themeName = "kuml")
            stm.shouldBeInstanceOf<DesktopRenderResult.Svg>()
            val sim = DesktopRenderPipeline.prepareSimulation(script = sample("sysml2-traffic-light-stm"), themeName = "kuml")
            sim.shouldBeInstanceOf<SimulationPrepareResult.Ready>()
            val viewBox = Regex("""viewBox="([^"]+)"""")
            viewBox.find(stm.svg)!!.groupValues[1] shouldBe viewBox.find(sim.svg)!!.groupValues[1]
        }
    })
