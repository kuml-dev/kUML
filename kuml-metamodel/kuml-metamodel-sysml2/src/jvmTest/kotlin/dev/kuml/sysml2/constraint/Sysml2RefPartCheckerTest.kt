package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Nacharbeit 53, P-BDD-05: a `refPart` must point at a part definition. */
class Sysml2RefPartCheckerTest :
    FunSpec(body = {
        fun refPartIssues(
            model: dev.kuml.sysml2.Sysml2Model,
            diagram: BdDiagram? = null,
        ) = Sysml2AnalysisChecker.check(model = model, diagram = diagram).filter { it.code == "SYSML2_REFPART_TYPE_UNKNOWN" }

        test("a typo in the typeId gives exactly one issue at the refPart usage") {
            val m =
                sysml2Model(name = "R") {
                    partDef(name = "Gateway")
                    partDef(name = "Controller") { refPart(name = "gw", typeId = "Gatewya") }
                    bdd(name = "D") {}
                }
            val issues = refPartIssues(model = m)
            issues shouldHaveSize 1
            issues.single().elementId shouldBe "Controller::gw"
            issues.single().message shouldContain "Gatewya"
        }

        test("a mutual forward reference by name is fine") {
            val m =
                sysml2Model(name = "R") {
                    partDef(name = "Gateway") { refPart(name = "ctl", typeId = "Controller") }
                    partDef(name = "Controller") { refPart(name = "gw", typeId = "Gateway") }
                }
            refPartIssues(model = m).shouldBeEmpty()
        }

        test("a refPart onto an attribute definition is an error") {
            val m =
                sysml2Model(name = "R") {
                    val unit = attributeDef(name = "Pressure", unitSymbol = "Pa")
                    partDef(name = "Controller") { refPart(name = "p", typeId = unit.id) }
                }
            val issues = refPartIssues(model = m)
            issues shouldHaveSize 1
            issues.single().message shouldContain "no part definition"
        }

        test("a part definition with a custom id gets the 'use its id' hint when referenced by name") {
            val m =
                sysml2Model(name = "R") {
                    partDef(name = "Sensor", id = "sensor-1")
                    partDef(name = "Controller") { refPart(name = "s", typeId = "Sensor") }
                }
            val issues = refPartIssues(model = m)
            issues shouldHaveSize 1
            issues.single().hint shouldContain "sensor-1"
        }

        test("a name match that is no part definition gets the generic hint") {
            val m =
                sysml2Model(name = "R") {
                    attributeDef(name = "Pressure", id = "p-1")
                    partDef(name = "Controller") { refPart(name = "s", typeId = "Pressure") }
                }
            val issues = refPartIssues(model = m)
            issues shouldHaveSize 1
            issues.single().hint shouldContain "Check the spelling"
        }

        test("a diagram that excludes the owner produces no finding") {
            val m =
                sysml2Model(name = "R") {
                    partDef(name = "Controller") { refPart(name = "gw", typeId = "Gatewya") }
                    partDef(name = "Other")
                }
            refPartIssues(model = m, diagram = BdDiagram(name = "only", elementIds = listOf("Other"))).shouldBeEmpty()
            refPartIssues(model = m, diagram = BdDiagram(name = "all", elementIds = listOf("Controller"))) shouldHaveSize 1
        }

        test("a model without refPart is unaffected") {
            val m =
                sysml2Model(name = "R") {
                    partDef(name = "Gateway")
                    partDef(name = "Controller") { part(name = "gw", typeId = "Missing") }
                }
            refPartIssues(model = m).shouldBeEmpty()
        }
    })
