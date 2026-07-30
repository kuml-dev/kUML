package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.generalization
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class UmlToErmInheritanceTest :
    FunSpec({

        val transformer = UmlToErmTransformer()

        test("JOINED (default) creates one entity per class, weak subtype, identifying relationship and a category") {
            val diagram =
                classDiagram(name = "Fleet") {
                    val vehicle =
                        classOf(name = "Vehicle") {
                            attribute(name = "id", type = "UUID")
                            attribute(name = "make", type = "String")
                        }
                    val car =
                        classOf(name = "Car") {
                            attribute(name = "doors", type = "Integer")
                        }
                    generalization(specific = car, general = vehicle)
                }

            val result = transformer.transform(source = diagram, ctx = TransformContext()) as TransformResult.Success
            val model = result.output

            model.entities shouldHaveSize 2
            val vehicles = model.entities.first { it.name == "vehicles" }
            val cars = model.entities.first { it.name == "cars" }

            cars.weak shouldBe true
            cars.primaryKey shouldHaveSize 1
            val carPk = cars.primaryKey.first()
            carPk.name shouldBe "id"
            carPk.foreignKey shouldNotBe null
            carPk.foreignKey!!.targetEntityId shouldBe vehicles.id
            carPk.type shouldBe vehicles.primaryKey.first().type
            cars.attributeByName("doors") shouldNotBe null

            val rel = model.relationships.single()
            rel.kind shouldBe RelationshipKind.IDENTIFYING
            rel.sourceEntityId shouldBe vehicles.id
            rel.targetEntityId shouldBe cars.id

            val category = model.categories.single()
            category.supertypeEntityId shouldBe vehicles.id
            category.subtypeEntityIds shouldBe listOf(cars.id)
        }

        test(
            "JOINED with 3+ levels stays correct even when an intermediate subtype is declared " +
                "after its own child (declaration order != root-to-leaf order)",
        ) {
            val diagram =
                classDiagram(name = "Chain") {
                    // Declaration order is Alpha, Gamma, Beta — Beta is Gamma's parent but is
                    // declared *after* Gamma. Regression test for the review finding: applyJoinedLinks
                    // must not rely on `classes` being in root-to-leaf order.
                    val alpha =
                        classOf(name = "Alpha") {
                            attribute(name = "id", type = "UUID")
                            attribute(name = "afield", type = "String")
                        }
                    val gamma = classOf(name = "Gamma") { attribute(name = "cfield", type = "String") }
                    val beta = classOf(name = "Beta") { attribute(name = "bfield", type = "String") }
                    generalization(specific = beta, general = alpha)
                    generalization(specific = gamma, general = beta)
                }

            val result = transformer.transform(source = diagram, ctx = TransformContext()) as TransformResult.Success
            val model = result.output

            val alphas = model.entities.first { it.name == "alphas" }
            val betas = model.entities.first { it.name == "betas" }
            val gammas = model.entities.first { it.name == "gammas" }

            betas.weak shouldBe true
            betas.primaryKey shouldHaveSize 1
            val betaPk = betas.primaryKey.first()
            betaPk.foreignKey shouldNotBe null
            betaPk.foreignKey!!.targetEntityId shouldBe alphas.id

            gammas.weak shouldBe true
            gammas.primaryKey shouldHaveSize 1
            val gammaPk = gammas.primaryKey.first()
            gammaPk.foreignKey shouldNotBe null
            gammaPk.foreignKey!!.targetEntityId shouldBe betas.id

            val betaToGammaRel = model.relationships.first { it.targetEntityId == gammas.id }
            betaToGammaRel.sourceEntityId shouldBe betas.id
            betaToGammaRel.kind shouldBe RelationshipKind.IDENTIFYING

            model.categories shouldHaveSize 2
        }

        test("«Inheritance».strategy=SINGLE_TABLE merges all subtype columns into one entity plus a discriminator") {
            val diagram =
                classDiagram(name = "Fleet") {
                    applyProfile(ermMappingProfile)
                    val vehicle =
                        classOf(name = "Vehicle") {
                            stereotype(name = "Inheritance") {
                                "strategy" to "SINGLE_TABLE"
                            }
                            attribute(name = "id", type = "UUID")
                            attribute(name = "make", type = "String")
                        }
                    val car =
                        classOf(name = "Car") {
                            attribute(name = "doors", type = "Integer")
                        }
                    generalization(specific = car, general = vehicle)
                }

            val result = transformer.transform(source = diagram, ctx = TransformContext()) as TransformResult.Success
            val model = result.output

            model.entities shouldHaveSize 1
            val vehicles = model.entities.first()
            vehicles.name shouldBe "vehicles"
            vehicles.attributeByName("doors") shouldNotBe null
            vehicles.attributeByName("doors")!!.nullable shouldBe true
            vehicles.attributeByName("dtype") shouldNotBe null
            model.relationships shouldHaveSize 0
            model.categories shouldHaveSize 0
        }

        test("SINGLE_TABLE via TransformContext option (no stereotype needed)") {
            val diagram =
                classDiagram(name = "Fleet") {
                    val vehicle = classOf(name = "Vehicle") { attribute(name = "id", type = "UUID") }
                    val car = classOf(name = "Car") { attribute(name = "doors", type = "Integer") }
                    generalization(specific = car, general = vehicle)
                }
            val result =
                transformer.transform(source = diagram, ctx = TransformContext(options = mapOf("inheritance" to "SINGLE_TABLE"))) as
                    TransformResult.Success
            result.output.entities shouldHaveSize 1
        }

        test("«Inheritance».discriminatorColumn override renames the discriminator column") {
            val diagram =
                classDiagram(name = "Fleet") {
                    applyProfile(ermMappingProfile)
                    val vehicle =
                        classOf(name = "Vehicle") {
                            stereotype(name = "Inheritance") {
                                "strategy" to "SINGLE_TABLE"
                                "discriminatorColumn" to "vehicle_kind"
                            }
                            attribute(name = "id", type = "UUID")
                        }
                    val car = classOf(name = "Car") { attribute(name = "doors", type = "Integer") }
                    generalization(specific = car, general = vehicle)
                }
            val result = transformer.transform(source = diagram, ctx = TransformContext()) as TransformResult.Success
            result.output.entities
                .first()
                .attributeByName("vehicle_kind") shouldNotBe null
        }

        test("«Inheritance».strategy=TABLE_PER_CLASS gives every concrete class its own table with inherited columns") {
            val diagram =
                classDiagram(name = "Fleet") {
                    applyProfile(ermMappingProfile)
                    val vehicle =
                        classOf(name = "Vehicle") {
                            isAbstract = true
                            stereotype(name = "Inheritance") {
                                "strategy" to "TABLE_PER_CLASS"
                            }
                            attribute(name = "id", type = "UUID")
                            attribute(name = "make", type = "String")
                        }
                    val car = classOf(name = "Car") { attribute(name = "doors", type = "Integer") }
                    val truck = classOf(name = "Truck") { attribute(name = "payloadKg", type = "Integer") }
                    generalization(specific = car, general = vehicle)
                    generalization(specific = truck, general = vehicle)
                }

            val result = transformer.transform(source = diagram, ctx = TransformContext()) as TransformResult.Success
            val model = result.output

            model.entities.map { it.name } shouldContainExactlyInAnyOrder listOf("cars", "trucks")
            val cars = model.entities.first { it.name == "cars" }
            cars.attributeByName("make") shouldNotBe null
            cars.attributeByName("doors") shouldNotBe null
            cars.primaryKey shouldHaveSize 1
            cars.primaryKey.first().name shouldBe "id"

            val trucks = model.entities.first { it.name == "trucks" }
            trucks.attributeByName("make") shouldNotBe null
            trucks.attributeByName("payload_kg") shouldNotBe null

            model.relationships shouldHaveSize 0
            model.categories shouldHaveSize 0
        }

        test("TABLE_PER_CLASS with a concrete root gives the root its own table too") {
            val diagram =
                classDiagram(name = "Fleet") {
                    applyProfile(ermMappingProfile)
                    val vehicle =
                        classOf(name = "Vehicle") {
                            stereotype(name = "Inheritance") {
                                "strategy" to "TABLE_PER_CLASS"
                            }
                            attribute(name = "id", type = "UUID")
                            attribute(name = "make", type = "String")
                        }
                    val car = classOf(name = "Car") { attribute(name = "doors", type = "Integer") }
                    generalization(specific = car, general = vehicle)
                }
            val result = transformer.transform(source = diagram, ctx = TransformContext()) as TransformResult.Success
            result.output.entities.map { it.name } shouldContainExactlyInAnyOrder listOf("vehicles", "cars")
        }
    })
