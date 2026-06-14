package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlAssociation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe

class KtAssociationDetectorTest :
    FunSpec({

        test("property with internal classifier type creates UmlAssociation") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Domain.kt" to
                            """
                            class Engine(val horsepower: Int)
                            class Car(val engine: Engine)
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val diagram = success.model.root as KumlDiagram
            val associations = diagram.elements.filterIsInstance<UmlAssociation>()
            associations shouldHaveAtLeastSize 1
            // One end should reference Car or Engine
            val assoc = associations.first()
            val endTypeIds = assoc.ends.map { it.typeId }.toSet()
            endTypeIds.any { it.contains("Car") || it.contains("Engine") } shouldBe true
        }
    })
