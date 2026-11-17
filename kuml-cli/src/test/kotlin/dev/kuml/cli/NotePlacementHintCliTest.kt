package dev.kuml.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/**
 * N2: a note placement hint is evaluated for SysML 2 diagrams other than sequence diagrams, for C4 and (Nacharbeit 99) for UML
 * diagrams other than sequence diagrams; every other family (including the UML sequence diagram, family "UML") reports
 * `KUML-NOTE-W005` (once per hinted note), NEAR never warns, and the SVG of an ignoring family does not change.
 */
class NotePlacementHintCliTest :
    FunSpec({
        fun scripts(hint: String?): Map<String, String> {
            val arg = if (hint == null) "" else ", placement = NotePlacement.$hint"
            return mapOf(
                "SEQ" to
                    """
                    import dev.kuml.sysml2.MessageKind
                    import dev.kuml.sysml2.dsl.sysml2Model
                    sysml2Model(name = "M") {
                        val a = lifelineDef(name = "A")
                        val b = lifelineDef(name = "B")
                        message(label = "go()", source = a, target = b, seqNo = 1, kind = MessageKind.Sync)
                        seqDiagram(name = "D") {
                            include(a)
                            include(b)
                            note("About A", a, id = "n1"$arg)
                        }
                    }
                    """.trimIndent(),
                "C4" to
                    """
                    import dev.kuml.c4.dsl.c4Model
                    c4Model(name = "Shop") {
                        val user = person(name = "User")
                        val shop = softwareSystem(name = "Shop")
                        relationship(source = user, target = shop) { description = "uses" }
                        note("About shop", shop, id = "n1"$arg)
                        systemContextDiagram(name = "Context") { include(user, shop) }
                    }
                    """.trimIndent(),
                "ERM" to
                    """
                    import dev.kuml.erm.dsl.ermModel
                    ermModel("S") {
                        val a = entity("A") { id() }
                        entity("B") {
                            id()
                            foreignKey(name = "a_id", references = a, nullable = false)
                        }
                        note(text = "About A", id = "n1"$arg)
                        diagram(name = "D")
                    }
                    """.trimIndent(),
                "BPMN" to
                    """
                    import dev.kuml.bpmn.dsl.*
                    import dev.kuml.bpmn.model.*
                    bpmnModel(name = "Orders") {
                        process(id = "p", name = "Order") {
                            val start = startEvent(name = "Start")
                            val done = endEvent(name = "Done")
                            sequenceFlow(from = start, to = done)
                            textAnnotation(text = "About start", start, id = "n1"$arg)
                        }
                        diagram(name = "Orders", processId = "p")
                    }
                    """.trimIndent(),
                "UML" to
                    """
                    sequenceDiagram(name = "Orders") {
                        val a = lifeline(name = "A")
                        val b = lifeline(name = "B")
                        message(from = a, to = b, label = "go()")
                        comment(text = "About A", a$arg)
                    }
                    """.trimIndent(),
                "BDD" to
                    """
                    sysml2Model("M") {
                        val vehicle = partDef("Vehicle")
                        bdd("D") {
                            include(vehicle)
                            comment("About vehicle", vehicle, id = "n1"$arg)
                        }
                    }
                    """.trimIndent(),
            )
        }

        fun render(script: String): Pair<RenderOutcome, String> {
            val dir = Files.createTempDirectory("kuml-hint").toFile()
            try {
                val input = File(dir, "d.kuml.kts").also { it.writeText(script) }
                val out = dir.toPath().resolve("o.svg")
                val outcome = RenderPipeline.run(input = input, output = out, format = "svg", width = 800, themeName = "kuml")
                return outcome to File(out.toString()).readText()
            } finally {
                dir.deleteRecursively()
            }
        }

        fun placementCodes(o: RenderOutcome) = o.noteFindings.filter { it.code == "KUML-NOTE-W004" || it.code == "KUML-NOTE-W005" }

        for (family in listOf("SEQ", "BPMN", "UML")) {
            test("$family ignores ABOVE with exactly one W005, an unchanged SVG, and NEAR stays silent") {
                val (plainOutcome, plainSvg) = render(scripts(null).getValue(family))
                val (aboveOutcome, aboveSvg) = render(scripts("ABOVE").getValue(family))
                val (nearOutcome, nearSvg) = render(scripts("NEAR").getValue(family))
                placementCodes(plainOutcome) shouldBe emptyList()
                placementCodes(nearOutcome) shouldBe emptyList()
                val w005 = placementCodes(aboveOutcome)
                withClue(w005.toString()) {
                    w005.size shouldBe 1
                    w005.single().code shouldBe "KUML-NOTE-W005"
                    w005.single().message.contains("($family)") shouldBe true
                }
                aboveSvg shouldBe plainSvg
                nearSvg shouldBe plainSvg
            }
        }

        test("a SysML 2 BDD evaluates the hint: no W005, and the note moves compared to no hint") {
            val (plainOutcome, plainSvg) = render(scripts(null).getValue("BDD"))
            val (nearOutcome, nearSvg) = render(scripts("NEAR").getValue("BDD"))
            nearSvg shouldBe plainSvg
            for (side in listOf("ABOVE", "BELOW", "LEFT", "RIGHT")) {
                val (outcome, _) = render(scripts(side).getValue("BDD"))
                placementCodes(outcome).filter { it.code == "KUML-NOTE-W005" } shouldBe emptyList()
            }
            val (_, above) = render(scripts("ABOVE").getValue("BDD"))
            val (_, below) = render(scripts("BELOW").getValue("BDD"))
            (above == below) shouldBe false
            placementCodes(plainOutcome) shouldBe emptyList()
            placementCodes(nearOutcome) shouldBe emptyList()
        }

        test("C4 evaluates the hint: no W005 (the diagram checks of render and validate), NEAR equals no hint, and the note moves") {
            val (plainOutcome, plainSvg) = render(scripts(null).getValue("C4"))
            val (nearOutcome, nearSvg) = render(scripts("NEAR").getValue("C4"))
            nearSvg shouldBe plainSvg
            placementCodes(plainOutcome) shouldBe emptyList()
            placementCodes(nearOutcome) shouldBe emptyList()
            for (side in listOf("ABOVE", "BELOW", "LEFT", "RIGHT")) {
                val (outcome, svg) = render(scripts(side).getValue("C4"))
                withClue("C4 $side ${outcome.noteFindings}") {
                    outcome.noteFindings.none { it.code == "KUML-NOTE-W005" } shouldBe true
                    svg.contains("<svg") shouldBe true
                }
            }
            val (_, above) = render(scripts("ABOVE").getValue("C4"))
            val (_, below) = render(scripts("BELOW").getValue("C4"))
            (above == below) shouldBe false
        }

        fun evaluated(hint: String?): Map<String, String> {
            val arg = if (hint == null) "" else ", placement = NotePlacement.$hint"
            return mapOf(
                "UMLCLASS" to
                    """
                    classDiagram(name = "Orders") {
                        val order = classOf(name = "Order") { attribute(name = "id", type = "String") }
                        val item = classOf(name = "Item") { attribute(name = "sku", type = "String") }
                        association(source = order, target = item)
                        comment(text = "About order", order$arg)
                    }
                    """.trimIndent(),
                "IBD" to
                    """
                    sysml2Model("Ibd") {
                        attributeDef("Fuel")
                        portDef("FuelPort") { flowProperty("fuel", "Fuel", PortDirection.In) }
                        connectionDef("FuelLine")
                        partDef("Engine")
                        partDef("Tank") { port("outlet", "FuelPort", direction = PortDirection.Out) }
                        val vehicle = partDef("Vehicle") {
                            part("engine", "Engine")
                            part("tank", "Tank")
                        }
                        comment("About vehicle", vehicle, id = "n1"$arg)
                        ibd("Wiring", owner = vehicle)
                    }
                    """.trimIndent(),
                "UC" to
                    """
                    sysml2Model("Library") {
                        val reader = actorDef("Reader")
                        val borrow = useCaseDef("Borrow Book")
                        comment("About borrowing", borrow, id = "n1"$arg)
                        ucDiagram("Library system") {
                            include(reader)
                            include(borrow)
                            association(reader, borrow)
                        }
                    }
                    """.trimIndent(),
                "STM" to
                    """
                    sysml2Model("Light") {
                        val start = stateDef("Start", isInitial = true)
                        val red = stateDef("Red")
                        val green = stateDef("Green")
                        transition("t0", start, red)
                        transition("t1", red, green, trigger = "go")
                        comment("About red", red, id = "n1"$arg)
                        stmDiagram("Light") {
                            include(start)
                            include(red)
                            include(green)
                        }
                    }
                    """.trimIndent(),
                "ERM" to
                    """
                    import dev.kuml.erm.dsl.ermModel
                    ermModel("S") {
                        val a = entity("A") { id() }
                        entity("B") {
                            id()
                            foreignKey(name = "a_id", references = a, nullable = false)
                        }
                        note(text = "About A", a, id = "n1"$arg)
                        diagram(name = "D")
                    }
                    """.trimIndent(),
                "BLUEPRINT" to
                    """
                    import dev.kuml.blueprint.dsl.blueprint
                    import dev.kuml.blueprint.model.Sentiment
                    blueprint(name = "Service") {
                        lateinit var s: String
                        phase(name = "Request") {
                            customer(name = "Reads", sentiment = Sentiment.NEUTRAL)
                            s = customer(name = "Fills in", sentiment = Sentiment.NEUTRAL)
                            customer(name = "Sends", sentiment = Sentiment.NEUTRAL)
                        }
                        note("About step", s, id = "n1"$arg)
                        blueprintDiagram(name = "Blueprint")
                    }
                    """.trimIndent(),
                "REQ" to
                    """
                    sysml2Model("Reqs") {
                        val a = requirementDef("Speed", text = "The vehicle shall reach 180 km/h.")
                        val b = requirementDef("Brake", text = "The vehicle shall stop within 36 m.")
                        comment("About speed", a, id = "n1"$arg)
                        reqDiagram("Reqs") {
                            group("G1") {
                                include(a)
                                include(b)
                            }
                        }
                    }
                    """.trimIndent(),
            )
        }

        for (family in listOf("UMLCLASS", "IBD", "UC", "STM", "REQ", "ERM", "BLUEPRINT")) {
            test("$family evaluates the hint: no W005, NEAR is byte-identical to no hint, a side hint keeps the diagram valid") {
                val (plainOutcome, plainSvg) = render(evaluated(null).getValue(family))
                val (nearOutcome, nearSvg) = render(evaluated("NEAR").getValue(family))
                nearSvg shouldBe plainSvg
                placementCodes(plainOutcome) shouldBe emptyList()
                placementCodes(nearOutcome) shouldBe emptyList()
                for (side in listOf("ABOVE", "BELOW", "LEFT", "RIGHT")) {
                    val (o, svg) = render(evaluated(side).getValue(family))
                    withClue("$family $side ${o.noteFindings}") {
                        o.noteFindings.none { it.code == "KUML-NOTE-W005" } shouldBe true
                        svg.contains("<svg") shouldBe true
                    }
                }
            }
        }

        test("a UML class diagram note moves with ABOVE versus BELOW and keeps its anchor line") {
            val (_, above) = render(evaluated("ABOVE").getValue("UMLCLASS"))
            val (_, below) = render(evaluated("BELOW").getValue("UMLCLASS"))
            (above == below) shouldBe false
            above.contains("About order") shouldBe true
            below.contains("About order") shouldBe true
        }
    })
