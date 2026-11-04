@file:Suppress("RequireNamedArguments")

package dev.kuml.core.script.print

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.dsl.print.BlueprintDslPrinter
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintLabels
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintLine
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.blueprint.model.ConnectionStyle
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.blueprint.model.TouchpointSymbol
import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.InProcessScriptEvaluator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Nacharbeit 6: a Service Blueprint survives DSL -> model -> [BlueprintDslPrinter] -> script -> model.
 * Blueprint ids are deterministic, so the comparison is full data-class equality (the fixtures use
 * no `layout` block, which the printer does not serialize).
 */
class BlueprintPrinterRoundTripTest :
    FunSpec({
        fun reparse(printed: String): BlueprintModel {
            val result = InProcessScriptEvaluator.evaluate(source = printed, fileName = "blueprint.kuml.kts")
            require(result is EvaluatedScript.Success) { "re-parse failed: $result\n---\n$printed" }
            val extracted = result.diagram
            require(extracted is ExtractedDiagram.Blueprint) { "expected a Blueprint diagram, got: $extracted" }
            return extracted.model
        }

        fun roundTrip(original: BlueprintModel): String {
            val printed = BlueprintDslPrinter.print(original)
            reparse(printed) shouldBe original
            return printed
        }

        fun fullModel(texts: List<String> = emptyList()): BlueprintModel =
            blueprint(name = "Onboarding") {
                val kunde = actor(name = "Kunde")
                val agent = actor(name = "Agent", role = ActorRole.STAFF, description = "Front desk")
                actor(name = "CRM", role = ActorRole.SYSTEM)
                val web = channel(name = "Web", kind = ChannelKind.WEB)
                channel(name = "Other")
                val tp = touchpoint(name = "Site", channel = web, symbol = TouchpointSymbol.DIAMOND)
                touchpoint(name = "Loose")
                var s1 = ""
                var s2 = ""
                var s3 = ""
                var s4 = ""
                phase(name = "Discover") {
                    s1 =
                        customer(
                            name = "Sees ad",
                            sentiment = Sentiment.NEGATIVE,
                            touchpoints = listOf(tp),
                            actor = kunde,
                            pain = "slow",
                            opportunity = "fast",
                        )
                    s2 = frontstage(name = "Greets", touchpoints = listOf(tp), actor = agent, opportunity = "smile")
                }
                phase(name = "Decide") {
                    s3 = backstage(name = "Prepares", actor = agent)
                    s4 = support(name = "Stores")
                    step(name = "Plain")
                    step(name = "Odd backstage", layer = BlueprintLayer.BACKSTAGE, touchpoints = listOf(tp), pain = "x")
                    step(name = "Pained front", layer = BlueprintLayer.FRONTSTAGE, pain = "p", sentiment = Sentiment.POSITIVE)
                }
                phase(name = "Empty") {}
                connection(from = s1, to = s2)
                connection(from = s2, to = s3, style = ConnectionStyle.DASHED)
                connection(from = s3, to = s4)
                journeyDiagram(
                    name = "J",
                    layers = setOf(BlueprintLayer.CUSTOMER_ACTIONS, BlueprintLayer.FRONTSTAGE),
                    emotionCurve = false,
                    labels = BlueprintLabels.GERMAN,
                )
                blueprintDiagram(
                    name = "B",
                    lines = setOf(BlueprintLine.VISIBILITY),
                    emotionCurve = true,
                    labels = BlueprintLabels(customerActions = "X \"q\" \$y", painPrefix = "Pain!"),
                )
                journeyDiagram(name = "J2")
                blueprintDiagram(name = "B2")
                texts.forEach { note(it, s1, s3) }
            }

        test("model without notes round-trips with full equality") {
            val printed = roundTrip(fullModel())
            printed shouldNotContain "note("
            printed shouldNotContain "NoteKind"
        }

        test("every NoteKind round-trips, anchored on several steps and free-standing") {
            val original =
                blueprint(name = "Kinds") {
                    var a = ""
                    var b = ""
                    phase(name = "P") {
                        a = customer(name = "A", sentiment = Sentiment.NEUTRAL)
                        b = backstage(name = "B")
                    }
                    for (k in NoteKind.entries) {
                        note("anchored ${k.name}", a, b, kind = k)
                        note("free ${k.name}", kind = k)
                    }
                    journeyDiagram(name = "J")
                }
            val printed = roundTrip(original)
            printed shouldContain "kind = NoteKind.WARNING"
        }

        test("markup, escapes, multi-line, empty and unicode text survive") {
            val texts =
                listOf(
                    "*bold* and `code` and [a](b) and <b>x</b>",
                    "back\\slash \"quoted\" \$x \${y} C:\\Users 0..* snake_case",
                    "line1\nline2\r\nline3\ttabbed",
                    "",
                    "Unicode: äöüß € 日本語 \uD83D\uDE00",
                )
            val printed = roundTrip(fullModel(texts))
            printed shouldContain "\\\$x"
        }

        test("explicit note ids and kind are printed; kind only when not NOTE") {
            val m =
                blueprint(name = "N") {
                    phase(name = "P") { customer(name = "A", sentiment = Sentiment.NEUTRAL) }
                    note("plain", id = "n-plain")
                    note("risky", kind = NoteKind.RISK)
                    journeyDiagram(name = "J")
                }
            val printed = roundTrip(m)
            printed.lines().single { "\"plain\"" in it } shouldContain "id = \"n-plain\""
            printed.lines().single { "\"plain\"" in it } shouldNotContain "kind ="
            printed.lines().single { "\"risky\"" in it } shouldContain "kind = NoteKind.RISK"
        }

        test("unrepresentable fields get a TODO instead of being silently dropped") {
            val base = fullModel()
            val patched =
                base.copy(
                    steps = base.steps.mapIndexed { i, s -> if (i == 0) s.copy(name = null) else s },
                    diagrams =
                        base.diagrams.map {
                            if (it is dev.kuml.blueprint.model.JourneyDiagram) it.copy(elementIds = listOf("x\nobject Injected")) else it
                        },
                )
            val printed = BlueprintDslPrinter.print(patched)
            printed shouldContain "// TODO: step 'step_0' has a null name"
            printed shouldContain "elementIds are not serialized"
            printed.lines().filter { "Injected" in it }.forEach { it.trimStart().startsWith("//") shouldBe true }
        }
    })
