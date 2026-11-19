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

        test("checker: fork with two incoming flows gets exactly one ACT316 warning") {
            val m =
                sysml2Model(name = "Degrees") {
                    val a = actionDef(name = "A")
                    val b = actionDef(name = "B")
                    val c = actionDef(name = "C")
                    val f = forkNode(name = "F")
                    controlFlow(name = "af", source = a, target = f)
                    objectFlow(name = "bf", source = b, target = f)
                    controlFlow(name = "fc", source = f, target = c)
                    actDiagram(name = "D") {
                        include(a)
                        include(b)
                        include(c)
                        include(f)
                    }
                }
            codes(m) shouldBe listOf(316)
            codes(m, ActSeverity.Warning) shouldBe listOf(316)
        }

        test("checker: fork with one incoming and two outgoing flows is fine") {
            val m =
                sysml2Model(name = "Degrees") {
                    val a = actionDef(name = "A")
                    val c = actionDef(name = "C")
                    val d = actionDef(name = "D")
                    val f = forkNode(name = "F")
                    controlFlow(name = "af", source = a, target = f)
                    controlFlow(name = "fc", source = f, target = c)
                    controlFlow(name = "fd", source = f, target = d)
                    actDiagram(name = "D") {
                        include(a)
                        include(c)
                        include(d)
                        include(f)
                    }
                }
            codes(m) shouldBe emptyList()
        }

        test("checker: accept and accept-time event with two outgoing flows get ACT317, one outgoing flow does not") {
            val m =
                sysml2Model(name = "Degrees") {
                    val c = actionDef(name = "C")
                    val d = actionDef(name = "D")
                    val ev = acceptEvent(name = "Ev", signal = "x")
                    val tm = acceptTimeEvent(name = "Tm", time = "after 5 min")
                    val one = acceptEvent(name = "One", signal = "y")
                    controlFlow(name = "e1", source = ev, target = c)
                    controlFlow(name = "e2", source = ev, target = d)
                    controlFlow(name = "t1", source = tm, target = c)
                    objectFlow(name = "t2", source = tm, target = d)
                    controlFlow(name = "o1", source = one, target = c)
                    actDiagram(name = "D") {
                        include(c)
                        include(d)
                        include(ev)
                        include(tm)
                        include(one)
                    }
                }
            val issues = Sysml2ActChecker.check(model = m).filter { it.code == 317 }
            issues.map { it.elementId } shouldBe listOf("Ev", "Tm")
            issues.all { it.severity == ActSeverity.Warning } shouldBe true
            codes(m) shouldBe listOf(317, 317)
        }

        test("checker: self-loops and flows leaving the diagram are not counted for ACT316 and ACT317") {
            val m =
                sysml2Model(name = "Degrees") {
                    val a = actionDef(name = "A")
                    val outside = actionDef(name = "Outside")
                    val f = forkNode(name = "F")
                    val ev = acceptEvent(name = "Ev", signal = "x")
                    controlFlow(name = "af", source = a, target = f)
                    controlFlow(name = "ff", source = f, target = f)
                    controlFlow(name = "outIn", source = outside, target = f)
                    controlFlow(name = "e1", source = ev, target = a)
                    controlFlow(name = "ee", source = ev, target = ev)
                    controlFlow(name = "eOut", source = ev, target = outside)
                    actDiagram(name = "D") {
                        include(a)
                        include(f)
                        include(ev)
                    }
                }
            codes(m) shouldBe emptyList()
        }

        // 318-320: a control node with 2 incoming and the given number of outgoing flows (neighbours are plain actions).
        fun degreeModel(
            kind: String,
            incoming: Int,
            outgoing: Int,
        ): Sysml2Model =
            sysml2Model(name = "Degrees") {
                val node =
                    when (kind) {
                        "join" -> joinNode(name = "N")
                        "decision" -> decisionNode(name = "N")
                        else -> mergeNode(name = "N")
                    }
                val sources = (1..incoming).map { actionDef(name = "S$it") }
                val targets = (1..outgoing).map { actionDef(name = "T$it") }
                sources.forEachIndexed { i, s ->
                    if (i % 2 ==
                        0
                    ) {
                        controlFlow(name = "in$i", source = s, target = node)
                    } else {
                        objectFlow(name = "in$i", source = s, target = node)
                    }
                }
                targets.forEachIndexed { i, t ->
                    if (i % 2 ==
                        0
                    ) {
                        controlFlow(name = "out$i", source = node, target = t)
                    } else {
                        objectFlow(name = "out$i", source = node, target = t)
                    }
                }
                actDiagram(name = "D") {
                    include(node)
                    sources.forEach { include(it) }
                    targets.forEach { include(it) }
                }
            }

        test("checker: join with two outgoing flows gets ACT318, one outgoing flow does not") {
            val m = degreeModel("join", incoming = 2, outgoing = 2)
            codes(m) shouldBe listOf(318)
            codes(m, ActSeverity.Warning) shouldBe listOf(318)
            codes(degreeModel("join", incoming = 2, outgoing = 1)) shouldBe emptyList()
        }

        test("checker: decision with two incoming flows gets ACT319, one incoming flow does not") {
            val m = degreeModel("decision", incoming = 2, outgoing = 2)
            codes(m) shouldBe listOf(319)
            codes(m, ActSeverity.Warning) shouldBe listOf(319)
            codes(degreeModel("decision", incoming = 1, outgoing = 2)) shouldBe emptyList()
        }

        test("checker: merge with two outgoing flows gets ACT320, one outgoing flow does not") {
            val m = degreeModel("merge", incoming = 2, outgoing = 2)
            codes(m) shouldBe listOf(320)
            codes(m, ActSeverity.Warning) shouldBe listOf(320)
            codes(degreeModel("merge", incoming = 2, outgoing = 1)) shouldBe emptyList()
        }

        test("checker: action with two outgoing flows gets ACT321, one outgoing flow does not") {
            val m =
                sysml2Model(name = "Degrees") {
                    val a = actionDef(name = "A")
                    val b = actionDef(name = "B")
                    val c = actionDef(name = "C")
                    controlFlow(name = "ab", source = a, target = b)
                    objectFlow(name = "ac", source = a, target = c)
                    actDiagram(name = "D") {
                        include(a)
                        include(b)
                        include(c)
                    }
                }
            val issues = Sysml2ActChecker.check(model = m)
            issues.map { it.code } shouldBe listOf(321)
            issues.single().elementId shouldBe "A"
            issues.single().severity shouldBe ActSeverity.Warning
            val one =
                sysml2Model(name = "Degrees") {
                    val a = actionDef(name = "A")
                    val b = actionDef(name = "B")
                    controlFlow(name = "ab", source = a, target = b)
                    actDiagram(name = "D") {
                        include(a)
                        include(b)
                    }
                }
            codes(one) shouldBe emptyList()
        }

        test("checker: an interrupting flow is not counted as an outgoing flow for ACT321") {
            val m =
                sysml2Model(name = "Degrees") {
                    val a = actionDef(name = "A")
                    val b = actionDef(name = "B")
                    val out = actionDef(name = "Out")
                    controlFlow(name = "ab", source = a, target = b)
                    controlFlow(name = "aout", source = a, target = out, interrupts = true)
                    actDiagram(name = "D") {
                        include(a)
                        include(b)
                        include(out)
                    }
                }
            codes(m).contains(321) shouldBe false
        }

        test("checker: self-loops and flows leaving the diagram are not counted for ACT318 to ACT321") {
            val m =
                sysml2Model(name = "Degrees") {
                    val a = actionDef(name = "A")
                    val b = actionDef(name = "B")
                    val outside = actionDef(name = "Outside")
                    val j = joinNode(name = "J")
                    val d = decisionNode(name = "Dec")
                    val mg = mergeNode(name = "Mg")
                    controlFlow(name = "aa", source = a, target = a)
                    controlFlow(name = "ab", source = a, target = b)
                    controlFlow(name = "aOut", source = a, target = outside)
                    controlFlow(name = "jj", source = j, target = j)
                    controlFlow(name = "jb", source = j, target = b)
                    controlFlow(name = "jOut", source = j, target = outside)
                    controlFlow(name = "dd", source = d, target = d)
                    controlFlow(name = "outD", source = outside, target = d)
                    controlFlow(name = "mm", source = mg, target = mg)
                    controlFlow(name = "mb", source = mg, target = b)
                    controlFlow(name = "mOut", source = mg, target = outside)
                    actDiagram(name = "D") {
                        include(a)
                        include(b)
                        include(j)
                        include(d)
                        include(mg)
                    }
                }
            codes(m) shouldBe emptyList()
        }

        // Only the flow-degree codes (ACT316-ACT326); other warnings (311, 312 ...) are irrelevant for these cases.
        fun degreeCodes(model: Sysml2Model) = codes(model).filter { it in 316..326 }

        // Nacharbeit 121a: a node of the given kind with n incoming and m outgoing flows (interrupting optional).
        fun nodeModel(
            kind: String,
            incoming: Int,
            outgoing: Int,
            interruptingOut: Int = 0,
            outsideOut: Int = 0,
            outsideIn: Int = 0,
        ): Sysml2Model =
            sysml2Model(name = "Degrees") {
                val node =
                    when (kind) {
                        "initial" -> initialNode(name = "N")
                        "final" -> finalNode(name = "N")
                        "flowFinal" -> flowFinalNode(name = "N")
                        "call" -> callBehavior(name = "N", activityId = "Other")
                        "send" -> sendSignal(name = "N", signal = "S")
                        "decision" -> decisionNode(name = "N")
                        else -> mergeNode(name = "N")
                    }
                val sources = (1..incoming).map { actionDef(name = "S$it") }
                val targets = (1..outgoing + interruptingOut).map { actionDef(name = "T$it") }
                val outside = actionDef(name = "Outside")
                sources.forEachIndexed { i, s -> controlFlow(name = "in$i", source = s, target = node) }
                targets.forEachIndexed { i, t ->
                    controlFlow(name = "out$i", source = node, target = t, interrupts = i >= outgoing)
                }
                repeat(outsideOut) { controlFlow(name = "xo$it", source = node, target = outside) }
                repeat(outsideIn) { controlFlow(name = "xi$it", source = outside, target = node) }
                actDiagram(name = "D") {
                    include(node)
                    sources.forEach { include(it) }
                    targets.forEach { include(it) }
                }
            }

        test("checker: initial node with an incoming flow gets ACT322, without does not") {
            val issues = Sysml2ActChecker.check(model = nodeModel("initial", incoming = 1, outgoing = 1))
            issues.map { it.code } shouldBe listOf(322)
            issues.single().elementId shouldBe "N"
            issues.single().severity shouldBe ActSeverity.Warning
            codes(nodeModel("initial", incoming = 0, outgoing = 1)) shouldBe emptyList()
        }

        test("checker: initial node with two outgoing flows is not reported (implicit fork per UML 2.5)") {
            codes(nodeModel("initial", incoming = 0, outgoing = 2)) shouldBe emptyList()
        }

        test("checker: final and flow-final nodes with an outgoing flow get ACT323") {
            for (kind in listOf("final", "flowFinal")) {
                val m = nodeModel(kind, incoming = 1, outgoing = 1)
                codes(m) shouldBe listOf(323)
                codes(m, ActSeverity.Warning) shouldBe listOf(323)
                codes(nodeModel(kind, incoming = 1, outgoing = 0)) shouldBe emptyList()
                degreeCodes(nodeModel(kind, incoming = 1, outgoing = 0, interruptingOut = 1)) shouldBe emptyList()
            }
        }

        test("checker: call-behavior and send-signal with two outgoing flows get ACT324 and not ACT321") {
            for (kind in listOf("call", "send")) {
                degreeCodes(nodeModel(kind, incoming = 1, outgoing = 2)) shouldBe listOf(324)
                degreeCodes(nodeModel(kind, incoming = 1, outgoing = 1)) shouldBe emptyList()
                degreeCodes(nodeModel(kind, incoming = 1, outgoing = 1, interruptingOut = 1)) shouldBe emptyList()
            }
        }

        test("checker: decision with one outgoing flow gets ACT325, with two does not") {
            val m = nodeModel("decision", incoming = 1, outgoing = 1)
            codes(m) shouldBe listOf(325)
            codes(m, ActSeverity.Warning) shouldBe listOf(325)
            codes(nodeModel("decision", incoming = 1, outgoing = 2)) shouldBe emptyList()
            codes(nodeModel("decision", incoming = 1, outgoing = 0)) shouldBe emptyList()
        }

        test("checker: merge with one incoming flow gets ACT326, with two does not") {
            val m = nodeModel("merge", incoming = 1, outgoing = 1)
            codes(m) shouldBe listOf(326)
            codes(m, ActSeverity.Warning) shouldBe listOf(326)
            codes(nodeModel("merge", incoming = 2, outgoing = 1)) shouldBe emptyList()
            codes(nodeModel("merge", incoming = 0, outgoing = 1)) shouldBe emptyList()
        }

        test("checker: ACT325 and ACT326 count branches leaving the diagram, ACT322 to ACT326 ignore self-loops") {
            codes(nodeModel("decision", incoming = 1, outgoing = 1, outsideOut = 1)) shouldBe emptyList()
            codes(nodeModel("merge", incoming = 1, outgoing = 1, outsideIn = 1)) shouldBe emptyList()
            val m =
                sysml2Model(name = "Loops") {
                    val i = initialNode(name = "I")
                    val f = finalNode(name = "F")
                    val a = actionDef(name = "A")
                    controlFlow(name = "ii", source = i, target = i)
                    controlFlow(name = "ff", source = f, target = f)
                    controlFlow(name = "ia", source = i, target = a)
                    controlFlow(name = "af", source = a, target = f)
                    actDiagram(name = "D") {
                        include(i)
                        include(f)
                        include(a)
                    }
                }
            codes(m) shouldBe emptyList()
        }

        test("checker: the new degree rules are warnings only") {
            for ((kind, i, o) in listOf(
                Triple("initial", 1, 1),
                Triple("final", 1, 1),
                Triple("call", 1, 2),
                Triple("decision", 1, 1),
                Triple("merge", 1, 1),
            )) {
                codes(nodeModel(kind, incoming = i, outgoing = o), ActSeverity.Error) shouldBe emptyList()
            }
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
