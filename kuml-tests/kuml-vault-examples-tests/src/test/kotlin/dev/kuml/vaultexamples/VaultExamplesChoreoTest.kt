package dev.kuml.vaultexamples

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Dedicated smoke-tests for BPMN Choreography and Conversation vault examples.
 *
 * These tests supplement the generic [VaultExamplesRenderTest] with shape-level
 * assertions that act as a regression guard for the specific SVG output of each
 * diagram type:
 *
 * - Choreography: tasks rendered as `<rect>` shapes with the initiating-participant
 *   band in Aureolin yellow (`#FFED00` = CHOREO_INITIATING_FILL).
 * - Conversation: conversation nodes rendered as `<polygon>` (hexagon), participants
 *   wrapped in a `<g id="conv-participant-…">` group.
 *
 * V3.2.4 — BPMN Choreography + Conversation: Vault-Beispiele und Tests
 */
class VaultExamplesChoreoTest :
    StringSpec({
        VaultExampleRenderer.init()
        val examples = VaultExampleLoader.loadFromClasspath()

        "rendert ChoreographyDiagram als SVG mit Task-Rechteck-Shapes und Aureolin-Initiator-Band" {
            val ex = examples.firstOrNull { it.baseName.contains("Choreography") }
            ex shouldNotBe null
            val result = VaultExampleRenderer.render(ex!!.kumlScript, "plain")
            result.error shouldBe null
            val svg = result.svg
            svg shouldNotBe null
            svg!! shouldContain "<svg"
            // Choreography-Tasks sind Rechteck-Shapes mit abgerundetem Rand
            svg shouldContain "<rect"
            // Initiierendes Participant-Band immer Aureolin (CHOREO_INITIATING_FILL)
            svg shouldContain "#FFED00"
            SampleOutput.write("vault-examples/choreo-test/choreography.svg", svg)
        }

        "rendert ConversationDiagram als SVG mit Hexagon-Shapes und Participant-Gruppen" {
            val ex = examples.firstOrNull { it.baseName.contains("Conversation") }
            ex shouldNotBe null
            val result = VaultExampleRenderer.render(ex!!.kumlScript, "plain")
            result.error shouldBe null
            val svg = result.svg
            svg shouldNotBe null
            svg!! shouldContain "<svg"
            // Conversation-Nodes sind BPMN-Hexagons (elongierte Polygone)
            svg shouldContain "<polygon"
            // Participants werden in <g id="conv-participant-…">-Gruppen gerendert
            svg shouldContain "conv-participant-"
            SampleOutput.write("vault-examples/choreo-test/conversation.svg", svg)
        }
    })
