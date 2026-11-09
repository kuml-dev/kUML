package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.LayoutSeverity
import dev.kuml.sysml2.constraint.Sysml2LayoutHintChecker
import dev.kuml.sysml2.dsl.GroupLayoutScope
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Nacharbeit 49 - group-local `layout { wrapColumns }` (DSL, printer, checker 444) and the invisible-subject warning 443. */
class Sysml2Nacharbeit49Test :
    FunSpec({
        fun withGroupColumns(n: Int?) =
            sysml2Model(name = "M") {
                val a = requirementDef(name = "A", text = "a")
                val b = requirementDef(name = "B", text = "b")
                reqDiagram(name = "R") {
                    group(name = "G") {
                        include(a)
                        include(b)
                        if (n != null) layout { wrapColumns = n }
                    }
                }
            }

        test("group layout { wrapColumns = 3 } compiles and lands on the RequirementGroup") {
            val g =
                withGroupColumns(3)
                    .diagrams
                    .filterIsInstance<ReqDiagram>()
                    .single()
                    .groups
                    .single()
            g.wrapColumns shouldBe 3
            withGroupColumns(null)
                .diagrams
                .filterIsInstance<ReqDiagram>()
                .single()
                .groups
                .single()
                .wrapColumns shouldBe null
        }

        test("the group layout scope offers wrapColumns only") {
            val members =
                GroupLayoutScope::class.java.declaredMethods
                    .map { it.name }
                    .filter { it.startsWith("get") || it.startsWith("set") }
            members shouldContainExactlyInAnyOrder listOf("getWrapColumns", "setWrapColumns")
        }

        test("printer: the group-local wrapColumns survives a print; an unset value is not printed") {
            Sysml2DslPrinter.print(withGroupColumns(3)) shouldContain "wrapColumns = 3)"
            val without = Sysml2DslPrinter.print(withGroupColumns(null))
            (without.contains("groupById(")) shouldBe true
            (without.lines().filter { it.contains("groupById(") }.any { it.contains("wrapColumns") }) shouldBe false
        }

        test("444: a group-local wrapColumns outside 1..32 is an error, in range is silent") {
            for (bad in listOf(0, -1, 33)) {
                val m = withGroupColumns(bad)
                val issues = Sysml2LayoutHintChecker.check(model = m, diagram = m.diagrams.filterIsInstance<ReqDiagram>().single())
                issues.map { it.code } shouldBe listOf(444)
                issues.single().severity shouldBe LayoutSeverity.Error
                issues.single().elementId shouldBe "G"
            }
            for (ok in listOf(1, 12, 32)) {
                val m = withGroupColumns(ok)
                Sysml2LayoutHintChecker.check(model = m, diagram = m.diagrams.filterIsInstance<ReqDiagram>().single()).shouldBeEmpty()
            }
        }

        test("443: showSubjectEdges with a subject that is not in the diagram warns, a visible subject does not") {
            val m =
                sysml2Model(name = "M") {
                    val p = partDef(name = "Vitec")
                    partDef(name = "Hidden")
                    val a = requirementDef(name = "A", subjectRef = "Vitec")
                    val b = requirementDef(name = "B", subjectRef = "Hidden")
                    reqDiagram(name = "R") {
                        include(p)
                        include(a)
                        include(b)
                        layout { showSubjectEdges = true }
                    }
                    reqDiagram(name = "Quiet") {
                        include(p)
                        include(a)
                        include(b)
                    }
                }
            val loud = m.diagrams.filterIsInstance<ReqDiagram>().first { it.name == "R" }
            val issues = Sysml2LayoutHintChecker.check(model = m, diagram = loud)
            issues.map { it.code } shouldBe listOf(443)
            issues.single().severity shouldBe LayoutSeverity.Warning
            issues.single().elementId shouldBe "B"
            val quiet = m.diagrams.filterIsInstance<ReqDiagram>().first { it.name == "Quiet" }
            Sysml2LayoutHintChecker.check(model = m, diagram = quiet).shouldBeEmpty()
        }
    })
