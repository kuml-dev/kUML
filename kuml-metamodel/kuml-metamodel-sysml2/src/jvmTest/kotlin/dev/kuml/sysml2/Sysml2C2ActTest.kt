package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.ActSeverity
import dev.kuml.sysml2.constraint.Sysml2ActChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.ActEdgeAdapter
import dev.kuml.sysml2.edge.Sysml2ArrowHead
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

/** Wave C2 — SysML 2 ACT: metamodel, DSL, printer, edge adapter and checker. */
class Sysml2C2ActTest :
    FunSpec({

        fun codes(
            model: Sysml2Model,
            severity: ActSeverity? = null,
        ) = Sysml2ActChecker.check(model = model).filter { severity == null || it.severity == severity }.map { it.code }

        fun sample(): Sysml2Model =
            sysml2Model(name = "C2") {
                val region = interruptibleRegion(name = "R")
                val inner = interruptibleRegion(name = "Inner", showLabel = false, parent = region)
                val send = sendSignal(name = "Send", signal = "Ping", region = region)
                val accept = acceptEvent(name = "Accept", signal = "Pong", region = inner)
                val time = acceptTimeEvent(name = "Timeout", time = "after 5 min", region = region)
                val call = callBehavior(name = "Call", activityId = "Other")
                val work =
                    actionDef(
                        name = "Work",
                        pins =
                            listOf(
                                ActionPin(name = "out", direction = PinDirection.Output, side = PinSide.Bottom),
                                ActionPin(name = "in", direction = PinDirection.Input, side = PinSide.Top),
                            ),
                    )
                val handler = actionDef(name = "Handler")
                val decide = decisionNode(name = "ok?", showName = true)
                controlFlow(name = "a", source = send, target = accept)
                controlFlow(name = "b", source = time, target = call, interrupts = true)
                objectFlow(name = "o", source = work, target = work, sourcePin = "out", targetPin = "in")
                controlFlow(name = "c", source = call, target = decide)
                exceptionHandler(protectedNode = work, handler = handler, type = "IOError", handlerPin = null)
                actDiagram(name = "A") {
                    include(send)
                    include(accept)
                    include(time)
                    include(call)
                    include(work)
                    include(handler)
                    include(decide)
                }
            }

        test("DSL: all new constructs land in the model with their attributes") {
            val m = sample()
            val defs = m.definitions.filterIsInstance<ActionDefinition>().associateBy { it.id }
            defs.getValue("Send").kind shouldBe ActivityNodeKind.SendSignal
            defs.getValue("Send").signal shouldBe "Ping"
            defs.getValue("Accept").kind shouldBe ActivityNodeKind.AcceptEvent
            defs.getValue("Timeout").timeExpression shouldBe "after 5 min"
            defs.getValue("Call").calledActivityId shouldBe "Other"
            defs.getValue("ok?").showName shouldBe true
            defs.getValue("Accept").interruptibleRegionId shouldBe "Inner"
            val regions = m.definitions.filterIsInstance<InterruptibleRegionDefinition>().associateBy { it.id }
            regions.getValue("Inner").parentRegionId shouldBe "R"
            regions.getValue("Inner").showLabel shouldBe false
            m.usages
                .filterIsInstance<ExceptionHandlerUsage>()
                .single()
                .exceptionType shouldBe "IOError"
            m.usages
                .filterIsInstance<ControlFlowUsage>()
                .first { it.name == "b" }
                .interrupts shouldBe true
        }

        test("pins: Auto resolves to left for inputs and right for outputs, explicit sides win") {
            ActionPin(name = "i", direction = PinDirection.Input).effectiveSide shouldBe PinSide.Left
            ActionPin(name = "o", direction = PinDirection.Output).effectiveSide shouldBe PinSide.Right
            ActionPin(name = "o", direction = PinDirection.Output, side = PinSide.Top).effectiveSide shouldBe PinSide.Top
        }

        test("printer: non-default values are printed, defaults are not") {
            val dsl = Sysml2DslPrinter.print(sample())
            for (token in listOf(
                "interruptibleRegion(",
                "parent = ",
                "region = ",
                "signal = \"Ping\"",
                "timeExpression = \"after 5 min\"",
                "calledActivityId = \"Other\"",
                "showName = true",
                "side = PinSide.Bottom",
                "interrupts = true",
                "sourcePin = \"out\"",
                "exceptionHandlerById(",
                "type = \"IOError\"",
            )) {
                dsl shouldContain token
            }
            val plain = Sysml2DslPrinter.print(sysml2Model(name = "P") { actionDef(name = "X") })
            for (token in listOf("signal", "showName", "region", "PinSide", "interrupts")) {
                (plain.contains(token)) shouldBe false
            }
        }

        test("JSON: a pre-C2 document without the new fields still deserializes with defaults") {
            val old =
                """{"name":"Old","definitions":[
                    {"type":"dev.kuml.sysml2.ActionDefinition","id":"A","name":"A","pins":[{"name":"p"}]}],
                  "usages":[{"type":"dev.kuml.sysml2.ObjectFlowUsage","id":"o","name":"o","sourceNodeId":"A","targetNodeId":"A"}],
                  "diagrams":[]}"""
            val m = Json.decodeFromString<Sysml2Model>(old)
            val a = m.definitions.filterIsInstance<ActionDefinition>().single()
            a.signal.shouldBeNull()
            a.timeExpression.shouldBeNull()
            a.calledActivityId.shouldBeNull()
            a.interruptibleRegionId.shouldBeNull()
            a.showName shouldBe false
            a.pins.single().side shouldBe PinSide.Auto
            val f = m.usages.filterIsInstance<ObjectFlowUsage>().single()
            f.interrupts shouldBe false
            f.sourcePin.shouldBeNull()
        }

        test("JSON: the C2 model round-trips") {
            val model = sample()
            val json = Json.encodeToString(Sysml2Model.serializer(), model)
            Json.decodeFromString<Sysml2Model>(json) shouldBe model
        }

        test("edge adapter: interrupting flows and handlers are lightning edges with an open arrow") {
            val m = sample()
            val d = m.diagrams.filterIsInstance<ActDiagram>().single()
            val adapter = ActEdgeAdapter(model = m, diagram = d)
            val b = adapter.metadataFor("controlFlow:Timeout::Call").shouldNotBeNullMeta()
            b.lightning shouldBe true
            b.arrowHead shouldBe Sysml2ArrowHead.OpenAngle
            val h = adapter.metadataFor("exceptionHandler:Work::Handler").shouldNotBeNullMeta()
            h.lightning shouldBe true
            h.label shouldBe "IOError"
            adapter.metadataFor("controlFlow:Send::Accept").shouldNotBeNullMeta().lightning shouldBe false
        }

        test("checker: the sample model is clean") {
            codes(sample(), ActSeverity.Error) shouldBe emptyList()
        }

        test("checker: missing signal / time expression / oversized text") {
            val m =
                sysml2Model(name = "M") {
                    val a = actionDef(name = "S", kind = ActivityNodeKind.SendSignal)
                    val b = actionDef(name = "T", kind = ActivityNodeKind.AcceptTimeEvent)
                    val c = sendSignal(name = "L", signal = "x".repeat(81))
                    actDiagram(name = "A") {
                        include(a)
                        include(b)
                        include(c)
                    }
                }
            codes(m) shouldContain 301
            codes(m) shouldContain 302
            codes(m) shouldContain 303
        }

        test("checker: accept events without an incoming flow are valid") {
            val m =
                sysml2Model(name = "M") {
                    val a = acceptEvent(name = "E", signal = "s")
                    val t = acceptTimeEvent(name = "T", time = "after 1 s")
                    actDiagram(name = "A") {
                        include(a)
                        include(t)
                    }
                }
            codes(m) shouldBe emptyList()
        }

        test("checker: region cycle and depth") {
            val cyc =
                Sysml2Model(
                    name = "C",
                    definitions =
                        listOf(
                            InterruptibleRegionDefinition(id = "a", name = "a", parentRegionId = "b"),
                            InterruptibleRegionDefinition(id = "b", name = "b", parentRegionId = "a"),
                            ActionDefinition(id = "n", name = "n", interruptibleRegionId = "a"),
                        ),
                    diagrams = listOf(ActDiagram(name = "A", elementIds = listOf("n"))),
                )
            codes(cyc) shouldContain 304
            val deep =
                sysml2Model(name = "D") {
                    val r1 = interruptibleRegion(name = "r1")
                    val r2 = interruptibleRegion(name = "r2", parent = r1)
                    val r3 = interruptibleRegion(name = "r3", parent = r2)
                    val r4 = interruptibleRegion(name = "r4", parent = r3)
                    val r5 = interruptibleRegion(name = "r5", parent = r4)
                    val n = actionDef(name = "n", region = r5)
                    actDiagram(name = "A") { include(n) }
                }
            codes(deep) shouldContain 304
        }

        test("checker: handler rules (self, wrong kind, dangling, too many)") {
            val m =
                sysml2Model(name = "H") {
                    val p = actionDef(name = "P")
                    val d = decisionNode(name = "D")
                    exceptionHandler(protectedNode = p, handler = p, type = "E", id = "h1")
                    exceptionHandler(protectedNode = p, handler = d, type = "E", id = "h2")
                    actDiagram(name = "A") {
                        include(p)
                        include(d)
                    }
                }
            codes(m) shouldContain 305
            codes(m) shouldContain 306
            val many =
                sysml2Model(name = "M") {
                    val p = actionDef(name = "P")
                    val hs = (1..9).map { actionDef(name = "H$it") }
                    hs.forEach { exceptionHandler(protectedNode = p, handler = it, type = "E") }
                    actDiagram(name = "A") {
                        include(p)
                        hs.forEach { include(it) }
                    }
                }
            codes(many) shouldContain 309
        }

        test("checker: pin references and pin counts") {
            val m =
                sysml2Model(name = "P") {
                    val a = actionDef(name = "A", pins = listOf(ActionPin(name = "in", direction = PinDirection.Input)))
                    val b =
                        actionDef(
                            name = "B",
                            pins = (1..9).map { ActionPin(name = "p$it", direction = PinDirection.Input, side = PinSide.Top) },
                        )
                    objectFlow(name = "o", source = a, target = b, sourcePin = "in", targetPin = "nope")
                    actDiagram(name = "A") {
                        include(a)
                        include(b)
                    }
                }
            val c = codes(m)
            c shouldContain 307
            c shouldContain 308
        }

        test("checker: warnings for unresolved call, interrupt without region, showName on actions") {
            val m =
                sysml2Model(name = "W") {
                    val a = callBehavior(name = "C", activityId = "missing")
                    val b = actionDef(name = "B", showName = true)
                    controlFlow(name = "f", source = a, target = b, interrupts = true)
                    actDiagram(name = "A") {
                        include(a)
                        include(b)
                    }
                }
            val w = codes(m, ActSeverity.Warning)
            w shouldContain 311
            w shouldContain 312
            w shouldContain 314
            codes(m, ActSeverity.Error) shouldNotContain 311
        }

        test("checker: dangling region reference is an error") {
            val m =
                Sysml2Model(
                    name = "D",
                    definitions = listOf(ActionDefinition(id = "n", name = "n", interruptibleRegionId = "ghost")),
                    diagrams = listOf(ActDiagram(name = "A", elementIds = listOf("n"))),
                )
            codes(m) shouldContain 310
        }
    })

private fun dev.kuml.sysml2.edge.Sysml2EdgeMetadata?.shouldNotBeNullMeta(): dev.kuml.sysml2.edge.Sysml2EdgeMetadata {
    checkNotNull(this) { "edge metadata missing" }
    return this
}
