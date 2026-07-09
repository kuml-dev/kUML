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
                classDiagram("Fleet") {
                    applyProfile(ermMappingProfile)
                    val vehicle =
                        classOf("Vehicle") {
                            stereotype("Inheritance") {
                                "strategy" to "SINGLE_TABLE"
                            }
                            attribute("id", "UUID")
                            attribute("name", "String")
                        }
                    val car =
                        classOf("Car") {
                            // Same attribute name as the supertype's own "name" column — after
                            // merging, the entity would have two "name" columns (ErmConstraintChecker rule 4).
                            attribute("name", "String")
                        }
                    generalization(specific = car, general = vehicle)
                }

            val result = transformer.transform(diagram, TransformContext())
            result.shouldBeInstanceOf<TransformResult.Failure>()
            result.errors.shouldNotBeEmpty()
            result.errors.first().message shouldContain "name"
        }
    })
