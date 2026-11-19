package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Nacharbeit 116: PAR443 (binding endpoint names an attribute the part does not have). */
class Sysml2ParAttributeCheckerTest :
    FunSpec(body = {
        fun model(
            source: String,
            includeVehicle: Boolean = true,
            specializesId: String? = null,
            withParent: Boolean = true,
        ): Pair<Sysml2Model, dev.kuml.sysml2.ParDiagram> {
            var diagram: dev.kuml.sysml2.ParDiagram? = null
            val m =
                sysml2Model(name = "P") {
                    val mass = attributeDef(name = "Mass")
                    if (withParent) partDef(name = "Base") { attribute(name = "weight", typeId = mass.id) }
                    val vehicle = partDef(name = "Vehicle", specializesId = specializesId) { attribute(name = "mass", typeId = mass.id) }
                    val newton =
                        constraintDef(
                            name = "Newton",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "m", typeId = mass.id, direction = ConstraintParameterDirection.In),
                                ),
                        )
                    bind(name = "b1", source = source, target = "Newton::m")
                    diagram =
                        parDiagram(name = "par") {
                            if (includeVehicle) include(vehicle)
                            include(newton)
                        }
                }
            return m to diagram!!
        }

        fun issues(
            source: String,
            includeVehicle: Boolean = true,
            specializesId: String? = null,
        ): List<ParIssue> {
            val (m, d) = model(source, includeVehicle, specializesId)
            return Sysml2ParChecker.check(model = m, diagram = d)
        }

        test("PAR443: a mistyped attribute gives exactly one warning naming part and attribute") {
            val found = issues("Vehicle::masse")
            found shouldHaveSize 1
            val i = found.single()
            i.code shouldBe 443
            i.severity shouldBe ParSeverity.Warning
            i.elementId shouldBe "binding:Vehicle::masse::Newton::m"
            i.message shouldContain "Vehicle"
            i.message shouldContain "masse"
            i.hint shouldContain "mass"
        }

        test("a known attribute gives no issue") {
            issues("Vehicle::mass").shouldBeEmpty()
        }

        test("unresolvable endpoints give no warning") {
            issues("External::x").shouldBeEmpty()
            issues("Vehicle").shouldBeEmpty()
            issues("Vehicle::engine::power").shouldBeEmpty()
            issues("Vehicle::masse", includeVehicle = false).shouldBeEmpty()
        }

        test("an unknown generalization gives no warning, a known one contributes its attributes") {
            issues("Vehicle::masse", specializesId = "Unknown").shouldBeEmpty()
            issues("Vehicle::weight", specializesId = "Base").shouldBeEmpty()
            issues("Vehicle::nope", specializesId = "Base") shouldHaveSize 1
        }

        test("without a diagram all parts are candidates") {
            val (m, _) = model("Vehicle::masse", includeVehicle = false)
            Sysml2ParChecker.check(model = m).filter { it.code == 443 } shouldHaveSize 1
        }

        test("441 still fires for an unknown constraint parameter and 443 does not") {
            val m =
                sysml2Model(name = "P") {
                    val v = partDef(name = "Vehicle") { attribute(name = "mass", typeId = "Mass") }
                    val c = constraintDef(name = "Newton", expression = "a = b")
                    bind(name = "b1", source = "Vehicle::mass", target = "Newton::zz")
                    parDiagram(name = "par") {
                        include(v)
                        include(c)
                    }
                }
            Sysml2ParChecker.check(model = m).map { it.code } shouldBe listOf(441)
        }
    })
