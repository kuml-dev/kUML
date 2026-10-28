package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.ParSeverity
import dev.kuml.sysml2.constraint.Sysml2ParChecker
import dev.kuml.sysml2.dsl.pin
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/** Wave C4 — nested constraint definitions, `pin()`, structure checker, printer and JSON compatibility. */
class Sysml2C4ParTest :
    FunSpec({
        fun p(
            name: String,
            dir: ConstraintParameterDirection = ConstraintParameterDirection.In,
        ) = ConstraintParameter(name = name, direction = dir)

        fun nested(): Sysml2Model =
            sysml2Model(name = "N") {
                val vehicle = partDef(name = "Vehicle") { attribute(name = "mass", typeId = "Mass") }
                val dyn =
                    constraintDef(
                        name = "VehicleDynamics",
                        expression = "a = F / m",
                        parameters = listOf(p("m"), p("a", ConstraintParameterDirection.Out)),
                    )
                val newton =
                    constraintDef(
                        name = "NewtonsLaw",
                        expression = "F = m * a",
                        parameters = listOf(p("m"), p("a"), p("F", ConstraintParameterDirection.Out)),
                        parent = dyn,
                    )
                bind(name = "b1", source = "Vehicle::mass", target = dyn.pin("m"))
                bind(name = "b2", source = dyn.pin("m"), target = newton.pin("m"))
                parDiagram(name = "P") {
                    include(vehicle)
                    include(dyn)
                    include(newton)
                }
            }

        test("constraintDef(parent) stores ownerConstraintId, top-level keeps null") {
            val m = nested()
            m.definitions.filterIsInstance<ConstraintDefinition>().associate { it.id to it.ownerConstraintId } shouldBe
                mapOf("VehicleDynamics" to null, "NewtonsLaw" to "VehicleDynamics")
        }

        test("pin() returns the endpoint id and rejects unknown parameters") {
            val c = ConstraintDefinition(id = "C", name = "C", parameters = listOf(p("x")))
            c.pin("x") shouldBe "C::x"
            shouldThrow<IllegalArgumentException> { c.pin("y") }.message shouldContain "no parameter 'y'"
        }

        test("printer emits the parent variable, owner first; flat models print unchanged") {
            val dsl = Sysml2DslPrinter.print(nested())
            dsl shouldContain "parent = s2v"
            (dsl.indexOf("name = \"VehicleDynamics\"") < dsl.indexOf("name = \"NewtonsLaw\"")) shouldBe true
            val flat =
                Sysml2DslPrinter.print(
                    sysml2Model(name = "F") { constraintDef(name = "C", parameters = listOf(p("x"))) },
                )
            flat shouldNotContain "parent ="
            flat shouldNotContain "val s2v"
        }

        test("JSON round-trips, and old JSON without ownerConstraintId still decodes") {
            val m = nested()
            Json.decodeFromString<Sysml2Model>(Json.encodeToString(Sysml2Model.serializer(), m)) shouldBe m
            val old = """{"id":"C","name":"C","qualifiedName":"C","expression":"x = 1"}"""
            Json.decodeFromString(ConstraintDefinition.serializer(), old).ownerConstraintId shouldBe null
        }

        test("checker accepts a well-formed nesting and pin bindings") {
            Sysml2ParChecker.check(model = nested(), diagram = nested().diagrams.single() as ParDiagram).shouldBeEmpty()
        }

        fun chain(
            depth: Int,
            params: Int = 1,
        ): Sysml2Model =
            Sysml2Model(
                name = "D",
                definitions =
                    (1..depth).map { i ->
                        ConstraintDefinition(
                            id = "C$i",
                            name = "C$i",
                            parameters = (1..params).map { p("p$it") },
                            ownerConstraintId = if (i == 1) null else "C${i - 1}",
                        )
                    },
            )

        test("depth 4 is fine, depth 5 is error 437") {
            Sysml2ParChecker.check(model = chain(4)).shouldBeEmpty()
            val issues = Sysml2ParChecker.check(model = chain(5))
            issues.map { it.code to it.elementId } shouldBe listOf(437 to "C5")
            issues.single().severity shouldBe ParSeverity.Error
        }

        test("cycle is error 438 and terminates") {
            val m =
                Sysml2Model(
                    name = "Cy",
                    definitions =
                        listOf(
                            ConstraintDefinition(id = "A", name = "A", ownerConstraintId = "B"),
                            ConstraintDefinition(id = "B", name = "B", ownerConstraintId = "A"),
                            ConstraintDefinition(id = "S", name = "S", ownerConstraintId = "S"),
                        ),
                )
            Sysml2ParChecker.check(model = m).map { it.code }.toSet() shouldBe setOf(438)
        }

        test("unknown owner is 439, more than 64 parameters is 440") {
            val m = Sysml2Model(name = "U", definitions = listOf(ConstraintDefinition(id = "A", name = "A", ownerConstraintId = "Nope")))
            Sysml2ParChecker.check(model = m).map { it.code } shouldBe listOf(439)
            Sysml2ParChecker.check(model = chain(1, params = 65)).map { it.code } shouldBe listOf(440)
            Sysml2ParChecker.check(model = chain(1, params = 64)).shouldBeEmpty()
        }

        test("binding to an unknown parameter is warning 441") {
            val m =
                Sysml2Model(
                    name = "W",
                    definitions = listOf(ConstraintDefinition(id = "A", name = "A", parameters = listOf(p("x")))),
                    usages =
                        listOf(
                            BindingConnectorUsage(
                                id = "b",
                                name = "b",
                                qualifiedName = "b",
                                sourceEndId = "A::zz",
                                targetEndId = "Vehicle::m",
                            ),
                            BindingConnectorUsage(
                                id = "ok",
                                name = "ok",
                                qualifiedName = "ok",
                                sourceEndId = "A::x",
                                targetEndId = "Vehicle::m",
                            ),
                        ),
                )
            val issues = Sysml2ParChecker.check(model = m)
            issues.map { it.code to it.elementId } shouldBe listOf(441 to "b")
            issues.single().severity shouldBe ParSeverity.Warning
        }
    })
