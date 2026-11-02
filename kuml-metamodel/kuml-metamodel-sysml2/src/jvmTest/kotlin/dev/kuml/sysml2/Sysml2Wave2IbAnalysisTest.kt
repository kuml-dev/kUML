@file:Suppress("RequireNamedArguments")

package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.Sysml2AnalysisChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.BddEdgeAdapter
import dev.kuml.sysml2.projection.BDD_WRAP_CHARS
import dev.kuml.sysml2.projection.BddEdgeKind
import dev.kuml.sysml2.projection.BddProjection
import dev.kuml.sysml2.projection.sysml2BddBoxContent
import dev.kuml.sysml2.projection.sysml2BddProjection
import dev.kuml.sysml2.projection.wrapBddText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/** Wave 2I-b - analysis cases, calculations, perform / exhibit: model, JSON, DSL, printer, checker, projection, box content. */
class Sysml2Wave2IbAnalysisTest :
    FunSpec({
        fun sample(): Sysml2Model =
            sysml2Model(name = "S") {
                val action = actionDef("Drive")
                val state = stateDef("Charging")
                val vehicle =
                    partDef("Vehicle") {
                        perform("drive", action)
                        exhibit("charging", state)
                    }
                val total =
                    calcDef("TotalMass") {
                        input("a", "Mass")
                        output("b", "Mass")
                        inout("c")
                        expression("a + b")
                        returns("total", "Mass")
                    }
                val analysis =
                    analysisDef("MassAnalysis") {
                        subject(vehicle)
                        objective("Stay below the limit")
                        calc("total", total)
                        returns("ok", "Boolean")
                    }
                bdd("B") {
                    include(analysis)
                    include(total)
                    include(vehicle)
                }
            }

        fun analysisOf(m: Sysml2Model) = m.definitions.filterIsInstance<AnalysisCaseDefinition>().single()

        test("the DSL builds calc, analysis and perform / exhibit with their ids") {
            val m = sample()
            val calc = m.definitions.filterIsInstance<CalculationDefinition>().single()
            calc.parameters.map { it.direction } shouldContainExactly
                listOf(CalcParameterDirection.IN, CalcParameterDirection.OUT, CalcParameterDirection.INOUT)
            calc.expression shouldBe "a + b"
            calc.result shouldBe CalcParameter("total", "Mass", CalcParameterDirection.OUT)
            val an = analysisOf(m)
            an.subjectId shouldBe "Vehicle"
            an.calculations shouldContainExactly listOf(CalcUsageRef("total", "TotalMass"))
            val part = m.definitions.filterIsInstance<PartDefinition>().single()
            part.performs shouldContainExactly listOf(BehaviorRef("drive", "Drive"))
            part.exhibits shouldContainExactly listOf(BehaviorRef("charging", "Charging"))
        }

        test("an old part JSON without performs and exhibits still decodes") {
            val json = Json { ignoreUnknownKeys = true }
            val m =
                json.decodeFromString(
                    Sysml2Model.serializer(),
                    """{"name":"Old","definitions":[{"type":"dev.kuml.sysml2.PartDefinition","id":"p","name":"P"}]}""",
                )
            val p = m.definitions.single() as PartDefinition
            p.performs.shouldBeEmpty()
            p.exhibits.shouldBeEmpty()
        }

        test("JSON round trip keeps the new definitions and references") {
            val json = Json { encodeDefaults = true }
            val m = sample()
            json.decodeFromString(Sysml2Model.serializer(), json.encodeToString(Sysml2Model.serializer(), m)) shouldBe m
        }

        test("the printer emits calcDef, analysisDef, perform and exhibit with the id forms") {
            val dsl = Sysml2DslPrinter.print(sample())
            dsl shouldContain """calcDef(name = "TotalMass", id = "TotalMass")"""
            dsl shouldContain """input("a", "Mass")"""
            dsl shouldContain """output("b", "Mass")"""
            dsl shouldContain """inout("c", "")"""
            dsl shouldContain """expression("a + b")"""
            dsl shouldContain """returns("total", "Mass")"""
            dsl shouldContain """analysisDef(name = "MassAnalysis", id = "MassAnalysis")"""
            dsl shouldContain """subject("Vehicle")"""
            dsl shouldContain """objective("Stay below the limit")"""
            dsl shouldContain """calc("total", "TotalMass")"""
            dsl shouldContain """perform("drive", "Drive")"""
            dsl shouldContain """exhibit("charging", "Charging")"""
        }

        test("replaying the printed calls through the builder gives an equal model") {
            val original = sample()
            val replay =
                sysml2Model("S") {
                    actionDef("Drive")
                    stateDef("Charging")
                    partDef(name = "Vehicle", id = "Vehicle") {
                        perform("drive", "Drive")
                        exhibit("charging", "Charging")
                    }
                    calcDef(name = "TotalMass", id = "TotalMass") {
                        input("a", "Mass")
                        output("b", "Mass")
                        inout("c", "")
                        expression("a + b")
                        returns("total", "Mass")
                    }
                    analysisDef(name = "MassAnalysis", id = "MassAnalysis") {
                        subject("Vehicle")
                        objective("Stay below the limit")
                        calc("total", "TotalMass")
                        returns("ok", "Boolean")
                    }
                    bdd("B") {
                        includeById("MassAnalysis")
                        includeById("TotalMass")
                        includeById("Vehicle")
                    }
                }
            replay.definitions.toSet() shouldBe original.definitions.toSet()
        }

        test("the DSL refuses oversized lists and texts") {
            shouldThrow<IllegalArgumentException> {
                sysml2Model("M") { calcDef("C") { repeat(Sysml2Limits.CALC_ENTRIES + 1) { input("p$it") } } }
            }
            shouldThrow<IllegalArgumentException> {
                sysml2Model("M") { analysisDef("A") { repeat(Sysml2Limits.CALC_ENTRIES + 1) { calc("c$it", "X") } } }
            }
            shouldThrow<IllegalArgumentException> {
                sysml2Model("M") { partDef("P") { repeat(Sysml2Limits.CALC_ENTRIES + 1) { perform("a$it", "X") } } }
            }
            shouldThrow<IllegalArgumentException> {
                sysml2Model("M") { calcDef("C") { expression("x".repeat(Sysml2Limits.CALC_TEXT + 1)) } }
            }
        }

        test("checker: a clean model has no findings, also for models without the new constructs") {
            Sysml2AnalysisChecker.check(sample()).shouldBeEmpty()
            Sysml2AnalysisChecker.check(sysml2Model("Plain") { partDef("P") }).shouldBeEmpty()
        }

        test("checker: SYSML2_CALC_REF_UNKNOWN for a missing calc and for a definition of another type") {
            val m =
                sysml2Model("M") {
                    partDef("NotACalc")
                    analysisDef("A") {
                        calc("one", "Missing")
                        calc("two", "NotACalc")
                    }
                }
            Sysml2AnalysisChecker.check(m).map { it.code } shouldContainExactly listOf("SYSML2_CALC_REF_UNKNOWN", "SYSML2_CALC_REF_UNKNOWN")
        }

        test("checker: SYSML2_ANALYSIS_SUBJECT_UNKNOWN") {
            val m = sysml2Model("M") { analysisDef("A") { subject("Nope") } }
            Sysml2AnalysisChecker.check(m).map { it.code } shouldContainExactly listOf("SYSML2_ANALYSIS_SUBJECT_UNKNOWN")
        }

        test("checker: perform needs an action and exhibit needs a state, each missing or wrong target is reported") {
            val m =
                sysml2Model("M") {
                    val s = stateDef("S")
                    val a = actionDef("A")
                    partDef("P") {
                        perform("ok", a)
                        perform("wrong", s.id)
                        perform("missing", "Nope")
                        exhibit("ok", s)
                        exhibit("wrong", a.id)
                        exhibit("missing", "Nope")
                    }
                }
            Sysml2AnalysisChecker.check(m).map { it.code } shouldContainExactly
                listOf(
                    "SYSML2_PERFORM_TARGET_NOT_ACTION",
                    "SYSML2_PERFORM_TARGET_NOT_ACTION",
                    "SYSML2_EXHIBIT_TARGET_NOT_STATE",
                    "SYSML2_EXHIBIT_TARGET_NOT_STATE",
                )
        }

        test("checker: duplicate names of calcs, performs and exhibits") {
            val m =
                sysml2Model("M") {
                    val c = calcDef("C")
                    val a = actionDef("A")
                    val s = stateDef("S")
                    analysisDef("An") {
                        calc("x", c)
                        calc("x", c)
                    }
                    partDef("P") {
                        perform("p", a)
                        perform("p", a)
                        exhibit("e", s)
                        exhibit("e", s)
                    }
                }
            Sysml2AnalysisChecker.check(m).map { it.code } shouldContainExactly List(3) { "SYSML2_DUPLICATE_USAGE_NAME" }
        }

        test("checker: lists beyond the limit in a hostile model (no DSL) are reported once and checked boundedly") {
            val many = List(Sysml2Limits.CALC_ENTRIES + 5) { CalcUsageRef("c$it", "Nope") }
            val m = Sysml2Model(name = "H", definitions = listOf(AnalysisCaseDefinition(id = "A", name = "A", calculations = many)))
            val codes = Sysml2AnalysisChecker.check(m).map { it.code }
            codes.count { it == "SYSML2_ANALYSIS_LIMIT" } shouldBe 1
            codes.count { it == "SYSML2_CALC_REF_UNKNOWN" } shouldBe Sysml2Limits.CALC_ENTRIES
        }

        test("projection: calcs become compositions with the calc name, the subject a Subject edge; both leave the compartments") {
            val m = sample()
            val d = m.diagrams.single() as BdDiagram
            val proj = sysml2BddProjection(model = m, diagram = d)
            val edges = proj.edges.associateBy { it.id }
            edges.getValue("comp:MassAnalysis::total").let {
                it.kind shouldBe BddEdgeKind.Composition
                it.sourceId shouldBe "MassAnalysis"
                it.targetId shouldBe "TotalMass"
                it.label shouldBe "total"
            }
            edges.getValue("subject:MassAnalysis").kind shouldBe BddEdgeKind.Subject
            val content = sysml2BddBoxContent(model = m, def = analysisOf(m), projection = proj)
            content.compartments.map { it.header } shouldContainExactly listOf("objective", "return")
        }

        test("projection: without the target in the diagram the calc and the subject stay as compartment lines") {
            val m = sample()
            val d = BdDiagram(name = "only", elementIds = listOf("MassAnalysis"))
            val proj = sysml2BddProjection(model = m, diagram = d)
            proj.edges.shouldBeEmpty()
            val content = sysml2BddBoxContent(model = m, def = analysisOf(m), projection = proj)
            content.compartments.map { it.header } shouldContainExactly listOf("subject", "objective", "calcs", "return")
            content.compartments.first { it.header == "subject" }.lines shouldContainExactly listOf("subject : Vehicle")
            content.compartments.first { it.header == "calcs" }.lines shouldContainExactly listOf("total : TotalMass")
        }

        test("projection is deterministic and the edge adapter draws the subject edge dashed with a stereotype") {
            val m = sample()
            val d = m.diagrams.single() as BdDiagram
            sysml2BddProjection(model = m, diagram = d) shouldBe sysml2BddProjection(model = m, diagram = d)
            val meta = BddEdgeAdapter(model = m, diagram = d).metadataFor("subject:MassAnalysis")!!
            meta.stereotype shouldBe "«subject»"
            meta.dashArray shouldBe "5 4"
        }

        test("box content of a calculation: parameters, wrapped expression, return - in that order") {
            val m =
                sysml2Model("M") {
                    calcDef("C") {
                        input("a", "Mass")
                        inout("b")
                        expression("alpha + beta + gamma + delta + epsilon + zeta + eta + theta")
                        returns("r", "Mass")
                    }
                }
            val c = m.definitions.single()
            val content = sysml2BddBoxContent(model = m, def = c, projection = null)
            content.compartments.map { it.header } shouldContainExactly listOf("parameters", "expression", "return")
            content.compartments[0].lines shouldContainExactly listOf("in a : Mass", "inout b")
            content.compartments[1].monospace shouldBe true
            (content.compartments[1].lines.size > 1) shouldBe true
            content.compartments[1].lines.joinToString(" ") shouldBe "alpha + beta + gamma + delta + epsilon + zeta + eta + theta"
            content.compartments[2].lines shouldContainExactly listOf("r : Mass")
        }

        test("box content of a part lists performs and exhibits by the names of the targets, an unknown id stays raw") {
            val m =
                sysml2Model("M") {
                    partDef("P") {
                        perform("drive", "Drive")
                        exhibit("x", "Gone")
                    }
                    actionDef("Drive")
                }
            val content = sysml2BddBoxContent(model = m, def = m.definitions.first { it.id == "P" }, projection = null)
            content.compartments[0].header shouldBe "perform actions"
            content.compartments[0].lines shouldContainExactly listOf("perform drive : Drive")
            content.compartments[1].lines shouldContainExactly listOf("exhibit x : Gone")
        }

        test("boxes without the new constructs have no extra compartments") {
            val m = sysml2Model("M") { partDef("P") }
            sysml2BddBoxContent(model = m, def = m.definitions.single(), projection = null).compartments.shouldBeEmpty()
        }

        test("wrapBddText: breaks at spaces, loses no word, splits very long words, caps the line count honestly") {
            wrapBddText("a b c").shouldContainExactly("a b c")
            val text = (1..40).joinToString(" ") { "word$it" }
            val lines = wrapBddText(text, width = 20)
            (lines.all { it.length <= 20 }) shouldBe true
            (lines.size <= BddProjection.MAX_COMPARTMENT_LINES) shouldBe true
            wrapBddText("x".repeat(100), width = 30).joinToString("") shouldBe "x".repeat(100)
            wrapBddText("x".repeat(100), width = 30).size shouldBe 4
            val capped = wrapBddText("y".repeat(BDD_WRAP_CHARS * 50))
            capped.size shouldBe BddProjection.MAX_COMPARTMENT_LINES
            capped.last() shouldContain "…"
            wrapBddText("a b c") shouldBe wrapBddText("a b c")
            // Hostile input: a huge text is looked at only in part, still ends in the cap with an ellipsis.
            val huge = wrapBddText("word ".repeat(5_000_000))
            huge.size shouldBe BddProjection.MAX_COMPARTMENT_LINES
            huge.last() shouldContain "…"
            wrapBddText("   ").shouldBeEmpty()
            val ok = wrapBddText("one two three four five six seven eight nine ten", width = 12)
            ok.joinToString(" ") shouldBe "one two three four five six seven eight nine ten"
            ok.joinToString("\n") shouldNotContain "…"
        }

        test("box content of a hostile model is bounded: line count and line length") {
            val long = "x".repeat(100_000)
            val many = List(5_000) { BehaviorRef("p$it", long) }
            val m = Sysml2Model(name = "H", definitions = listOf(PartDefinition(id = "P", name = "P", performs = many, exhibits = many)))
            val content = sysml2BddBoxContent(model = m, def = m.definitions.single(), projection = null)
            for (c in content.compartments) {
                c.lines.size shouldBe BddProjection.MAX_COMPARTMENT_LINES
                (c.lines.all { it.length <= Sysml2Limits.CALC_TEXT }) shouldBe true
            }
        }
    })
