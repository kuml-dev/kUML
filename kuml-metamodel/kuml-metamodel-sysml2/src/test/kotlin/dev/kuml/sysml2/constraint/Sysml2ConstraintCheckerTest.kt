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
                sysml2Model("NewtonCheck") {
                    attributeDef("Force")
                    attributeDef("Mass")
                    attributeDef("Acceleration")
                    val newton =
                        constraintDef(
                            name = "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter("F", "Force", ConstraintParameterDirection.Out),
                                    ConstraintParameter("m", "Mass", ConstraintParameterDirection.In),
                                    ConstraintParameter("a", "Acceleration", ConstraintParameterDirection.In),
                                ),
                        )
                    val vehicle =
                        partDef("Vehicle") {
                            attribute("mass", "Mass")
                            attribute("acceleration", "Acceleration")
                            attribute("force", "Force")
                        }
                    bind("F_force", "NewtonsLaw::F", "Vehicle::force")
                    bind("m_mass", "NewtonsLaw::m", "Vehicle::mass")
                    bind("a_acc", "NewtonsLaw::a", "Vehicle::acceleration")
                    parDiagram("Newton PAR") {
                        include(newton)
                        include(vehicle)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model, diagram)
            errors.shouldBeEmpty()
        }

        // ── 2. Type mismatch: Bool in arithmetic context ───────────────────────

        test("F = m + true produces one type error") {
            val model =
                sysml2Model("TypeMismatch") {
                    attributeDef("Force")
                    attributeDef("Mass")
                    val c =
                        constraintDef(
                            name = "BadConstraint",
                            expression = "F = m + true",
                            parameters =
                                listOf(
                                    ConstraintParameter("F", "Force", ConstraintParameterDirection.Out),
                                    ConstraintParameter("m", "Mass", ConstraintParameterDirection.In),
                                ),
                        )
                    val part =
                        partDef("P") {
                            attribute("force", "Force")
                            attribute("mass", "Mass")
                        }
                    bind("F_f", "BadConstraint::F", "P::force")
                    bind("m_m", "BadConstraint::m", "P::mass")
                    parDiagram("BadPAR") {
                        include(c)
                        include(part)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model, diagram)
            errors shouldHaveSize 1
        }

        // ── 3. Unparseable expression ─────────────────────────────────────────

        test("unparseable expression '@@@' produces one parse error") {
            val model =
                sysml2Model("Unparseable") {
                    val c =
                        constraintDef(
                            name = "BadExpr",
                            expression = "@@@",
                            parameters =
                                listOf(ConstraintParameter("x", "Mass", ConstraintParameterDirection.In)),
                        )
                    attributeDef("Mass")
                    parDiagram("BadExprPAR") { include(c) }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model, diagram)
            errors shouldHaveSize 1
            errors[0].message shouldContain "failed to parse"
        }

        // ── 4. Empty expression returns no errors ─────────────────────────────

        test("empty constraint expression produces no errors") {
            val model =
                sysml2Model("EmptyExpr") {
                    val c = constraintDef(name = "Empty", expression = "")
                    parDiagram("EmptyPAR") { include(c) }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model, diagram)
            errors.shouldBeEmpty()
        }

        // ── 5. Unknown parameter type — no error in V2.0.20b ─────────────────

        test("parameter with no binding resolves to Unknown — no type error") {
            val model =
                sysml2Model("UnknownParam") {
                    val c =
                        constraintDef(
                            name = "Unknown",
                            expression = "x + y",
                            parameters =
                                listOf(
                                    ConstraintParameter("x", null, ConstraintParameterDirection.In),
                                    ConstraintParameter("y", null, ConstraintParameterDirection.In),
                                ),
                        )
                    parDiagram("UnknownPAR") { include(c) }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model, diagram)
            // Unknown + Unknown = Unknown (not a TypeError)
            errors.shouldBeEmpty()
        }

        // ── 6. Full newton-second-law-par DSL fixture — 0 errors ─────────────

        test("newton-second-law-par DSL fixture produces no constraint errors") {
            val model =
                sysml2Model("NewtonModel") {
                    attributeDef("Mass")
                    attributeDef("Acceleration")
                    attributeDef("Force")
                    val newton =
                        constraintDef(
                            name = "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter("F", "Force", ConstraintParameterDirection.Out),
                                    ConstraintParameter("m", "Mass", ConstraintParameterDirection.In),
                                    ConstraintParameter("a", "Acceleration", ConstraintParameterDirection.In),
                                ),
                        )
                    val vehicle =
                        partDef("Vehicle") {
                            attribute("mass", "Mass")
                            attribute("acceleration", "Acceleration")
                            attribute("force", "Force")
                        }
                    bind("F_to_force", "NewtonsLaw::F", "Vehicle::force")
                    bind("m_to_mass", "NewtonsLaw::m", "Vehicle::mass")
                    bind("a_to_acceleration", "NewtonsLaw::a", "Vehicle::acceleration")
                    parDiagram("Newton — F = m·a applied to Vehicle") {
                        include(newton)
                        include(vehicle)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model, diagram)
            errors.shouldBeEmpty()
        }

        // ── 7. Two operands, correct types — no error ─────────────────────────

        test("two Real operands in comparison produce no errors") {
            val model =
                sysml2Model("TwoOps") {
                    attributeDef("Mass")
                    val c =
                        constraintDef(
                            name = "MassCheck",
                            expression = "m1 == m2",
                            parameters =
                                listOf(
                                    ConstraintParameter("m1", "Mass", ConstraintParameterDirection.In),
                                    ConstraintParameter("m2", "Mass", ConstraintParameterDirection.In),
                                ),
                        )
                    val p =
                        partDef("P") {
                            attribute("mass1", "Mass")
                            attribute("mass2", "Mass")
                        }
                    bind("b1", "MassCheck::m1", "P::mass1")
                    bind("b2", "MassCheck::m2", "P::mass2")
                    parDiagram("TwoOpsPAR") {
                        include(c)
                        include(p)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model, diagram)
            errors.shouldBeEmpty()
        }

        // ── 8. Mixed Real/Int comparison — no error (Int and Real are compatible) ─

        test("mixed Real and Int comparison produces no errors (compatible types)") {
            val model =
                sysml2Model("MixedNumeric") {
                    attributeDef("Force")
                    attributeDef("Count")
                    val c =
                        constraintDef(
                            name = "MixedCheck",
                            expression = "f > n",
                            parameters =
                                listOf(
                                    ConstraintParameter("f", "Force", ConstraintParameterDirection.In),
                                    ConstraintParameter("n", "Count", ConstraintParameterDirection.In),
                                ),
                        )
                    val p =
                        partDef("P") {
                            attribute("force", "Force")
                            attribute("count", "Count")
                        }
                    bind("bf", "MixedCheck::f", "P::force")
                    bind("bn", "MixedCheck::n", "P::count")
                    parDiagram("MixedPAR") {
                        include(c)
                        include(p)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ParDiagram
            val errors = Sysml2ConstraintChecker.check(model, diagram)
            // Real > Int — both numeric, so comparison is valid (type checker allows it)
            errors.shouldBeEmpty()
        }
    })
