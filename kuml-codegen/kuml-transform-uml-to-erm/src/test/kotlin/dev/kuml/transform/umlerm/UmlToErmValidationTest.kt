package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.generalization
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class UmlToErmValidationTest :
    FunSpec({

        val transformer = UmlToErmTransformer()

        test("SINGLE_TABLE column-name collision between supertype and subtype fails the transform") {
            val diagram =
                classDiagram(name = "Fleet") {
                    applyProfile(ermMappingProfile)
                    val vehicle =
                        classOf(name = "Vehicle") {
                            stereotype(name = "Inheritance") {
                                "strategy" to "SINGLE_TABLE"
                            }
                            attribute(name = "id", type = "UUID")
                            attribute(name = "name", type = "String")
                        }
                    val car =
                        classOf(name = "Car") {
                            // Same attribute name as the supertype's own "name" column — after
                            // merging, the entity would have two "name" columns (ErmConstraintChecker rule 4).
                            attribute(name = "name", type = "String")
                        }
                    generalization(specific = car, general = vehicle)
                }

            val result = transformer.transform(source = diagram, ctx = TransformContext())
            result.shouldBeInstanceOf<TransformResult.Failure>()
            result.errors.shouldNotBeEmpty()
            result.errors.first().message shouldContain "name"
        }
    })
