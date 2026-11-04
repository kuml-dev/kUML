@file:Suppress("RequireNamedArguments")

package dev.kuml.blueprint.dsl.print

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.ConnectionStyle
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.core.model.note.NoteKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** String-level checks for [BlueprintDslPrinter]; the evaluated round trip lives in kuml-core-script. */
class BlueprintDslPrinterTest :
    StringSpec({
        "small model without notes prints the pinned format" {
            val m =
                blueprint(name = "Mini") {
                    val a = actor(name = "Staff", role = ActorRole.STAFF)
                    var s0 = ""
                    var s1 = ""
                    phase(name = "P") {
                        s0 = customer(name = "Go", sentiment = Sentiment.POSITIVE)
                        s1 = backstage(name = "Do", actor = a)
                    }
                    connection(from = s0, to = s1, style = ConnectionStyle.DASHED)
                    journeyDiagram(name = "J")
                }
            BlueprintDslPrinter.print(m) shouldBe
                """
                blueprint(name = "Mini") {
                    val actor0 = actor("Staff", role = ActorRole.STAFF)
                    lateinit var step0: String
                    lateinit var step1: String
                    phase("P") {
                        step0 = customer("Go", sentiment = Sentiment.POSITIVE)
                        step1 = backstage("Do", actor = actor0)
                    }
                    connection(from = step0, to = step1, style = ConnectionStyle.DASHED)
                    journeyDiagram(name = "J")
                }

                """.trimIndent()
        }

        "notes print text, step vars, explicit id and kind only when not NOTE" {
            val m =
                blueprint(name = "N") {
                    var s = ""
                    phase(name = "P") { s = customer(name = "A", sentiment = Sentiment.NEUTRAL) }
                    note("hello", s)
                    note("warn", kind = NoteKind.WARNING)
                }
            val printed = BlueprintDslPrinter.print(m)
            printed shouldContain "    note(\"hello\", step0, id = \"note-1\")"
            printed shouldContain "    note(\"warn\", id = \"note-2\", kind = NoteKind.WARNING)"
            printed.lines().single { "\"hello\"" in it } shouldNotContain "kind ="
        }

        "a note anchor without a step falls back to the quoted id" {
            val base = blueprint(name = "N") { note("x", "step_9") }
            BlueprintDslPrinter.print(base) shouldContain "note(\"x\", \"step_9\", id = \"note-1\")"
        }
    })
