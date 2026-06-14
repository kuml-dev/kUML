package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KtSealedHierarchyMapperTest :
    FunSpec({

        test("sealed hierarchy emits generalization relations for subtypes") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Shape.kt" to
                            """
                            sealed class Shape
                            class Circle(val r: Double) : Shape()
                            class Rectangle(val w: Double, val h: Double) : Shape()
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val diagram = success.model.root as KumlDiagram

            // sealed parent should have <<sealed>> stereotype
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            val shape = classes.find { it.name == "Shape" }
            shape shouldNotBe null
            shape?.stereotypes?.contains("sealed") shouldBe true

            // There should be 2 generalizations: Circle→Shape and Rectangle→Shape
            val generalizations = diagram.elements.filterIsInstance<UmlGeneralization>()
            generalizations shouldHaveAtLeastSize 2
            val shapeId = shape?.id
            generalizations.count { it.generalId == shapeId } shouldBe 2
        }
    })
