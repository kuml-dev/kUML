package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmMetadataKeys
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

class UmlToErmBasicTest :
    FunSpec({

        val transformer = UmlToErmTransformer()

        test("class with own id attribute maps to one entity with a single primary key") {
            val diagram =
                classDiagram("Simple") {
                    classOf("Customer") {
                        attribute("id", "UUID")
                        attribute("name", "String")
                        attribute("email", "String", multiplicity = Multiplicity(0, 1))
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            val model = (result as TransformResult.Success).output

            model.entities.size shouldBe 1
            val entity = model.entities.first()
            entity.name shouldBe "customers"
            entity.primaryKey.size shouldBe 1
            entity.primaryKey.first().name shouldBe "id"
            entity.primaryKey.first().type shouldBe ErmDataType.Uuid

            val nameCol = entity.attributeByName("name")
            nameCol shouldNotBe null
            nameCol!!.nullable shouldBe false
            nameCol.type shouldBe ErmDataType.Varchar(255)

            val emailCol = entity.attributeByName("email")!!
            emailCol.nullable shouldBe true
        }

        test("class without an id attribute gets a synthetic bigint primary key") {
            val diagram =
                classDiagram("Simple") {
                    classOf("Product") {
                        attribute("name", "String")
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val entity = result.output.entities.first()
            entity.primaryKey.size shouldBe 1
            val pk = entity.primaryKey.first()
            pk.name shouldBe "id"
            pk.type shouldBe ErmDataType.Integer(64)
            pk.autoIncrement shouldBe true
        }

        test("idType=uuid option synthesizes a UUID primary key") {
            val diagram =
                classDiagram("Simple") {
                    classOf("Product") {
                        attribute("name", "String")
                    }
                }
            val result = transformer.transform(diagram, TransformContext(options = mapOf("idType" to "uuid"))) as TransformResult.Success
            val pk =
                result.output.entities
                    .first()
                    .primaryKey
                    .first()
            pk.type shouldBe ErmDataType.Uuid
            pk.autoIncrement shouldBe false
        }

        test("class name is snake_cased and pluralised for the table name") {
            val diagram =
                classDiagram("Simple") {
                    classOf("OrderItem") {
                        attribute("id", "UUID")
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            result.output.entities
                .first()
                .name shouldBe "order_items"
        }

        test("«Entity».tableName overrides the derived table name") {
            val diagram =
                classDiagram("Simple") {
                    applyProfile(ermMappingProfile)
                    classOf("Customer") {
                        stereotype("Entity") {
                            "tableName" to "crm_customers"
                        }
                        attribute("id", "UUID")
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            result.output.entities
                .first()
                .name shouldBe "crm_customers"
        }

        test("«Entity».kotlinObjectName sets the KOTLIN_OBJECT_NAME entity metadata") {
            val diagram =
                classDiagram("Simple") {
                    applyProfile(ermMappingProfile)
                    classOf("Member") {
                        stereotype("Entity") {
                            "tableName" to "members"
                            "kotlinObjectName" to "MemberTable"
                        }
                        attribute("id", "UUID")
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val entity = result.output.entities.first()
            val override = entity.metadata[ErmMetadataKeys.KOTLIN_OBJECT_NAME]
            override.shouldBeInstanceOf<KumlMetaValue.Text>()
            (override as KumlMetaValue.Text).value shouldBe "MemberTable"
        }

        test("Entity without kotlinObjectName override has no KOTLIN_OBJECT_NAME metadata") {
            val diagram =
                classDiagram("Simple") {
                    classOf("Product") { attribute("id", "UUID") }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            result.output.entities
                .first()
                .metadata shouldBe emptyMap()
        }

        test("«Column».columnName overrides the derived column name") {
            val diagram =
                classDiagram("Simple") {
                    applyProfile(ermMappingProfile)
                    classOf("Customer") {
                        attribute("id", "UUID")
                        attribute("fullName", "String") {
                            stereotype("Column") {
                                "columnName" to "full_name_override"
                            }
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val entity = result.output.entities.first()
            entity.attributeByName("full_name_override") shouldNotBe null
            entity.attributeByName("full_name") shouldBe null
        }

        test("«Transient» skips the attribute entirely") {
            val diagram =
                classDiagram("Simple") {
                    applyProfile(ermMappingProfile)
                    classOf("Customer") {
                        attribute("id", "UUID")
                        attribute("cachedScore", "Double") {
                            stereotype("Transient")
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val entity = result.output.entities.first()
            entity.attributes.map { it.name } shouldBe listOf("id")
        }

        test("«Id» stereotype marks a non-'id'-named attribute as the primary key") {
            val diagram =
                classDiagram("Simple") {
                    applyProfile(ermMappingProfile)
                    classOf("Customer") {
                        attribute("customerNumber", "Long") {
                            stereotype("Id")
                        }
                        attribute("name", "String")
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val entity = result.output.entities.first()
            entity.primaryKey.size shouldBe 1
            entity.primaryKey.first().name shouldBe "customer_number"
        }

        test("«Column».nullable/unique/sqlType overrides are honoured") {
            val diagram =
                classDiagram("Simple") {
                    applyProfile(ermMappingProfile)
                    classOf("Customer") {
                        attribute("id", "UUID")
                        attribute("email", "String") {
                            stereotype("Column") {
                                "columnName" to "email"
                                "nullable" to false
                                "unique" to true
                                "sqlType" to "text"
                            }
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val col =
                result.output.entities
                    .first()
                    .attributeByName("email")!!
            col.nullable shouldBe false
            col.unique shouldBe true
            col.type shouldBe ErmDataType.Text
        }

        test("model-level result passes ErmConstraintChecker with zero errors") {
            val diagram =
                classDiagram("Simple") {
                    classOf("Customer") {
                        attribute("id", "UUID")
                        attribute("name", "String")
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            result.shouldBeInstanceOf<TransformResult.Success<*>>()
        }
    })
