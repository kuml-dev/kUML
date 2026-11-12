@file:Suppress("RequireNamedArguments")

package dev.kuml.blueprint.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** PAL wave 2G-c: card metrics, band heights, stacking, footer badge layout and the label set. */
class BlueprintGridConstantsTest :
    StringSpec({
        fun step(
            id: String,
            name: String = id,
            phase: String = "p0",
            layer: BlueprintLayer = BlueprintLayer.BACKSTAGE,
            actor: String? = null,
            tps: List<String> = emptyList(),
            pain: String? = null,
            opportunity: String? = null,
        ) = JourneyStep(
            id = id,
            name = name,
            phaseRef = phase,
            layer = layer,
            actorRef = actor,
            touchpointRefs = tps,
            painPoint = pain,
            opportunity = opportunity,
        )

        fun model(vararg steps: JourneyStep) =
            BlueprintModel(
                name = "M",
                phases = listOf(Phase(id = "p0", name = "P0", order = 0)),
                steps = steps.toList(),
            )

        "a plain card keeps the classic minimum height and the band the classic 112 px" {
            val s = step("a")
            BlueprintGridConstants.cardHeight(s) shouldBe BlueprintGridConstants.CARD_MIN_HEIGHT
            val h = BlueprintGridConstants.layerHeights(model(s), listOf(BlueprintLayer.BACKSTAGE))
            h.getValue(BlueprintLayer.BACKSTAGE) shouldBe BlueprintGridConstants.ROW_HEIGHT
        }

        "title lines, marker row and footer each add height" {
            val long = "Beantwortet die Anfrage des Mitglieds ausfuehrlich und prueft dabei sorgfaeltig alle Unterlagen"
            val plain = step("a", name = long)
            val withFooter = step("b", name = long, actor = "x")
            val withMarker = step("c", name = long, actor = "x", pain = "p")
            BlueprintGridConstants.cardHeight(plain) shouldBeGreaterThan BlueprintGridConstants.CARD_MIN_HEIGHT
            BlueprintGridConstants.cardHeight(withFooter) shouldBe
                BlueprintGridConstants.cardHeight(plain) + BlueprintGridConstants.CARD_FOOTER_GAP +
                BlueprintGridConstants.CARD_FOOTER_HEIGHT
            BlueprintGridConstants.cardHeight(withMarker) shouldBe
                BlueprintGridConstants.cardHeight(withFooter) + BlueprintGridConstants.MARKER_ROW_RESERVE
        }

        "layer height is the tallest stack of the layer, never below 112" {
            val m = model(step("a"), step("b"), step("c", layer = BlueprintLayer.FRONTSTAGE))
            val h = BlueprintGridConstants.layerHeights(m, listOf(BlueprintLayer.FRONTSTAGE, BlueprintLayer.BACKSTAGE))
            h.getValue(BlueprintLayer.FRONTSTAGE) shouldBe 112.0
            val stack = 2 * 80.0 + BlueprintGridConstants.CARD_STACK_GAP
            h.getValue(BlueprintLayer.BACKSTAGE) shouldBe
                BlueprintGridConstants.CARD_MARGIN_TOP + stack + BlueprintGridConstants.CARD_MARGIN_BOTTOM
            h.values.forEach { it shouldBeGreaterThanOrEqualTo 112.0 }
        }

        "stacked cards keep declaration order, do not overlap and fill the band" {
            val m = model(step("a"), step("b"), step("c"))
            val band = BlueprintGridConstants.layerHeights(m, listOf(BlueprintLayer.BACKSTAGE)).getValue(BlueprintLayer.BACKSTAGE)
            val cards = BlueprintGridConstants.stackedCards(m, "p0", BlueprintLayer.BACKSTAGE, band)
            cards.map { it.step.id } shouldBe listOf("a", "b", "c")
            cards.zipWithNext().forEach { (u, l) ->
                (l.yOffset >= u.yOffset + u.height + BlueprintGridConstants.CARD_STACK_GAP - 0.001) shouldBe true
            }
            val last = cards.last()
            (last.yOffset + last.height + BlueprintGridConstants.CARD_MARGIN_BOTTOM) shouldBe band
        }

        "a lone card fills the card area of a taller band" {
            val m = model(step("a"), step("b", phase = "p1"), step("c", phase = "p1"))
            val m2 = m.copy(phases = m.phases + Phase(id = "p1", name = "P1", order = 1))
            val band = BlueprintGridConstants.layerHeights(m2, listOf(BlueprintLayer.BACKSTAGE)).getValue(BlueprintLayer.BACKSTAGE)
            val lone = BlueprintGridConstants.stackedCards(m2, "p0", BlueprintLayer.BACKSTAGE, band).single()
            lone.height shouldBe band - BlueprintGridConstants.CARD_MARGIN_TOP - BlueprintGridConstants.CARD_MARGIN_BOTTOM
        }

        "footer badges: all fit, or the surplus collapses into one +n chip, at least one slot remains" {
            val avail = BlueprintGridConstants.cardTextWidth()
            BlueprintGridConstants.footerBadgeLayout(0, avail, 0.0) shouldBe BlueprintGridConstants.FooterBadges(0, 0)
            BlueprintGridConstants.footerBadgeLayout(3, avail, 0.0) shouldBe BlueprintGridConstants.FooterBadges(3, 0)
            // actor icon + 60 px name leave (168 - 80) / 20 = 4 slots: five badges become 3 + "+2"
            val reserved =
                BlueprintGridConstants.FOOTER_ICON_SIZE + BlueprintGridConstants.FOOTER_ICON_GAP +
                    BlueprintGridConstants.FOOTER_NAME_MIN_WIDTH
            val five = BlueprintGridConstants.footerBadgeLayout(5, avail, reserved)
            five shouldBe BlueprintGridConstants.FooterBadges(3, 2)
            five.slots shouldBe 4
            BlueprintGridConstants.footerBadgeLayout(4, avail, reserved) shouldBe BlueprintGridConstants.FooterBadges(4, 0)
            BlueprintGridConstants.footerBadgeLayout(9, 10.0, 0.0) shouldBe BlueprintGridConstants.FooterBadges(0, 9)
        }

        "wrapWords never loses text, bounds hostile input and splits overlong words only on request" {
            BlueprintGridConstants.wrapWords("aa bb cc", 5) shouldBe listOf("aa bb", "cc")
            BlueprintGridConstants.wrapWords("Superlongwordthatneverfits", 8) shouldBe listOf("Superlongwordthatneverfits")
            val split = BlueprintGridConstants.wrapWords("Superlongwordthatneverfits", 8, splitLongWords = true)
            split.all { it.length <= 8 } shouldBe true
            split.joinToString("").replace("-", "") shouldBe "Superlongwordthatneverfits"
            val hostile = step("h", name = "word ".repeat(10_000))
            BlueprintGridConstants.cardTitleLines(hostile) shouldHaveSize BlueprintGridConstants.MAX_TITLE_LINES
            BlueprintGridConstants.cardHeight(hostile) shouldBeGreaterThan 80.0
            (BlueprintGridConstants.laneLabelLines("x".repeat(10_000)).size <= BlueprintGridConstants.MAX_LABEL_LINES) shouldBe true
            (BlueprintGridConstants.laneLabelLines("word ".repeat(1_000)).size <= BlueprintGridConstants.MAX_LABEL_LINES) shouldBe true
        }

        "labels: English is the default, German differs, JSON roundtrip keeps them, old JSON without labels stays readable" {
            BlueprintLabels() shouldBe BlueprintLabels.ENGLISH
            BlueprintLabels.ENGLISH.layer(BlueprintLayer.SUPPORT_PROCESSES) shouldBe "Support Processes"
            BlueprintLabels.ENGLISH.line(BlueprintLine.INTERNAL_INTERACTION) shouldBe "Line of Internal Interaction"
            BlueprintLabels.GERMAN.layer(BlueprintLayer.CUSTOMER_ACTIONS) shouldBe "Kundenaktionen"
            BlueprintLabels.GERMAN.line(BlueprintLine.VISIBILITY) shouldBe "Sichtbarkeitslinie"
            val json = Json { encodeDefaults = true }
            val d: BlueprintDiagram = BlueprintDiagramFull(name = "D", labels = BlueprintLabels.GERMAN)
            json.decodeFromString(BlueprintDiagram.serializer(), json.encodeToString(BlueprintDiagram.serializer(), d)) shouldBe d
            val old = """{"type":"BlueprintDiagramFull","name":"D"}"""
            json.decodeFromString(BlueprintDiagram.serializer(), old).labels shouldBe BlueprintLabels.ENGLISH
        }

        "a long actor name wraps, keeps the full text and grows the card; short names keep the classic height" {
            val full = "Central Control Unit Station North Regional"
            val s1 = step("a", actor = "x", tps = listOf("t1", "t2"))
            val lines = BlueprintGridConstants.actorNameLines(name = full, badgeCount = 2)
            (lines.size > 1) shouldBe true
            lines.joinToString(" ") shouldBe full
            val longH = BlueprintGridConstants.cardHeight(step = s1, actorName = full)
            val shortH = BlueprintGridConstants.cardHeight(step = s1, actorName = "Customer")
            longH shouldBeGreaterThan shortH
            shortH shouldBe BlueprintGridConstants.cardHeight(step = s1)
            BlueprintGridConstants.footerHeight(lineCount = 1) shouldBe BlueprintGridConstants.CARD_FOOTER_HEIGHT
        }

        "an 80 character actor name is capped at three lines with an ellipsis on the last" {
            val lines = BlueprintGridConstants.actorNameLines(name = "Operations ".repeat(8).trim(), badgeCount = 2)
            lines shouldHaveSize BlueprintGridConstants.MAX_ACTOR_NAME_LINES
            lines.last().endsWith("…") shouldBe true
            BlueprintGridConstants.actorNameLines(name = null, badgeCount = 0) shouldBe emptyList()
        }

        "the badge slots use the same width as the card text width" {
            BlueprintGridConstants.cardTextWidth() shouldBe 168.0
        }

        "stacked cards with a long actor name do not overlap and the band grows" {
            val full = "Central Control Unit Station North Regional"
            val a = step("a", actor = "x", tps = listOf("t1", "t2"))
            val b = step("b")
            val m =
                BlueprintModel(
                    name = "M",
                    phases = listOf(Phase(id = "p0", name = "P0", order = 0)),
                    actors = listOf(Actor(id = "x", name = full)),
                    touchpoints = listOf(Touchpoint(id = "t1", name = "T1"), Touchpoint(id = "t2", name = "T2")),
                    steps = listOf(a, b),
                )
            val layers = listOf(BlueprintLayer.BACKSTAGE)
            val plain = BlueprintGridConstants.layerHeights(model(a, b), layers).getValue(BlueprintLayer.BACKSTAGE)
            val grown = BlueprintGridConstants.layerHeights(m, layers).getValue(BlueprintLayer.BACKSTAGE)
            grown shouldBeGreaterThan plain
            val cards = BlueprintGridConstants.stackedCards(m, "p0", BlueprintLayer.BACKSTAGE, grown)
            cards shouldHaveSize 2
            cards[1].yOffset shouldBeGreaterThanOrEqualTo cards[0].yOffset + cards[0].height + BlueprintGridConstants.CARD_STACK_GAP
        }
    })
