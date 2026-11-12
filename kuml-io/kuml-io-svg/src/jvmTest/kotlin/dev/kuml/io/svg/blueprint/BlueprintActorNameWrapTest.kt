package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintGridConstants
import dev.kuml.blueprint.model.BlueprintLabels
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.io.svg.KumlSvgRenderer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Actor-name wrapping in the card footer, same-cell stacking and label passthrough, end to end through the SVG renderer. */
class BlueprintActorNameWrapTest :
    StringSpec({
        val full = "Central Control Unit Station North Regional"

        fun model(actorName: String = full): BlueprintModel =
            blueprint(name = "Wrap") {
                val unit = actor(name = actorName, role = ActorRole.SYSTEM)
                val web = channel(name = "Web", kind = ChannelKind.WEB)
                val one = touchpoint(name = "Portal", channel = web)
                val two = touchpoint(name = "Mailbox", channel = web)
                phase(name = "Phase") {
                    step(name = "First", layer = BlueprintLayer.BACKSTAGE, actor = unit, touchpoints = listOf(one, two))
                    backstage(name = "Second")
                }
                blueprintDiagram(name = "D")
            }

        fun render(
            m: BlueprintModel,
            labels: Boolean = false,
        ): String = KumlSvgRenderer.toSvg(model = m, diagram = m.diagrams.first() as BlueprintDiagramFull)

        fun rects(svg: String): List<Pair<Double, Double>> =
            Regex("""<rect x="[^"]+" y="([\d.]+)" width="[^"]+" height="([\d.]+)" rx="6"""")
                .findAll(svg)
                .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
                .toList()

        "a long actor name is wrapped into tspans without losing text and the card grows" {
            val m = model()
            val svg = render(m)
            val text = Regex("""<text [^>]*class="kuml-small" font-size="10">(.*?)<title>""").findAll(svg).first().groupValues[1]
            val parts = Regex("""<tspan[^>]*>(.*?)</tspan>""").findAll(text).map { it.groupValues[1] }.toList()
            (parts.size > 1) shouldBe true
            parts.joinToString(" ") shouldBe full
            svg shouldNotContain "…"
            val first = m.steps.first()
            val expected =
                BlueprintGridConstants.cardHeight(step = first, actorName = full, badgeCount = 2)
            val heights = rects(svg).map { it.second }
            (heights.any { it >= expected }) shouldBe true
        }

        "actor names are XML-escaped in the wrapped footer" {
            val svg = render(model(actorName = "A<B & C"))
            svg shouldContain "A&lt;B &amp; C"
            svg shouldNotContain "A<B"
        }

        "two cards in the same cell are both drawn and stacked without overlap" {
            val m = model()
            val svg = render(m)
            m.steps.forEach { svg shouldContain """<g id="${it.id}">""" }
            val cards = rects(svg).filter { it.second >= BlueprintGridConstants.CARD_MIN_HEIGHT }.sortedBy { it.first }
            val (r1, r2) = cards.take(2)
            r2.first shouldBeGreaterThan r1.first
            r2.first shouldBeGreaterThanOrEqual r1.first + r1.second + BlueprintGridConstants.CARD_STACK_GAP - 0.01
        }

        "German labels replace the English defaults" {
            val base = model()
            val german =
                base.copy(
                    diagrams =
                        listOf(
                            BlueprintDiagramFull(name = "D", labels = BlueprintLabels.GERMAN),
                        ),
                )
            val svg = KumlSvgRenderer.toSvg(model = german, diagram = german.diagrams.first() as BlueprintDiagramFull)
            svg shouldContain "Sichtbarkeitslinie"
            svg shouldContain BlueprintLabels.GERMAN.layer(BlueprintLayer.BACKSTAGE)
            svg shouldNotContain "Line of Visibility"
            svg shouldNotContain "Customer Actions"
        }
    })
