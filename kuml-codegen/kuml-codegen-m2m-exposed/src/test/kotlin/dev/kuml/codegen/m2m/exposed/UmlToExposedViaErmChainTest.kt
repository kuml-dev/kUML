package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * V3.4.8 — end-to-end coverage for [UmlToExposedViaErmScriptTransformer], the
 * CLI-runnable `uml-to-exposed-via-erm` chain (`uml-to-erm` + `erm-to-exposed`).
 *
 * Focuses on the conceptual win over the older `uml-to-exposed` (Variante B):
 * a many-to-many UML association is chained through `UmlToErmTransformer`'s
 * junction-entity materialization first, so it arrives at [ErmExposedEmitter]
 * as a genuine junction [dev.kuml.erm.model.ErmEntity] with a composite
 * primary key — and is therefore emitted as a real Exposed `Table` object with
 * `reference()` columns, not the `// *-to-many not represented` comment that
 * [UmlToExposedTransformer] leaves.
 */
class UmlToExposedViaErmChainTest :
    FunSpec({

        val transformer = UmlToExposedViaErmScriptTransformer()

        test("id and description") {
            transformer.id shouldBe "uml-to-exposed-via-erm"
            transformer.description shouldContain "UML"
        }

        test("simple UML class with its own id attribute chains through to a Table object") {
            val diagram =
                classDiagram("Simple") {
                    classOf("Customer") {
                        attribute("id", "UUID")
                        attribute("name", "String")
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output

            files shouldHaveSize 1
            val content = files[0].content
            files[0].relativePath shouldBe "Customers.kt"
            content shouldContain "public object Customers : Table(\"customers\")"
            content shouldContain "override val primaryKey: PrimaryKey = PrimaryKey(id)"
        }

        test("«Entity».kotlinObjectName overrides the generated Exposed object name end-to-end") {
            val diagram =
                classDiagram("Simple") {
                    applyProfile(ermMappingProfile)
                    classOf("Member") {
                        stereotype("Entity") {
                            "tableName" to "members"
                            "kotlinObjectName" to "MemberTable"
                        }
                        attribute("id", "UUID")
                        attribute("name", "String")
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output

            files shouldHaveSize 1
            files[0].relativePath shouldBe "MemberTable.kt"
            files[0].content shouldContain "public object MemberTable : Table(\"members\")"
        }

        test("«Entity».kotlinObjectName that is not a valid Kotlin identifier fails the chain end-to-end") {
            // Confirms that ErmExposedEmitter's origin-agnostic identifier validation
            // (already exercised for the ERM-DSL origin in ErmToExposedTransformerTest)
            // also covers a UML-profile-supplied override, not just the DSL one.
            val diagram =
                classDiagram("Simple") {
                    applyProfile(ermMappingProfile)
                    classOf("Member") {
                        stereotype("Entity") {
                            "tableName" to "members"
                            "kotlinObjectName" to "123Invalid"
                        }
                        attribute("id", "UUID")
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            result.shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("many-to-many association becomes a real junction Table object with composite PK") {
            val diagram =
                classDiagram("Enrollment") {
                    val student = classOf("Student") { attribute("id", "UUID") }
                    val course = classOf("Course") { attribute("id", "UUID") }
                    association(source = student, target = course) {
                        source { multiplicity("0..*") }
                        target { multiplicity("0..*") }
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output

            files shouldHaveSize 3
            val junction = files.first { it.relativePath == "StudentsCourses.kt" }
            junction.content shouldContain "public object StudentsCourses : Table(\"students_courses\")"
            junction.content shouldContain "override val primaryKey: PrimaryKey = PrimaryKey(studentId, courseId)"
            // UmlToErmTransformer's junction-entity materialization sets onDelete = CASCADE on
            // both junction FKs by default (deleting either parent row cascades to the link row).
            junction.content shouldContain "reference(\"student_id\", Students.id, onDelete = ReferenceOption.CASCADE)"
            junction.content shouldContain "reference(\"course_id\", Courses.id, onDelete = ReferenceOption.CASCADE)"
        }

        test("--package option is threaded through both chain steps") {
            val diagram =
                classDiagram("Simple") {
                    classOf("Customer") { attribute("id", "UUID") }
                }
            val result = transformer.transform(diagram, TransformContext(mapOf("package" to "org.myapp.tables")))
            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output

            files.single().content shouldContain "package org.myapp.tables"
        }

        test("trace links merge across both chain steps (UML element -> ERM entity -> generated file)") {
            val diagram =
                classDiagram("Simple") {
                    classOf("Customer") { attribute("id", "UUID") }
                }
            val result = transformer.transform(diagram, TransformContext())
            val success = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>()

            // uml-to-erm step: UmlClass -> ErmEntity
            success.trace.links
                .map { it.ruleId }
                .shouldContain("uml-class-to-erm-entity")
            // erm-to-exposed step: ErmEntity -> generated file
            success.trace.links
                .map { it.ruleId }
                .shouldContain(ErmExposedEmitter.RULE_ENTITY_TO_TABLE)
        }
    })
