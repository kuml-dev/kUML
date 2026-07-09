package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.erm.constraint.ErmConstraintChecker
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.erm.ErmProfileNames
import dev.kuml.profile.toTagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun KumlDiagram.withOnlyAssociationStereotype(stereotype: KumlStereotypeApplication): KumlDiagram =
    copy(
        elements =
            elements.map { element ->
                if (element is UmlAssociation) element.copy(appliedStereotypes = listOf(stereotype)) else element
            },
    )

class UmlToErmManyToManyTest :
    FunSpec({

        val transformer = UmlToErmTransformer()

        fun studentCourseDiagram() =
            classDiagram("Enrollment") {
                val student = classOf("Student") { attribute("id", "UUID") }
                val course = classOf("Course") { attribute("id", "UUID") }
                association(source = student, target = course) {
                    source { multiplicity("0..*") }
                    target { multiplicity("0..*") }
                }
            }

        test("many-to-many association resolves to a junction entity with a composite PK") {
            val result = transformer.transform(studentCourseDiagram(), TransformContext()) as TransformResult.Success
            val model = result.output

            model.entities shouldHaveSize 3
            val junction = model.entities.first { it.name == "students_courses" }
            junction.weak shouldBe true
            junction.primaryKey shouldHaveSize 2
            junction.attributeByName("student_id") shouldNotBe null
            junction.attributeByName("course_id") shouldNotBe null
            junction.attributeByName("student_id")!!.foreignKey shouldNotBe null
            junction.attributeByName("course_id")!!.foreignKey shouldNotBe null
        }

        test("many-to-many resolution creates two IDENTIFYING relationships, no plain M:N relationship") {
            val result = transformer.transform(studentCourseDiagram(), TransformContext()) as TransformResult.Success
            val rels = result.output.relationships
            rels shouldHaveSize 2
            rels.forEach { it.kind shouldBe RelationshipKind.IDENTIFYING }
        }

        test("«JunctionTable».tableName override is honoured") {
            val diagram =
                studentCourseDiagram().withOnlyAssociationStereotype(
                    KumlStereotypeApplication(
                        profileNamespace = ErmProfileNames.NAMESPACE,
                        stereotypeName = ErmProfileNames.JUNCTION_TABLE,
                        tags = mapOf(ErmProfileNames.TAG_TABLE_NAME to "enrollments").mapValues { it.value.toTagValue() },
                    ),
                )
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            result.output.entities.map { it.name } shouldBe listOf("students", "courses", "enrollments")
        }

        test("«JunctionTable».sourceColumn/targetColumn overrides are honoured") {
            val diagram =
                studentCourseDiagram().withOnlyAssociationStereotype(
                    KumlStereotypeApplication(
                        profileNamespace = ErmProfileNames.NAMESPACE,
                        stereotypeName = ErmProfileNames.JUNCTION_TABLE,
                        tags =
                            mapOf(
                                ErmProfileNames.TAG_SOURCE_COLUMN to "learner_id",
                                ErmProfileNames.TAG_TARGET_COLUMN to "class_id",
                            ).mapValues { it.value.toTagValue() },
                    ),
                )
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val junction = result.output.entities.first { it.name == "students_courses" }
            junction.attributeByName("learner_id") shouldNotBe null
            junction.attributeByName("class_id") shouldNotBe null
        }

        test("resolved M:N model produces zero ErmConstraintChecker violations (ERROR or WARNING)") {
            val result = transformer.transform(studentCourseDiagram(), TransformContext()) as TransformResult.Success
            ErmConstraintChecker().check(result.output) shouldBe emptyList()
        }
    })
