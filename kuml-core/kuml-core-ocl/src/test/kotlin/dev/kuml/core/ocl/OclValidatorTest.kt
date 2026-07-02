package dev.kuml.core.ocl

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlConstraint
import dev.kuml.uml.UmlConstraintKind
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OclValidatorTest :
    FunSpec({

        test("reports violation for empty class with size constraint") {
            val cls =
                UmlClass(
                    id = "Empty",
                    name = "Empty",
                    constraints =
                        listOf(
                            UmlConstraint(
                                id = "Empty::c",
                                name = "c",
                                body = "self.attributes->size() > 0",
                            ),
                        ),
                )
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(cls),
                )
            val result = OclValidator.validate(diagram)
            result.valid shouldBe false
            result.violations.size shouldBe 1
        }

        // ── def: / pre: / post: / body: (V3.2.22) ───────────────────────────

        test("def: is not itself checked, but its value is bound for later invariants") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes = listOf(UmlProperty(id = "Order::total", name = "total", type = UmlTypeRef("Int"))),
                    constraints =
                        listOf(
                            UmlConstraint(
                                id = "Order::hasTotal",
                                name = "hasTotal",
                                body = "self.attributes->size() > 0",
                                kind = UmlConstraintKind.Definition,
                            ),
                            UmlConstraint(
                                id = "Order::inv",
                                name = "inv",
                                body = "hasTotal",
                                kind = UmlConstraintKind.Invariant,
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls))
            val result = OclValidator.validate(diagram)
            result.valid shouldBe true
        }

        test("pre: passes when it evaluates true against self at operation entry") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    operations = listOf(UmlOperation(id = "Order::confirm", name = "confirm")),
                    constraints =
                        listOf(
                            UmlConstraint(
                                id = "Order::confirmPre",
                                name = "confirmPre",
                                body = "self.operations->notEmpty()",
                                kind = UmlConstraintKind.Precondition,
                                contextOperation = "confirm",
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls))
            val result = OclValidator.validate(diagram)
            result.valid shouldBe true
        }

        test("pre:/post:/body: report a structural violation when contextOperation is unknown") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    constraints =
                        listOf(
                            UmlConstraint(
                                id = "Order::pre",
                                name = "pre",
                                body = "true",
                                kind = UmlConstraintKind.Precondition,
                                contextOperation = "doesNotExist",
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls))
            val result = OclValidator.validate(diagram)
            result.valid shouldBe false
            result.violations.single().message shouldBe
                "Precondition constraint 'pre' on 'Order' references unknown operation 'doesNotExist'"
        }

        test("post: binds 'result' to null in this static-validation evaluator") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    operations = listOf(UmlOperation(id = "Order::confirm", name = "confirm")),
                    constraints =
                        listOf(
                            UmlConstraint(
                                id = "Order::confirmPost",
                                name = "confirmPost",
                                body = "result.oclIsUndefined()",
                                kind = UmlConstraintKind.Postcondition,
                                contextOperation = "confirm",
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls))
            val result = OclValidator.validate(diagram)
            result.valid shouldBe true
        }

        test("body: binds 'result' to null in this static-validation evaluator") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    operations = listOf(UmlOperation(id = "Order::total", name = "total")),
                    constraints =
                        listOf(
                            UmlConstraint(
                                id = "Order::totalBody",
                                name = "totalBody",
                                body = "result.oclIsUndefined()",
                                kind = UmlConstraintKind.Body,
                                contextOperation = "total",
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls))
            val result = OclValidator.validate(diagram)
            result.valid shouldBe true
        }
    })
