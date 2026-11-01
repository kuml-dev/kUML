package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.Sysml2SeqChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/** PAL wave 2F — `foundMessage(source)`, `timeObservation(label)`, `acceptEvent(region)`: DSL, printer, JSON, checker. */
class Sysml2Wave2FSeqModelTest :
    FunSpec({
        fun sample(): Sysml2Model =
            sysml2Model(name = "W2F") {
                val a = lifelineDef(name = "a")
                val b = lifelineDef(name = "b")
                foundMessage(label = "open", target = b, seqNo = 0, source = a)
                foundMessage(label = "alarm", target = b, seqNo = 1)
                timeObservation(lifeline = a, seqNo = 0, name = "tOpen", label = "now")
                timeObservation(lifeline = b, seqNo = 1, name = "tPlain")
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                }
            }

        test("foundMessage with a source keeps sender and receiver apart, without a source it stays the historic form") {
            val m = sample().usages.filterIsInstance<MessageUsage>()
            m.first { it.messageLabel == "open" }.let {
                it.sourceLifelineId shouldBe "a"
                it.targetLifelineId shouldBe "b"
                it.kind shouldBe MessageKind.Found
            }
            m.first { it.messageLabel == "alarm" }.let { it.sourceLifelineId shouldBe it.targetLifelineId }
        }

        test("the checker accepts a found message with a sender but still rejects a lost message with two lifelines") {
            val clean = Sysml2SeqChecker.check(model = sample(), diagram = sample().diagrams.filterIsInstance<SeqDiagram>().single())
            clean.none { it.code == 411 } shouldBe true
            val bad =
                sysml2Model(name = "Bad") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    messageById(
                        label = "x",
                        sourceLifelineId = a.id,
                        targetLifelineId = b.id,
                        seqNo = 0,
                        kind = MessageKind.Lost,
                    )
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            Sysml2SeqChecker
                .check(
                    model = bad,
                    diagram = bad.diagrams.filterIsInstance<SeqDiagram>().single(),
                ).any { it.code == 411 } shouldBe
                true
        }

        test("timeObservation label survives the printer, an old JSON without it still reads") {
            val model = sample()
            val printed = Sysml2DslPrinter.print(model)
            printed shouldContain "label = \"now\""
            val plainLine = printed.lines().first { it.contains("tPlain") }
            plainLine shouldNotContain "label ="
            val json = Json { encodeDefaults = false }
            val obs = model.usages.filterIsInstance<TimeObservationUsage>().first { it.name == "tPlain" }
            val text = json.encodeToString(TimeObservationUsage.serializer(), obs)
            text shouldNotContain "label"
            json.decodeFromString(TimeObservationUsage.serializer(), text).label shouldBe null
            val withLabel = model.usages.filterIsInstance<TimeObservationUsage>().first { it.name == "tOpen" }
            json
                .decodeFromString(
                    TimeObservationUsage.serializer(),
                    json.encodeToString(TimeObservationUsage.serializer(), withLabel),
                ).label shouldBe "now"
        }

        test("acceptEvent(region) puts the node into the region") {
            val model =
                sysml2Model(name = "R") {
                    val r = interruptibleRegion(name = "R1")
                    val e = acceptEvent(name = "Event", region = r)
                    e.interruptibleRegionId shouldBe r.id
                }
            model.definitions
                .filterIsInstance<ActionDefinition>()
                .single()
                .interruptibleRegionId shouldBe "R1"
        }
    })
