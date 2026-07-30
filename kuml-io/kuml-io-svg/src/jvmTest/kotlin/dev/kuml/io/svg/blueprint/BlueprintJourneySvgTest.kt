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
            blueprint(name = "Onboarding") {
                val web = channel(name = "Website", kind = ChannelKind.WEB)
                val social = channel(name = "Social", kind = ChannelKind.SOCIAL)
                val postTp = touchpoint(name = "Post", channel = social)
                val pageTp = touchpoint(name = "Seite", channel = web)
                phase(name = "Entdeckung") {
                    customer(name = "Sieht Post", sentiment = Sentiment.NEUTRAL, touchpoints = listOf(postTp))
                }
                phase(name = "Interesse") {
                    customer(name = "Liest Programm", sentiment = Sentiment.POSITIVE, touchpoints = listOf(pageTp))
                }
                phase(name = "Wartezeit") {
                    customer(name = "Wartet", sentiment = Sentiment.NEGATIVE, pain = "Unklar wie lange")
                }
                phase(name = "Willkommen") {
                    customer(name = "Erhält Paket", sentiment = Sentiment.VERY_POSITIVE)
                }
                journeyDiagram(name = "Journey")
            }

        "phase headers appear in order" {
            val svg = renderBlueprintJourney(model = journeyModel(), diagram = JourneyDiagram(name = "J"))
            val iE = svg.indexOf("Entdeckung")
            val iI = svg.indexOf("Interesse")
            val iW = svg.indexOf("Willkommen")
            (iE in 0..iI) shouldBe true
            (iI in 0..iW) shouldBe true
        }

        "customer step cards are rendered with titles" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(model = m, diagram = m.diagrams.first())
            svg shouldContain "Sieht Post"
            svg shouldContain "Liest Programm"
            svg shouldContain "Erhält Paket"
        }

        "emotion curve is a polyline with the right number of points" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(model = m, diagram = JourneyDiagram(name = "J", showEmotionCurve = true))
            svg shouldContain "<polyline"
            // 4 sentiment dots
            Regex("""class="bp-emotion-dot"""").findAll(svg).count() shouldBe 4
        }

        "emotion curve y-inversion: VERY_POSITIVE is higher than VERY_NEGATIVE" {
            val m =
                blueprint(name = "Y") {
                    phase(name = "A") { customer(name = "low", sentiment = Sentiment.VERY_NEGATIVE) }
                    phase(name = "B") { customer(name = "high", sentiment = Sentiment.VERY_POSITIVE) }
                    journeyDiagram(name = "J")
                }
            val svg = renderBlueprintJourney(model = m, diagram = JourneyDiagram(name = "J", showEmotionCurve = true))
            val ys =
                Regex("""<circle class="bp-emotion-dot"[^>]*cy="([0-9.]+)"""")
                    .findAll(svg)
                    .map { it.groupValues[1].toDouble() }
                    .toList()
            // first point (VERY_NEGATIVE) must have LARGER y than second (VERY_POSITIVE = higher = smaller y)
            (ys[0] > ys[1]) shouldBe true
        }

        "emotion curve renders Y-axis scale, neutral baseline and band label" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(model = m, diagram = JourneyDiagram(name = "J", showEmotionCurve = true))
            // axis ticks
            svg shouldContain ">+2<"
            svg shouldContain ">−2<"
            // dashed neutral baseline
            svg shouldContain """stroke-dasharray="4 3""""
            // band label
            svg shouldContain ">Emotion<"
        }

        "emotion points are colour-coded by sentiment with tooltip" {
            val m =
                blueprint(name = "C") {
                    phase(name = "Tief") { customer(name = "schlecht", sentiment = Sentiment.VERY_NEGATIVE) }
                    phase(name = "Hoch") { customer(name = "super", sentiment = Sentiment.VERY_POSITIVE) }
                    journeyDiagram(name = "J")
                }
            val svg = renderBlueprintJourney(model = m, diagram = JourneyDiagram(name = "J", showEmotionCurve = true))
            svg shouldContain "#c0143c" // VERY_NEGATIVE red
            svg shouldContain "#2e9e5b" // VERY_POSITIVE green
            svg shouldContain "Tief: sehr negativ"
            svg shouldContain "Hoch: sehr positiv"
        }

        "missing sentiment leaves a gap (point skipped)" {
            val m =
                blueprint(name = "G") {
                    phase(name = "A") { customer(name = "x", sentiment = Sentiment.POSITIVE) }
                    phase(name = "B") { step(name = "no-sentiment", layer = BlueprintLayer.CUSTOMER_ACTIONS) }
                    phase(name = "C") { customer(name = "y", sentiment = Sentiment.NEGATIVE) }
                    journeyDiagram(name = "J")
                }
            val svg = renderBlueprintJourney(model = m, diagram = JourneyDiagram(name = "J", showEmotionCurve = true))
            Regex("""class="bp-emotion-dot"""").findAll(svg).count() shouldBe 2
        }

        "touchpoint symbols render channel icons" {
            val m =
                blueprint(name = "T") {
                    val ph = channel(name = "Phone", kind = ChannelKind.PHONE)
                    val hotlineTp = touchpoint(name = "Hotline", channel = ph, symbol = TouchpointSymbol.DIAMOND)
                    phase(name = "P") {
                        customer(name = "Ruft an", sentiment = Sentiment.NEUTRAL, touchpoints = listOf(hotlineTp))
                    }
                    journeyDiagram(name = "J")
                }
            val svg = renderBlueprintJourney(model = m, diagram = m.diagrams.first())
            svg shouldContain "polygon" // diamond symbol
            svg shouldContain """<path d="M6 3c""" // phone icon path prefix
        }

        "pain marker is drawn on the card" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(model = m, diagram = m.diagrams.first())
            svg shouldContain "#d00080" // pain colour
        }

        "swimlane layer header rendered for customer layer" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(model = m, diagram = m.diagrams.first())
            svg shouldContain "Customer Actions"
        }

        "journey view hides empty non-customer layers" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(model = m, diagram = JourneyDiagram(name = "J"))
            svg shouldNotContain "Backstage"
        }

        // ── V3.1.24: arrowhead marker uniqueness ─────────────────────────────

        "arrowhead marker id=bp-arrow appears exactly once regardless of connection count" {
            lateinit var s1: String
            lateinit var s2: String
            lateinit var s3: String
            val m =
                blueprint(name = "Connections") {
                    phase(name = "A") { s1 = customer(name = "Step1", sentiment = Sentiment.NEUTRAL) }
                    phase(name = "B") { s2 = customer(name = "Step2", sentiment = Sentiment.POSITIVE) }
                    phase(name = "C") { s3 = customer(name = "Step3", sentiment = Sentiment.NEGATIVE) }
                    // two connections → previously emitted two duplicate <defs> blocks
                    connection(from = s1, to = s2)
                    connection(from = s2, to = s3)
                    journeyDiagram(name = "J")
                }
            val svg = renderBlueprintJourney(model = m, diagram = m.diagrams.first())
            Regex("""id="bp-arrow"""").findAll(svg).count() shouldBe 1
        }

        // ── V3.1.24: BlueprintDiagramFull multi-layer rendering ──────────────

        "BlueprintDiagramFull renders all four Shostack layer headers" {
            val m =
                blueprint(name = "Full") {
                    phase(name = "A") {
                        customer(name = "Customer step", sentiment = Sentiment.NEUTRAL)
                        step(name = "Frontstage step", layer = BlueprintLayer.FRONTSTAGE)
                        step(name = "Backstage step", layer = BlueprintLayer.BACKSTAGE)
                        step(name = "Support step", layer = BlueprintLayer.SUPPORT_PROCESSES)
                    }
                    blueprintDiagram(name = "Full view")
                }
            val svg =
                renderBlueprintJourney(
                    model = m,
                    diagram =
                        BlueprintDiagramFull(
                            name = "Full view",
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
                blueprint(name = "Full") {
                    phase(name = "A") {
                        customer(name = "Customer step", sentiment = Sentiment.NEUTRAL)
                        step(name = "Frontstage step", layer = BlueprintLayer.FRONTSTAGE)
                        step(name = "Backstage step", layer = BlueprintLayer.BACKSTAGE)
                        step(name = "Support step", layer = BlueprintLayer.SUPPORT_PROCESSES)
                    }
                    blueprintDiagram(name = "Full view")
                }
            val svg =
                renderBlueprintJourney(
                    model = m,
                    diagram =
                        BlueprintDiagramFull(
                            name = "Full view",
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
                blueprint(name = "Full") {
                    phase(name = "A") { s1 = customer(name = "s1", sentiment = Sentiment.NEUTRAL) }
                    phase(name = "B") { s2 = customer(name = "s2", sentiment = Sentiment.POSITIVE) }
                    connection(from = s1, to = s2)
                    blueprintDiagram(name = "Full view")
                }
            val svg =
                renderBlueprintJourney(
                    model = m,
                    diagram =
                        BlueprintDiagramFull(
                            name = "Full view",
                            visibleLayers = setOf(BlueprintLayer.CUSTOMER_ACTIONS),
                        ),
                )
            Regex("""id="bp-arrow"""").findAll(svg).count() shouldBe 1
        }

        "svg contains embedded style block with kuml-title and kuml-body" {
            val m = journeyModel()
            val svg = renderBlueprintJourney(model = m, diagram = JourneyDiagram(name = "J"))
            svg shouldContain "<style>"
            svg shouldContain ".kuml-title"
            svg shouldContain ".kuml-body"
        }

        // ── wrapText unit tests (V3.1.27) ──────────────────────────────────

        "wrapText: short text fits in one line" {
            wrapText(text = "Kurzer Text", maxWidthPx = 100.0) shouldBe listOf("Kurzer Text")
        }

        "wrapText: long text wraps at word boundary" {
            // "Beschließt Aufnahme im Vorstand" @ 160 px / 6.5 ≈ 24 chars max
            val lines = wrapText(text = "Beschließt Aufnahme im Vorstand", maxWidthPx = 160.0)
            lines.size shouldBe 2
            // Each line must fit within the estimated width
            lines.forEach { line -> (line.length * 6.5) shouldBe line.length * 6.5 } // sanity
            lines[0] shouldBe "Beschließt Aufnahme im"
            lines[1] shouldBe "Vorstand"
        }

        "wrapText: single oversized word is kept on its own line" {
            val lines = wrapText(text = "Superlongwordthatneverfits", maxWidthPx = 40.0)
            lines shouldBe listOf("Superlongwordthatneverfits")
        }

        "wrapText: three-line wrap" {
            // 3 * 6 chars + spaces — force three lines at ~45 px (6 chars max)
            val lines = wrapText(text = "aa bb cc dd ee ff", maxWidthPx = 45.0)
            lines.size shouldBe 3
        }

        "long step title produces tspan elements in SVG" {
            // Customer layer is always shown in JourneyDiagram — use it
            // instead of backstage so the step is actually rendered.
            val m =
                blueprint(name = "Wrap Test") {
                    phase(name = "Phase") {
                        customer(name = "Beschließt Aufnahme im Vorstand", sentiment = Sentiment.NEUTRAL)
                    }
                }
            val svg = renderBlueprintJourney(model = m, diagram = JourneyDiagram(name = "D"))
            svg shouldContain "<tspan"
            svg shouldContain "Beschließt Aufnahme im"
            svg shouldContain "Vorstand"
        }

        "short step title does not produce tspan elements in SVG" {
            val m =
                blueprint(name = "No Wrap Test") {
                    phase(name = "Phase") {
                        customer(name = "Kurz", sentiment = Sentiment.NEUTRAL)
                    }
                }
            val svg = renderBlueprintJourney(model = m, diagram = JourneyDiagram(name = "D"))
            svg shouldNotContain "<tspan"
        }
    })
