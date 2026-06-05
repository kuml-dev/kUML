package dev.kuml.core.ocl

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlConstraint
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
    })
