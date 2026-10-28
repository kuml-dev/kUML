package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.ReqUcSeverity
import dev.kuml.sysml2.constraint.Sysml2ReqUcChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.ReqEdgeAdapter
import dev.kuml.sysml2.edge.Sysml2ArrowHead
import dev.kuml.sysml2.edge.UcEdgeAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/** Wave C1 — REQ L11 + UC L10: metamodel, DSL, printer, edge adapters and checker. */
class Sysml2C1ReqUcTest :
    FunSpec({

        fun codes(
            model: Sysml2Model,
            severity: ReqUcSeverity? = null,
        ) = Sysml2ReqUcChecker.check(model = model).filter { severity == null || it.severity == severity }.map { it.code }

        test("DSL: requirement attributes, testCase, refine/trace/copy edges") {
            val model =
                sysml2Model(name = "M") {
                    val r1 =
                        requirementDef(
                            name = "Speed",
                            text = "x",
                            priority = RequirementPriority.HIGH,
                            source = "Stakeholder A",
                            status = RequirementStatus.APPROVED,
                            isAssumption = true,
                            subjectRef = "Car",
                        )
                    val r2 = requirementDef(name = "SpeedCopy", text = "x")
                    val tc = testCaseDef(name = "SpeedTest", text = "drive")
                    val part = partDef(name = "Car")
                    reqDiagram(name = "R") {
                        include(r1)
                        include(r2)
                        include(tc)
                        include(part)
                        refine(source = part, requirement = r1)
                        trace(source = r1, target = r2)
                        copy(source = r2, target = r1)
                        verify(source = tc, requirement = r1)
                    }
                }
            val r = model.definitions.filterIsInstance<RequirementDefinition>().first { it.id == "Speed" }
            r.priority shouldBe RequirementPriority.HIGH
            r.status shouldBe RequirementStatus.APPROVED
            r.isAssumption shouldBe true
            r.source shouldBe "Stakeholder A"
            r.subjectRef shouldBe "Car"
            model.definitions
                .filterIsInstance<TestCaseDefinition>()
                .single()
                .text shouldBe "drive"
            val d = model.diagrams.filterIsInstance<ReqDiagram>().single()
            d.refines.single().id shouldBe "refine:Car::Speed"
            d.traces.single().id shouldBe "trace:Speed::SpeedCopy"
            d.copies.single().id shouldBe "copy:SpeedCopy::Speed"
        }

        test("DSL: nested requirement groups get path ids and register their members as nodes") {
            val model =
                sysml2Model(name = "M") {
                    val a = requirementDef(name = "A")
                    val b = requirementDef(name = "B")
                    reqDiagram(name = "R") {
                        group(name = "Safety") {
                            include(a)
                            group(name = "Brakes") { include(b) }
                        }
                    }
                }
            val d = model.diagrams.filterIsInstance<ReqDiagram>().single()
            d.elementIds shouldContainExactly listOf("A", "B")
            d.groups.map { it.id } shouldContainExactly listOf("group:Safety/Brakes", "group:Safety")
            d.groups.first { it.id == "group:Safety" }.childGroupIds shouldContainExactly listOf("group:Safety/Brakes")
        }

        test("DSL: UC subject frame, actor generalisation, description") {
            val model =
                sysml2Model(name = "M") {
                    val user = actorDef(name = "User")
                    val admin = actorDef(name = "Admin")
                    val login = useCaseDef(name = "Login", description = "Authenticates the user")
                    ucDiagram(name = "U") {
                        include(user)
                        include(admin)
                        include(login)
                        generalize(specific = admin, general = user)
                        subject(name = "My System") { include(login) }
                    }
                }
            val d = model.diagrams.filterIsInstance<UcDiagram>().single()
            d.actorGeneralizations.single().id shouldBe "generalization:Admin::User"
            d.subjects.single().id shouldBe "ucsubject:My_System"
            d.subjects.single().useCaseIds shouldContainExactly listOf("Login")
            model.definitions
                .filterIsInstance<UseCaseDefinition>()
                .single()
                .description shouldBe "Authenticates the user"
        }

        test("printer: legacy REQ/UC models print byte-identical (no C1 tokens, no extra lines)") {
            val model =
                sysml2Model(name = "Legacy") {
                    val actor = actorDef(name = "Reader")
                    val uc = useCaseDef(name = "Borrow")
                    val req = requirementDef(name = "R1", reqId = "R-1", text = "t")
                    ucDiagram(name = "U") {
                        include(actor)
                        include(uc)
                        association(actor = actor, useCase = uc)
                    }
                    reqDiagram(name = "R") { include(req) }
                }
            val dsl = Sysml2DslPrinter.print(model)
            for (token in listOf(
                "priority",
                "status",
                "isAssumption",
                "subjectRef",
                "description",
                "groupById",
                "subjectById",
                "generalizeById",
                "refineById",
                "traceById",
                "copyById",
                "testCaseDef",
            )) {
                dsl shouldNotContain token
            }
            dsl shouldContain "requirementDef(name = \"R1\", id = \"R1\", reqId = \"R-1\", text = \"t\")"
            dsl shouldContain "associationById(actorId = \"Reader\", useCaseId = \"Borrow\")"
        }

        test("printer: every C1 construct is printed") {
            val model =
                sysml2Model(name = "C1") {
                    val r =
                        requirementDef(
                            name = "R",
                            priority = RequirementPriority.LOW,
                            status = RequirementStatus.DRAFT,
                            source = "S",
                            isAssumption = true,
                            subjectRef = "P",
                        )
                    val c = requirementDef(name = "C")
                    val t = testCaseDef(name = "T", text = "tt")
                    val p = partDef(name = "P")
                    val a = actorDef(name = "A")
                    val b = actorDef(name = "B")
                    val u = useCaseDef(name = "U", description = "desc")
                    reqDiagram(name = "R") {
                        include(r)
                        refine(source = p, requirement = r)
                        trace(source = r, target = c)
                        copy(source = c, target = r)
                        include(t)
                        group(name = "G") { include(c) }
                    }
                    ucDiagram(name = "U") {
                        include(a)
                        include(b)
                        include(u)
                        generalize(specific = a, general = b)
                        subject(name = "S") { include(u) }
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "priority = RequirementPriority.LOW"
            dsl shouldContain "status = RequirementStatus.DRAFT"
            dsl shouldContain "isAssumption = true"
            dsl shouldContain "subjectRef = \"P\""
            dsl shouldContain "source = \"S\""
            dsl shouldContain "testCaseDef(name = \"T\", id = \"T\", text = \"tt\")"
            dsl shouldContain "description = \"desc\""
            dsl shouldContain "refineById(sourceId = \"P\", requirementId = \"R\")"
            dsl shouldContain "traceById(sourceId = \"R\", targetId = \"C\")"
            dsl shouldContain "copyById(sourceRequirementId = \"C\", targetRequirementId = \"R\")"
            dsl shouldContain "groupById(id = \"group:G\", name = \"G\", memberIds = listOf(\"C\")"
            dsl shouldContain "generalizeById(specificId = \"A\", generalId = \"B\")"
            dsl shouldContain "subjectById(id = \"ucsubject:S\", name = \"S\", useCaseIds = listOf(\"U\"))"
        }

        test("JSON: pre-C1 documents without the new fields still deserialize with defaults") {
            val old =
                """{"name":"Old","definitions":[
                    {"type":"dev.kuml.sysml2.RequirementDefinition","id":"R","name":"R","text":"x"},
                    {"type":"dev.kuml.sysml2.UseCaseDefinition","id":"U","name":"U"}],
                  "usages":[],"diagrams":[
                    {"type":"dev.kuml.sysml2.ReqDiagram","name":"D","elementIds":["R"]},
                    {"type":"dev.kuml.sysml2.UcDiagram","name":"E","elementIds":["U"]}]}"""
            val m = Json.decodeFromString<Sysml2Model>(old)
            val r = m.definitions.filterIsInstance<RequirementDefinition>().single()
            r.priority.shouldBeNull()
            r.status.shouldBeNull()
            r.source shouldBe ""
            r.isAssumption shouldBe false
            r.subjectRef.shouldBeNull()
            m.definitions
                .filterIsInstance<UseCaseDefinition>()
                .single()
                .description shouldBe ""
            m.diagrams
                .filterIsInstance<ReqDiagram>()
                .single()
                .groups
                .shouldBeEmpty()
            m.diagrams
                .filterIsInstance<UcDiagram>()
                .single()
                .subjects
                .shouldBeEmpty()
        }

        test("JSON: C1 model round-trips") {
            val model =
                sysml2Model(name = "RT") {
                    val r = requirementDef(name = "R", priority = RequirementPriority.HIGH)
                    val t = testCaseDef(name = "T")
                    reqDiagram(name = "D") {
                        include(r)
                        include(t)
                        group(name = "G") { include(r) }
                    }
                }
            val json = Json.encodeToString(Sysml2Model.serializer(), model)
            Json.decodeFromString<Sysml2Model>(json) shouldBe model
        }

        test("edge adapters: refine/trace/copy dashed with stereotypes, subject by prefix, generalisation hollow triangle") {
            val req =
                ReqDiagram(
                    name = "R",
                    refines = listOf(ReqRefine(id = "refine:a::b", sourceId = "a", requirementId = "b")),
                    traces = listOf(ReqTrace(id = "trace:a::b", sourceId = "a", targetId = "b")),
                    copies = listOf(ReqCopy(id = "copy:a::b", sourceRequirementId = "a", targetRequirementId = "b")),
                )
            val ra = ReqEdgeAdapter(req)
            ra.metadataFor("refine:a::b")!!.stereotype shouldBe "«refine»"
            ra.metadataFor("trace:a::b")!!.stereotype shouldBe "«trace»"
            ra.metadataFor("copy:a::b")!!.stereotype shouldBe "«copy»"
            ra.metadataFor("copy:a::b")!!.dashArray.shouldNotBeNull()
            ra.metadataFor("subject:R1::Car")!!.stereotype shouldBe "«subject»"
            ra.metadataFor("unknown:x").shouldBeNull()
            val ua =
                UcEdgeAdapter(
                    UcDiagram(
                        name = "U",
                        actorGeneralizations = listOf(UcActorGeneralization(id = "generalization:a::b", specificId = "a", generalId = "b")),
                    ),
                )
            val g = ua.metadataFor("generalization:a::b")!!
            g.arrowHead shouldBe Sysml2ArrowHead.OpenTriangle
            g.dashArray.shouldBeNull()
            g.stereotype.shouldBeNull()
        }

        // ── checker ──────────────────────────────────────────────────────────

        test("checker: old-style models produce no findings") {
            val model =
                sysml2Model(name = "Legacy") {
                    val a = actorDef(name = "A")
                    val u = useCaseDef(name = "U")
                    val r = requirementDef(name = "R", text = "t")
                    val p = partDef(name = "P")
                    ucDiagram(name = "UC") {
                        include(a)
                        include(u)
                        association(actor = a, useCase = u)
                    }
                    reqDiagram(name = "RQ") {
                        include(r)
                        include(p)
                        satisfy(source = p, requirement = r)
                    }
                }
            Sysml2ReqUcChecker.check(model = model).shouldBeEmpty()
        }

        test("checker 201/202: copy needs requirements with identical text") {
            val model =
                sysml2Model(name = "M") {
                    val a = requirementDef(name = "A", text = "one")
                    val b = requirementDef(name = "B", text = "two")
                    val p = partDef(name = "P")
                    reqDiagram(name = "R") {
                        include(a)
                        include(b)
                        include(p)
                        copy(source = a, target = b)
                        copyById(sourceRequirementId = "A", targetRequirementId = "P")
                    }
                }
            codes(model, ReqUcSeverity.Error) shouldContainExactly listOf(202, 201)
        }

        test("checker 203: subjectRef must resolve") {
            val model =
                sysml2Model(name = "M") {
                    val a = requirementDef(name = "A", subjectRef = "Nope")
                    reqDiagram(name = "R") { include(a) }
                }
            codes(model) shouldContainExactly listOf(203)
        }

        test("checker 204: group depth over 3 is an error, depth 3 is fine") {
            val ok =
                sysml2Model(name = "M") {
                    val a = requirementDef(name = "A")
                    reqDiagram(name = "R") { group(name = "1") { group(name = "2") { group(name = "3") { include(a) } } } }
                }
            codes(ok).shouldBeEmpty()
            val deep =
                sysml2Model(name = "M") {
                    val a = requirementDef(name = "A")
                    reqDiagram(
                        name = "R",
                    ) { group(name = "1") { group(name = "2") { group(name = "3") { group(name = "4") { include(a) } } } } }
                }
            codes(deep) shouldContainExactly listOf(204)
        }

        test("checker 205: use case in two subject frames") {
            val model =
                sysml2Model(name = "M") {
                    val u = useCaseDef(name = "U")
                    ucDiagram(name = "D") {
                        include(u)
                        subject(name = "S1") { include(u) }
                        subject(name = "S2") { include(u) }
                    }
                }
            codes(model) shouldContainExactly listOf(205)
        }

        test("checker 206/207: generalisation cycle and non-actor side") {
            val cyc =
                sysml2Model(name = "M") {
                    val a = actorDef(name = "A")
                    val b = actorDef(name = "B")
                    ucDiagram(name = "D") {
                        include(a)
                        include(b)
                        generalize(specific = a, general = b)
                        generalize(specific = b, general = a)
                    }
                }
            codes(cyc) shouldContainExactly listOf(206)
            val bad =
                sysml2Model(name = "M") {
                    val a = actorDef(name = "A")
                    val u = useCaseDef(name = "U")
                    ucDiagram(name = "D") {
                        include(a)
                        include(u)
                        generalizeById(specificId = "A", generalId = "U")
                    }
                }
            codes(bad) shouldContainExactly listOf(207)
        }

        test("checker 208: requirement in two groups") {
            val model =
                sysml2Model(name = "M") {
                    val a = requirementDef(name = "A")
                    reqDiagram(name = "R") {
                        group(name = "G1") { include(a) }
                        group(name = "G2") { include(a) }
                    }
                }
            codes(model) shouldContainExactly listOf(208)
        }

        test("checker 209-212: text limits") {
            val model =
                sysml2Model(name = "M") {
                    val a = requirementDef(name = "A".repeat(201))
                    val b = requirementDef(name = "B", text = "x".repeat(2001))
                    val c = requirementDef(name = "C", source = "s".repeat(201))
                    val u = useCaseDef(name = "U", description = "d".repeat(1001))
                    reqDiagram(name = "R") {
                        include(a)
                        include(b)
                        include(c)
                    }
                    ucDiagram(name = "D") { include(u) }
                }
            codes(model) shouldContainExactly listOf(209, 210, 211, 212)
        }

        test("checker 213/214: group and subject count limits") {
            val model =
                sysml2Model(name = "M") {
                    val u = useCaseDef(name = "U")
                    reqDiagram(name = "R") { repeat(65) { group(name = "g$it") {} } }
                    ucDiagram(name = "D") {
                        include(u)
                        repeat(17) { subject(name = "s$it") {} }
                    }
                }
            codes(model) shouldContainExactly listOf(213, 214)
        }

        test("checker 215/216 warnings and 217 hint") {
            val model =
                sysml2Model(name = "M") {
                    val r = requirementDef(name = "R")
                    val p = partDef(name = "P")
                    val u = useCaseDef(name = "U", description = "word ".repeat(60))
                    reqDiagram(name = "RQ") {
                        include(r)
                        include(p)
                        verify(source = p, requirement = r)
                        refineById(sourceId = "R", requirementId = "P")
                    }
                    ucDiagram(name = "D") { include(u) }
                }
            Sysml2ReqUcChecker.check(model = model).map { it.code to it.severity } shouldContainExactly
                listOf(
                    215 to ReqUcSeverity.Warning,
                    216 to ReqUcSeverity.Warning,
                    217 to ReqUcSeverity.Hint,
                )
        }

        test("checker: verify from a use case or test case and trace between anything stay silent") {
            val model =
                sysml2Model(name = "M") {
                    val r = requirementDef(name = "R")
                    val u = useCaseDef(name = "U")
                    val t = testCaseDef(name = "T")
                    val p = partDef(name = "P")
                    reqDiagram(name = "RQ") {
                        include(r)
                        include(u)
                        include(t)
                        include(p)
                        verify(source = u, requirement = r)
                        verify(source = t, requirement = r)
                        trace(source = p, target = u)
                    }
                }
            Sysml2ReqUcChecker.check(model = model).shouldBeEmpty()
        }
    })
