package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tests for `«Column».fkEntity`/`fkAttribute` — a column-level FK override that bypasses
 * [UmlToErmTransformer]'s association-to-FK derivation entirely (see that class's KDoc,
 * "Known limitations"). Closes the real-world gap found during the Lapis Cloud MDA retrofit:
 * a real column like "created_by" referencing "member.id" cannot be produced by association
 * derivation (which would default to "member_id"), and there is no role-based way to
 * override that name without a naming collision.
 */
class UmlToErmColumnFkTest :
    FunSpec({

        val transformer = UmlToErmTransformer()

        test("«Column».fkEntity pins an FK to the target's primary key when fkAttribute is absent") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("Member") {
                        attribute("id", "UUID")
                    }
                    classOf("Document") {
                        attribute("id", "UUID")
                        attribute("createdBy", "UUID") {
                            stereotype("Column") {
                                "columnName" to "created_by"
                                "fkEntity" to "Member"
                            }
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val documentEntity = result.output.entities.first { it.name == "documents" }
            val memberEntity = result.output.entities.first { it.name == "members" }
            val createdBy = documentEntity.attributeByName("created_by")
            createdBy shouldNotBe null
            createdBy!!.foreignKey shouldNotBe null
            createdBy.foreignKey!!.targetEntityId shouldBe memberEntity.id
            createdBy.foreignKey!!.targetAttributeId shouldBe null
        }

        test("«Column».fkAttribute pins an FK to a specific, non-primary-key target column") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("Member") {
                        attribute("id", "UUID")
                        attribute("email", "String") {
                            stereotype("Column") {
                                "columnName" to "email"
                                "unique" to true
                            }
                        }
                    }
                    classOf("Invite") {
                        attribute("id", "UUID")
                        attribute("invitedEmail", "String") {
                            stereotype("Column") {
                                "columnName" to "invited_email"
                                "fkEntity" to "Member"
                                "fkAttribute" to "email"
                            }
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val inviteEntity = result.output.entities.first { it.name == "invites" }
            val memberEntity = result.output.entities.first { it.name == "members" }
            val invitedEmail = inviteEntity.attributeByName("invited_email")
            invitedEmail!!.foreignKey!!.targetEntityId shouldBe memberEntity.id
            invitedEmail.foreignKey!!.targetAttributeId shouldBe memberEntity.attributeByName("email")!!.id
        }

        test("«Column».fkEntity resolves regardless of declaration order (forward reference)") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    // Document is declared BEFORE Member — proves resolution can't be inline.
                    classOf("Document") {
                        attribute("id", "UUID")
                        attribute("createdBy", "UUID") {
                            stereotype("Column") {
                                "columnName" to "created_by"
                                "fkEntity" to "Member"
                            }
                        }
                    }
                    classOf("Member") {
                        attribute("id", "UUID")
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val documentEntity = result.output.entities.first { it.name == "documents" }
            val memberEntity = result.output.entities.first { it.name == "members" }
            documentEntity.attributeByName("created_by")!!.foreignKey!!.targetEntityId shouldBe memberEntity.id
        }

        test("unresolvable «Column».fkEntity fails the transform") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("Document") {
                        attribute("id", "UUID")
                        attribute("createdBy", "UUID") {
                            stereotype("Column") {
                                "columnName" to "created_by"
                                "fkEntity" to "NoSuchClass"
                            }
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            result.shouldBeFailure()
        }

        test("unresolvable «Column».fkAttribute fails the transform") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("Member") {
                        attribute("id", "UUID")
                    }
                    classOf("Document") {
                        attribute("id", "UUID")
                        attribute("createdBy", "UUID") {
                            stereotype("Column") {
                                "columnName" to "created_by"
                                "fkEntity" to "Member"
                                "fkAttribute" to "noSuchColumn"
                            }
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            result.shouldBeFailure()
        }
    })

private fun TransformResult<*>.shouldBeFailure() {
    (this is TransformResult.Failure) shouldBe true
}
