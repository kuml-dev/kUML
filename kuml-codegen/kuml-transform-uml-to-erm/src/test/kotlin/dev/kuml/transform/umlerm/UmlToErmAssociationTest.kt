package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.erm.model.ReferentialAction
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.erm.ErmProfileNames
import dev.kuml.profile.toTagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Applies [stereotype] to the single [UmlAssociation] in this diagram named [assocName]. */
private fun KumlDiagram.withAssociationStereotype(
    assocName: String?,
    stereotype: KumlStereotypeApplication,
): KumlDiagram =
    copy(
        elements =
            elements.map { element ->
                if (element is UmlAssociation && element.name == assocName) {
                    element.copy(appliedStereotypes = element.appliedStereotypes + stereotype)
                } else {
                    element
                }
            },
    )

class UmlToErmAssociationTest :
    FunSpec({

        val transformer = UmlToErmTransformer()

        test("one-to-many association adds an FK column on the many side") {
            val diagram =
                classDiagram("Orders") {
                    val customer =
                        classOf("Customer") {
                            attribute("id", "UUID")
                        }
                    val order =
                        classOf("Order") {
                            attribute("id", "UUID")
                        }
                    association(source = customer, target = order, id = "assoc-cust-order") {
                        source { multiplicity("1") }
                        target { multiplicity("0..*") }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val orderEntity = result.output.entities.first { it.name == "orders" }
            val fkCol = orderEntity.attributeByName("customer_id")
            fkCol shouldNotBe null
            fkCol!!.foreignKey shouldNotBe null
            fkCol.foreignKey!!.targetEntityId shouldBe
                result.output.entities
                    .first { it.name == "customers" }
                    .id
            fkCol.nullable shouldBe false // source end lower=1

            val rel = result.output.relationships.single()
            rel.kind shouldBe RelationshipKind.NON_IDENTIFYING
        }

        test("optional one-to-many association (source lower=0) makes the FK column nullable") {
            val diagram =
                classDiagram("Orders") {
                    val customer = classOf("Customer") { attribute("id", "UUID") }
                    val order = classOf("Order") { attribute("id", "UUID") }
                    association(source = customer, target = order) {
                        source { multiplicity("0..1") }
                        target { multiplicity("0..*") }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val orderEntity = result.output.entities.first { it.name == "orders" }
            orderEntity.attributeByName("customer_id")!!.nullable shouldBe true
        }

        test("one-to-one association puts the FK on the target end") {
            val diagram =
                classDiagram("Profiles") {
                    val user = classOf("User") { attribute("id", "UUID") }
                    val profile = classOf("Profile") { attribute("id", "UUID") }
                    association(source = user, target = profile) {
                        source { multiplicity("1") }
                        target { multiplicity("0..1") }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val profileEntity = result.output.entities.first { it.name == "profiles" }
            profileEntity.attributeByName("user_id") shouldNotBe null
        }

        test("FK column type matches the target's primary key type") {
            val diagram =
                classDiagram("Orders") {
                    val customer = classOf("Customer") { attribute("id", "Long") }
                    val order = classOf("Order") { attribute("id", "UUID") }
                    association(source = customer, target = order) {
                        source { multiplicity("1") }
                        target { multiplicity("0..*") }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val orderEntity = result.output.entities.first { it.name == "orders" }
            val customerEntity = result.output.entities.first { it.name == "customers" }
            orderEntity.attributeByName("customer_id")!!.type shouldBe customerEntity.primaryKey.first().type
        }

        test("«FK».onDelete/onUpdate overrides are applied to the referential action") {
            val diagram =
                classDiagram("Orders") {
                    val customer = classOf("Customer") { attribute("id", "UUID") }
                    val order = classOf("Order") { attribute("id", "UUID") }
                    association(source = customer, target = order, id = "assoc-cust-order") {
                        source { multiplicity("1") }
                        target { multiplicity("0..*") }
                    }
                }.withAssociationStereotype(
                    null,
                    KumlStereotypeApplication(
                        profileNamespace = ErmProfileNames.NAMESPACE,
                        stereotypeName = ErmProfileNames.FK,
                        tags =
                            mapOf(
                                ErmProfileNames.TAG_ON_DELETE to "CASCADE",
                                ErmProfileNames.TAG_ON_UPDATE to "SET_NULL",
                            ).mapValues { it.value.toTagValue() },
                    ),
                )
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val orderEntity = result.output.entities.first { it.name == "orders" }
            val fk = orderEntity.attributeByName("customer_id")!!.foreignKey!!
            fk.onDelete shouldBe ReferentialAction.CASCADE
            fk.onUpdate shouldBe ReferentialAction.SET_NULL
        }

        test("two FK associations from the same class to the same target disambiguate via role names") {
            // Order.createdBy -> User and Order.assignedTo -> User both default to "user_id" —
            // the second association must be renamed using its role to avoid an attribute-name
            // collision (see UmlToErmTransformer.addForeignKey's Known limitations).
            val diagram =
                classDiagram("Orders") {
                    val user = classOf("User") { attribute("id", "UUID") }
                    val order = classOf("Order") { attribute("id", "UUID") }
                    association(source = order, target = user, id = "assoc-created-by") {
                        source { multiplicity("0..*") }
                        target {
                            role = "createdBy"
                            multiplicity("1")
                        }
                    }
                    association(source = order, target = user, id = "assoc-assigned-to") {
                        source { multiplicity("0..*") }
                        target {
                            role = "assignedTo"
                            multiplicity("1")
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val orderEntity = result.output.entities.first { it.name == "orders" }
            val userEntity = result.output.entities.first { it.name == "users" }

            val createdByCol = orderEntity.attributeByName("user_id")
            val assignedToCol = orderEntity.attributeByName("assigned_to_id")
            createdByCol shouldNotBe null
            assignedToCol shouldNotBe null
            createdByCol!!.foreignKey!!.targetEntityId shouldBe userEntity.id
            assignedToCol!!.foreignKey!!.targetEntityId shouldBe userEntity.id

            orderEntity.attributes.map { it.name } shouldBe listOf("id", "user_id", "assigned_to_id")
        }

        test("self-referential 1:N association is skipped (no FK created, no failure)") {
            val diagram =
                classDiagram("Trees") {
                    val node = classOf("Node") { attribute("id", "UUID") }
                    association(source = node, target = node) {
                        source { multiplicity("0..1") }
                        target { multiplicity("0..*") }
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            result.shouldBeInstanceOf<TransformResult.Success<*>>()
            val entity = (result as TransformResult.Success).output.entities.single()
            entity.attributes.map { it.name } shouldBe listOf("id")
        }
    })
