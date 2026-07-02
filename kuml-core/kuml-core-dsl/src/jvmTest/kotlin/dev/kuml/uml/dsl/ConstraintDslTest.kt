package dev.kuml.uml.dsl

import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlConstraintKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements

class ConstraintDslTest :
    FunSpec({

        test("constraint() stores body in UmlClass.constraints") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        constraint("hasAttr", "self.attributes->size() > 0")
                        attribute("id", "UUID")
                    }
                }.elements
                    .filterIsInstance<UmlClass>()
                    .first()

            cls.constraints shouldHaveSize 1
            cls.constraints[0].name shouldBe "hasAttr"
            cls.constraints[0].body shouldBe "self.attributes->size() > 0"
        }

        test("constraint IDs are disambiguated for duplicate names") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        constraint("check", "self.attributes->size() > 0")
                        constraint("check", "self.operations->notEmpty()")
                    }
                }.elements
                    .filterIsInstance<UmlClass>()
                    .first()

            cls.constraints shouldHaveSize 2
            val ids = cls.constraints.map { it.id }
            ids[0] shouldBe "Order::check"
            ids[1] shouldBe "Order::check~2"
        }

        // ── def:/pre:/post:/body: stereotype DSL (V3.2.22) ──────────────────

        test("constraint() defaults to Invariant kind") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        constraint("check", "self.attributes->size() > 0")
                    }
                }.elements
                    .filterIsInstance<UmlClass>()
                    .first()

            cls.constraints[0].kind shouldBe UmlConstraintKind.Invariant
            cls.constraints[0].contextOperation shouldBe null
        }

        test("invariant() is equivalent to constraint() with the default kind") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        invariant("check", "self.attributes->size() > 0")
                    }
                }.elements
                    .filterIsInstance<UmlClass>()
                    .first()

            cls.constraints[0].kind shouldBe UmlConstraintKind.Invariant
        }

        test("definition() stores a Definition-kind constraint") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        definition("isPaid", "self.attributes->notEmpty()")
                    }
                }.elements
                    .filterIsInstance<UmlClass>()
                    .first()

            cls.constraints[0].kind shouldBe UmlConstraintKind.Definition
            cls.constraints[0].name shouldBe "isPaid"
        }

        test("precondition() stores a Precondition-kind constraint scoped to an operation") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        operation("confirm")
                        precondition("confirmPre", operation = "confirm", body = "self.operations->notEmpty()")
                    }
                }.elements
                    .filterIsInstance<UmlClass>()
                    .first()

            cls.constraints[0].kind shouldBe UmlConstraintKind.Precondition
            cls.constraints[0].contextOperation shouldBe "confirm"
        }

        test("postcondition() stores a Postcondition-kind constraint scoped to an operation") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        operation("confirm")
                        postcondition("confirmPost", operation = "confirm", body = "result.oclIsUndefined()")
                    }
                }.elements
                    .filterIsInstance<UmlClass>()
                    .first()

            cls.constraints[0].kind shouldBe UmlConstraintKind.Postcondition
            cls.constraints[0].contextOperation shouldBe "confirm"
        }

        test("body() stores a Body-kind constraint scoped to an operation") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        operation("total")
                        body("totalBody", operation = "total", body = "result.oclIsUndefined()")
                    }
                }.elements
                    .filterIsInstance<UmlClass>()
                    .first()

            cls.constraints[0].kind shouldBe UmlConstraintKind.Body
            cls.constraints[0].contextOperation shouldBe "total"
        }
    })
