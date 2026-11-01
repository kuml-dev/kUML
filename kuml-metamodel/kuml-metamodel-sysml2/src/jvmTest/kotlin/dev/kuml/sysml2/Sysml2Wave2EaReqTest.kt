package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.Sysml2LayoutHintChecker
import dev.kuml.sysml2.constraint.Sysml2ReqUcChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Wave 2E-a - requirement diagrams: external requirements, reqId check 218, group ids in the layout block, printer. */
class Sysml2Wave2EaReqTest :
    FunSpec({
        fun codes(m: Sysml2Model): List<Int> = Sysml2ReqUcChecker.check(model = m).map { it.code }

        test("externalRequirement: stub with id ext:<reqId>, source and text; calling twice returns the same stub") {
            lateinit var first: RequirementDefinition
            val m =
                sysml2Model(name = "M") {
                    first = externalRequirement(reqId = "PAL-FB-007", source = "Dokument 11", text = "Restore within one hour.")
                    val again = externalRequirement(reqId = "PAL-FB-007", source = "other", text = "ignored")
                    (again === first) shouldBe true
                }
            m.definitions.filterIsInstance<RequirementDefinition>().size shouldBe 1
            first.id shouldBe "ext:PAL-FB-007"
            first.name shouldBe "PAL-FB-007"
            first.reqId shouldBe "PAL-FB-007"
            first.externalSource shouldBe "Dokument 11"
            first.text shouldBe "Restore within one hour."
            sysml2Model(name = "N") { externalRequirement(reqId = "X", source = "S") }
                .definitions
                .filterIsInstance<RequirementDefinition>()
                .single()
                .text shouldBe ""
        }

        test("external stubs can be the end of copy and derive") {
            val m =
                sysml2Model(name = "M") {
                    val ext = externalRequirement(reqId = "X-1", source = "Doc", text = "Same text.")
                    val local = requirementDef(name = "Local", reqId = "L-1", text = "Same text.")
                    reqDiagram(name = "R") {
                        include(ext)
                        include(local)
                        copy(source = local, target = ext)
                        derive(source = local, target = ext)
                    }
                }
            val d = m.diagrams.filterIsInstance<ReqDiagram>().single()
            d.copies.single().targetRequirementId shouldBe "ext:X-1"
            d.derives.single().targetRequirementId shouldBe "ext:X-1"
            codes(m).shouldBeEmpty()
        }

        test("218: same reqId with different text warns once, whitespace differences do not, case differences do") {
            fun model(
                a: String,
                b: String,
            ) = sysml2Model(name = "M") {
                val r1 = requirementDef(name = "A", reqId = "R-1", text = a)
                val r2 = requirementDef(name = "B", reqId = "R-1", text = b)
                reqDiagram(name = "R") {
                    include(r1)
                    include(r2)
                }
            }
            codes(model("The system shall start.", "The system shall stop.")) shouldBe listOf(218)
            codes(model("The system  shall\nstart.", " The system shall start. ")).shouldBeEmpty()
            codes(model("The system shall start.", "the system shall start.")) shouldBe listOf(218)
            codes(model("The system shall start.", "")).shouldBeEmpty()
        }

        test("218: a copy pair is only reported by 202, an external stub without text triggers neither") {
            val copied =
                sysml2Model(name = "M") {
                    val master = requirementDef(name = "Master", reqId = "R-1", text = "One.")
                    val slave = requirementDef(name = "Slave", reqId = "R-1", text = "Two.")
                    reqDiagram(name = "R") {
                        include(master)
                        include(slave)
                        copy(source = slave, target = master)
                    }
                }
            codes(copied) shouldBe listOf(202)
            val stub =
                sysml2Model(name = "M") {
                    val ext = externalRequirement(reqId = "R-1", source = "Doc")
                    val local = requirementDef(name = "Local", reqId = "R-1", text = "Local text.")
                    reqDiagram(name = "R") {
                        include(ext)
                        include(local)
                        copy(source = local, target = ext)
                    }
                }
            codes(stub).shouldBeEmpty()
        }

        test("218: an external stub with text that differs from a local requirement warns") {
            val m =
                sysml2Model(name = "M") {
                    val ext = externalRequirement(reqId = "R-1", source = "Doc", text = "Restore within one hour.")
                    val local = requirementDef(name = "Local", reqId = "R-1", text = "Restore within two hours.")
                    reqDiagram(name = "R") {
                        include(ext)
                        include(local)
                    }
                }
            codes(m) shouldBe listOf(218)
        }

        test("layout checker: a group id is a valid sameLayer member (no 431); showSubjectEdges outside REQ is 438") {
            val m =
                sysml2Model(name = "M") {
                    val a = requirementDef(name = "A", text = "a")
                    val b = requirementDef(name = "B", text = "b")
                    reqDiagram(name = "R") {
                        val g1 =
                            group(name = "G1") {
                                include(a)
                            }
                        val g2 =
                            group(name = "G2") {
                                include(b)
                            }
                        layout {
                            wrapColumns = 2
                            sameLayer(g1, g2)
                            showSubjectEdges = true
                        }
                    }
                    bdd(name = "D") {
                        layout { showSubjectEdges = true }
                    }
                }
            val req = m.diagrams.filterIsInstance<ReqDiagram>().single()
            req.layout!!.sameLayer shouldBe listOf(listOf("group:G1", "group:G2"))
            req.layout!!.showSubjectEdges shouldBe true
            Sysml2LayoutHintChecker.check(model = m, diagram = req).shouldBeEmpty()
            val bdd = m.diagrams.filterIsInstance<BdDiagram>().single()
            Sysml2LayoutHintChecker.check(model = m, diagram = bdd).map { it.code } shouldBe listOf(438)
        }

        test("printer: externalRequirement, showSubjectEdges and a sameLayer of groups survive a print") {
            val m =
                sysml2Model(name = "M") {
                    val ext = externalRequirement(reqId = "X-1", source = "Doc 11", text = "Text.")
                    val r = requirementDef(name = "R", reqId = "R-2", text = "r")
                    reqDiagram(name = "D") {
                        include(ext)
                        val g1 = group(name = "G1") { include(r) }
                        val g2 = group(name = "G2") {}
                        layout {
                            showSubjectEdges = false
                            sameLayer(g1, g2)
                        }
                    }
                }
            val dsl = Sysml2DslPrinter.print(m)
            dsl shouldContain "externalRequirement(reqId = \"X-1\", source = \"Doc 11\", text = \"Text.\")"
            dsl shouldContain "showSubjectEdges = false"
            dsl shouldContain "sameLayerById(\"group:G1\", \"group:G2\")"
        }
    })
