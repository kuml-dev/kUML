package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** PAL wave 2C-b3: PAR442 (include of an attributeDef) and IBD501 (unresolved connection end). */
class Sysml2IbdAndParIncludeCheckerTest :
    FunSpec(body = {
        fun ibdModel(vararg ends: Pair<String, String>): Sysml2Model =
            sysml2Model(name = "M") {
                portDef(name = "Sig")
                partDef(name = "Inner") { port(name = "ein", typeId = "Sig", direction = PortDirection.In) }
                partDef(name = "Steuerung") {
                    port(name = "eingang", typeId = "Sig", direction = PortDirection.In)
                    part(name = "logik", typeId = "Inner")
                    ends.forEachIndexed { i, (from, to) -> connect(name = "c$i", typeId = "Sig", sourceEndId = from, targetEndId = to) }
                }
            }

        fun ibd() = IbdDiagram(name = "d", ownerId = "Steuerung")

        test("IBD501: an unknown end gives exactly one warning naming the usage and the end") {
            val issues = Sysml2IbdChecker.check(model = ibdModel("gibtsNicht" to "logik.ein"), diagram = ibd())
            issues shouldHaveSize 1
            val i = issues.single()
            i.code shouldBe 501
            i.severity shouldBe IbdSeverity.Warning
            i.message shouldContain "gibtsNicht"
            i.message shouldContain "the edge is not drawn"
        }

        test("IBD501: a bare owner port name resolves and gives no warning") {
            Sysml2IbdChecker.check(model = ibdModel("eingang" to "logik.ein"), diagram = ibd()).shouldBeEmpty()
        }

        test("IBD501: the number of findings is capped at 256, the rest is one summary") {
            val ends = (1..400).map { "missing$it" to "logik.ein" }.toTypedArray()
            val issues = Sysml2IbdChecker.check(model = ibdModel(*ends), diagram = ibd())
            issues shouldHaveSize Sysml2IbdChecker.MAX_ISSUES
            issues.last().message shouldContain "more unresolved ends"
        }

        fun parModel(includeAttributeDef: Boolean): Sysml2Model =
            sysml2Model(name = "P") {
                val druck = attributeDef(name = "Druck", unitSymbol = "Pa")
                val c = constraintDef(name = "Mix", expression = "a = b")
                parDiagram(name = "par") {
                    include(c)
                    if (includeAttributeDef) include(druck)
                }
            }

        test("PAR442: include of an attributeDef is a warning") {
            val m = parModel(includeAttributeDef = true)
            val issues = Sysml2ParChecker.check(model = m, diagram = m.diagrams.single() as ParDiagram)
            issues.map { it.code } shouldBe listOf(442)
            issues.single().severity shouldBe ParSeverity.Warning
            issues.single().message shouldContain "unitSymbol"
        }

        test("PAR442: no warning without the include, and none without a diagram") {
            val m = parModel(includeAttributeDef = false)
            Sysml2ParChecker.check(model = m, diagram = m.diagrams.single() as ParDiagram).shouldBeEmpty()
            Sysml2ParChecker.check(model = parModel(includeAttributeDef = true)).shouldBeEmpty()
        }
    })
