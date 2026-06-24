package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.blueprint.model.TouchpointSymbol
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * SVG renderer tests for V3.1.23 (Journey-Map view). End-to-end through the real
 * grid geometry (no hardcoded LayoutResult) per CLAUDE.md renderer routine.
 */
class BlueprintJourneySvgTest :
    StringSpec({
        fun journeyModel() =
            blueprint("Onboarding") {
                val web = channel("Website", ChannelKind.WEB)
                val social = channel("Social", ChannelKind.SOCIAL)
                val postTp = touchpoint("Post", channel = social)
                val pageTp = touchpoint("Seite", channel = web)
                phase("Entdeckung") {
                    customer("Sieht Post", Sentiment.NEUTRAL, touchpoints = listOf(postTp))
                }
                phase("Interesse") {
                    customer("Liest Programm", Sentiment.POSITIVE, touchpoints = listOf(pageTp))
                }
                phase("Wartezeit") {
                    customer("Wartet", Sentiment.NEGATIVE, pain = "Unklar wie lange")
                }
                phase("Willkommen") {
                    customer("Erhält Paket", Sentiment.VERY_POSITIVE)
                }
                journeyDiagram("Journey")
            }

        "phase headers appear in order" {
            val svg = renderBlueprintJourney(journeyModel(), JourneyDiagram("J"))
            val iE = svg.indexOf("Entdeckung")
            val iI = svg.indexOf("Interesse")
            val iW = svg.indexOf("Willkommen")
            (iE in 0..iI) shouldBe true
            (iI in 0..iW) shouldBe true
        }

        "customer step cards are rendered with titles" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(m, m.diagrams.first())
            svg shouldContain "Sieht Post"
            svg shouldContain "Liest Programm"
            svg shouldContain "Erhält Paket"
        }

        "emotion curve is a polyline with the right number of points" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(m, JourneyDiagram("J", showEmotionCurve = true))
            svg shouldContain "<polyline"
            // 4 sentiment dots
            Regex("<circle[^>]*r=\"4\"").findAll(svg).count() shouldBe 4
        }

        "emotion curve y-inversion: VERY_POSITIVE is higher than VERY_NEGATIVE" {
            val m =
                blueprint("Y") {
                    phase("A") { customer("low", Sentiment.VERY_NEGATIVE) }
                    phase("B") { customer("high", Sentiment.VERY_POSITIVE) }
                    journeyDiagram("J")
                }
            val svg = renderBlueprintJourney(m, JourneyDiagram("J", showEmotionCurve = true))
            val ys =
                Regex("<circle[^>]*cy=\"([0-9.]+)\"[^>]*r=\"4\"")
                    .findAll(svg)
                    .map { it.groupValues[1].toDouble() }
                    .toList()
            // first point (VERY_NEGATIVE) must have LARGER y than second (VERY_POSITIVE = higher = smaller y)
            (ys[0] > ys[1]) shouldBe true
        }

        "missing sentiment leaves a gap (point skipped)" {
            val m =
                blueprint("G") {
                    phase("A") { customer("x", Sentiment.POSITIVE) }
                    phase("B") { step("no-sentiment", BlueprintLayer.CUSTOMER_ACTIONS) }
                    phase("C") { customer("y", Sentiment.NEGATIVE) }
                    journeyDiagram("J")
                }
            val svg = renderBlueprintJourney(m, JourneyDiagram("J", showEmotionCurve = true))
            Regex("<circle[^>]*r=\"4\"").findAll(svg).count() shouldBe 2
        }

        "touchpoint symbols render channel icons" {
            val m =
                blueprint("T") {
                    val ph = channel("Phone", ChannelKind.PHONE)
                    val hotlineTp = touchpoint("Hotline", channel = ph, symbol = TouchpointSymbol.DIAMOND)
                    phase("P") {
                        customer("Ruft an", Sentiment.NEUTRAL, touchpoints = listOf(hotlineTp))
                    }
                    journeyDiagram("J")
                }
            val svg = renderBlueprintJourney(m, m.diagrams.first())
            svg shouldContain "polygon" // diamond symbol
            svg shouldContain """<path d="M6 3c""" // phone icon path prefix
        }

        "pain marker is drawn on the card" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(m, m.diagrams.first())
            svg shouldContain "#d00080" // pain colour
        }

        "swimlane layer header rendered for customer layer" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(m, m.diagrams.first())
            svg shouldContain "Customer Actions"
        }

        "journey view hides empty non-customer layers" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(m, JourneyDiagram("J"))
            svg shouldNotContain "Backstage"
        }

        // ── V3.1.24: arrowhead marker uniqueness ─────────────────────────────

        "arrowhead marker id=bp-arrow appears exactly once regardless of connection count" {
            lateinit var s1: String
            lateinit var s2: String
            lateinit var s3: String
            val m =
                blueprint("Connections") {
                    phase("A") { s1 = customer("Step1", Sentiment.NEUTRAL) }
                    phase("B") { s2 = customer("Step2", Sentiment.POSITIVE) }
                    phase("C") { s3 = customer("Step3", Sentiment.NEGATIVE) }
                    // two connections → previously emitted two duplicate <defs> blocks
                    connection(s1, s2)
                    connection(s2, s3)
                    journeyDiagram("J")
                }
            val svg = renderBlueprintJourney(m, m.diagrams.first())
            Regex("""id="bp-arrow"""").findAll(svg).count() shouldBe 1
        }

        // ── V3.1.24: BlueprintDiagramFull multi-layer rendering ──────────────

        "BlueprintDiagramFull renders all four Shostack layer headers" {
            val m =
                blueprint("Full") {
                    phase("A") {
                        customer("Customer step", Sentiment.NEUTRAL)
                        step("Frontstage step", BlueprintLayer.FRONTSTAGE)
                        step("Backstage step", BlueprintLayer.BACKSTAGE)
                        step("Support step", BlueprintLayer.SUPPORT_PROCESSES)
                    }
                    blueprintDiagram("Full view")
                }
            val svg =
                renderBlueprintJourney(
                    m,
                    BlueprintDiagramFull(
                        "Full view",
                        visibleLayers = BlueprintLayer.entries.toSet(),
                    ),
                )
            svg shouldContain "Customer Actions"
            svg shouldContain "Frontstage"
            svg shouldContain "Backstage"
            svg shouldContain "Support Processes"
        }

        "BlueprintDiagramFull renders step content in all four layers" {
            val m =
                blueprint("Full") {
                    phase("A") {
                        customer("Customer step", Sentiment.NEUTRAL)
                        step("Frontstage step", BlueprintLayer.FRONTSTAGE)
                        step("Backstage step", BlueprintLayer.BACKSTAGE)
                        step("Support step", BlueprintLayer.SUPPORT_PROCESSES)
                    }
                    blueprintDiagram("Full view")
                }
            val svg =
                renderBlueprintJourney(
                    m,
                    BlueprintDiagramFull(
                        "Full view",
                        visibleLayers = BlueprintLayer.entries.toSet(),
                    ),
                )
            svg shouldContain "Customer step"
            svg shouldContain "Frontstage step"
            svg shouldContain "Backstage step"
            svg shouldContain "Support step"
        }

        "BlueprintDiagramFull arrowhead marker appears exactly once" {
            lateinit var s1: String
            lateinit var s2: String
            val m =
                blueprint("Full") {
                    phase("A") { s1 = customer("s1", Sentiment.NEUTRAL) }
                    phase("B") { s2 = customer("s2", Sentiment.POSITIVE) }
                    connection(s1, s2)
                    blueprintDiagram("Full view")
                }
            val svg =
                renderBlueprintJourney(
                    m,
                    BlueprintDiagramFull(
                        "Full view",
                        visibleLayers = setOf(BlueprintLayer.CUSTOMER_ACTIONS),
                    ),
                )
            Regex("""id="bp-arrow"""").findAll(svg).count() shouldBe 1
        }

        "svg contains embedded style block with kuml-title and kuml-body" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(m, JourneyDiagram("J"))
            svg shouldContain "<style>"
            svg shouldContain ".kuml-title"
            svg shouldContain ".kuml-body"
        }
    })
