package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain

/**
 * V2.0.20b — tests for [Sysml2ConstraintChecker].
 */
class Sysml2ConstraintCheckerTest :
    FunSpec({

        // ── 1. Newton's law — no type errors ──────────────────────────────────

        test("Newton's law F = m * a with all-Real env produces no errors") {
            val model =
                sysml2Model(name = "NewtonCheck") {
                    attributeDef(name = "Force")
                    attributeDef(name = "Mass")
                    attributeDef(name = "Acceleration")
                    val newton =
                        constraintDef(
                            name = "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "F", typeId = "Force", direction = ConstraintParameterDirection.Out),
                                    ConstraintParameter(name = "m", typeId = "Mass", direction = ConstraintParameterDirection.In),
                                    ConstraintParameter(name = "a", typeId = "Acceleration", direction = ConstraintParameterDirection.In),
                                ),
                        )
                    val vehicle =
                        partDef(name = "Vehicle") {
                            attribute(name = "mass", typeId = "Mass")
                            attribute(name = "acceleration", typeId = "Acceleration")
                            attribute(name = "force", typeId = "Force")
                        }
                    bind(name = "F_force", source = "NewtonsLaw::F", target = "Vehicle::force")
                    bind(name = "m_mass", source = "NewtonsLaw::m", target = "Vehicle::mass")
                    bind(name = "a_acc", source = "NewtonsLaw::a", target = "Vehicle::acceleration")
                    parDiagram(name = "Newton PAR") {
                        include(newton)
                        include(vehicle)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model = model, diagram = diagram)
            errors.shouldBeEmpty()
        }

        // ── 2. Type mismatch: Bool in arithmetic context ───────────────────────

        test("F = m + true produces one type error") {
            val model =
                sysml2Model(name = "TypeMismatch") {
                    attributeDef(name = "Force")
                    attributeDef(name = "Mass")
                    val c =
                        constraintDef(
                            name = "BadConstraint",
                            expression = "F = m + true",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "F", typeId = "Force", direction = ConstraintParameterDirection.Out),
                                    ConstraintParameter(name = "m", typeId = "Mass", direction = ConstraintParameterDirection.In),
                                ),
                        )
                    val part =
                        partDef(name = "P") {
                            attribute(name = "force", typeId = "Force")
                            attribute(name = "mass", typeId = "Mass")
                        }
                    bind(name = "F_f", source = "BadConstraint::F", target = "P::force")
                    bind(name = "m_m", source = "BadConstraint::m", target = "P::mass")
                    parDiagram(name = "BadPAR") {
                        include(c)
                        include(part)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model = model, diagram = diagram)
            errors shouldHaveSize 1
        }

        // ── 3. Unparseable expression ─────────────────────────────────────────

        test("unparseable expression '@@@' produces one parse error") {
            val model =
                sysml2Model(name = "Unparseable") {
                    val c =
                        constraintDef(
                            name = "BadExpr",
                            expression = "@@@",
                            parameters =
                                listOf(ConstraintParameter(name = "x", typeId = "Mass", direction = ConstraintParameterDirection.In)),
                        )
                    attributeDef(name = "Mass")
                    parDiagram(name = "BadExprPAR") { include(c) }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model = model, diagram = diagram)
            errors shouldHaveSize 1
            errors[0].message shouldContain "failed to parse"
        }

        // ── 4. Empty expression returns no errors ─────────────────────────────

        test("empty constraint expression produces no errors") {
            val model =
                sysml2Model(name = "EmptyExpr") {
                    val c = constraintDef(name = "Empty", expression = "")
                    parDiagram(name = "EmptyPAR") { include(c) }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model = model, diagram = diagram)
            errors.shouldBeEmpty()
        }

        // ── 5. Unknown parameter type — no error in V2.0.20b ─────────────────

        test("parameter with no binding resolves to Unknown — no type error") {
            val model =
                sysml2Model(name = "UnknownParam") {
                    val c =
                        constraintDef(
                            name = "Unknown",
                            expression = "x + y",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "x", typeId = null, direction = ConstraintParameterDirection.In),
                                    ConstraintParameter(name = "y", typeId = null, direction = ConstraintParameterDirection.In),
                                ),
                        )
                    parDiagram(name = "UnknownPAR") { include(c) }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model = model, diagram = diagram)
            // Unknown + Unknown = Unknown (not a TypeError)
            errors.shouldBeEmpty()
        }

        // ── 6. Full newton-second-law-par DSL fixture — 0 errors ─────────────

        test("newton-second-law-par DSL fixture produces no constraint errors") {
            val model =
                sysml2Model(name = "NewtonModel") {
                    attributeDef(name = "Mass")
                    attributeDef(name = "Acceleration")
                    attributeDef(name = "Force")
                    val newton =
                        constraintDef(
                            name = "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "F", typeId = "Force", direction = ConstraintParameterDirection.Out),
                                    ConstraintParameter(name = "m", typeId = "Mass", direction = ConstraintParameterDirection.In),
                                    ConstraintParameter(name = "a", typeId = "Acceleration", direction = ConstraintParameterDirection.In),
                                ),
                        )
                    val vehicle =
                        partDef(name = "Vehicle") {
                            attribute(name = "mass", typeId = "Mass")
                            attribute(name = "acceleration", typeId = "Acceleration")
                            attribute(name = "force", typeId = "Force")
                        }
                    bind(name = "F_to_force", source = "NewtonsLaw::F", target = "Vehicle::force")
                    bind(name = "m_to_mass", source = "NewtonsLaw::m", target = "Vehicle::mass")
                    bind(name = "a_to_acceleration", source = "NewtonsLaw::a", target = "Vehicle::acceleration")
                    parDiagram(name = "Newton — F = m·a applied to Vehicle") {
                        include(newton)
                        include(vehicle)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model = model, diagram = diagram)
            errors.shouldBeEmpty()
        }

        // ── 7. Two operands, correct types — no error ─────────────────────────

        test("two Real operands in comparison produce no errors") {
            val model =
                sysml2Model(name = "TwoOps") {
                    attributeDef(name = "Mass")
                    val c =
                        constraintDef(
                            name = "MassCheck",
                            expression = "m1 == m2",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "m1", typeId = "Mass", direction = ConstraintParameterDirection.In),
                                    ConstraintParameter(name = "m2", typeId = "Mass", direction = ConstraintParameterDirection.In),
                                ),
                        )
                    val p =
                        partDef(name = "P") {
                            attribute(name = "mass1", typeId = "Mass")
                            attribute(name = "mass2", typeId = "Mass")
                        }
                    bind(name = "b1", source = "MassCheck::m1", target = "P::mass1")
                    bind(name = "b2", source = "MassCheck::m2", target = "P::mass2")
                    parDiagram(name = "TwoOpsPAR") {
                        include(c)
                        include(p)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model = model, diagram = diagram)
            errors.shouldBeEmpty()
        }

        // ── 8. Mixed Real/Int comparison — no error (Int and Real are compatible) ─

        test("mixed Real and Int comparison produces no errors (compatible types)") {
            val model =
                sysml2Model(name = "MixedNumeric") {
                    attributeDef(name = "Force")
                    attributeDef(name = "Count")
                    val c =
                        constraintDef(
                            name = "MixedCheck",
                            expression = "f > n",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "f", typeId = "Force", direction = ConstraintParameterDirection.In),
                                    ConstraintParameter(name = "n", typeId = "Count", direction = ConstraintParameterDirection.In),
                                ),
                        )
                    val p =
                        partDef(name = "P") {
                            attribute(name = "force", typeId = "Force")
                            attribute(name = "count", typeId = "Count")
                        }
                    bind(name = "bf", source = "MixedCheck::f", target = "P::force")
                    bind(name = "bn", source = "MixedCheck::n", target = "P::count")
                    parDiagram(name = "MixedPAR") {
                        include(c)
                        include(p)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model = model, diagram = diagram)
            // Real > Int — both numeric, so comparison is valid (type checker allows it)
            errors.shouldBeEmpty()
        }
    })
