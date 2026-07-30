package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe

class UmlToErmTraceTest :
    FunSpec({

        val transformer = UmlToErmTransformer()

        test("trace contains a link from each class to its entity and each property to its column") {
            val diagram =
                classDiagram(name = "Orders") {
                    val customer =
                        classOf(name = "Customer") {
                            attribute(name = "id", type = "UUID")
                            attribute(name = "name", type = "String")
                        }
                    val order = classOf(name = "Order") { attribute(name = "id", type = "UUID") }
                    association(source = customer, target = order) {
                        source { multiplicity("1") }
                        target { multiplicity("0..*") }
                    }
                }
            val result = transformer.transform(source = diagram, ctx = TransformContext()) as TransformResult.Success

            val customerCls = diagram.elements.filterIsInstance<UmlClass>().first { it.name == "Customer" }
            val customerEntity = result.output.entities.first { it.name == "customers" }
            val classToEntityLink =
                result.trace.links.firstOrNull { it.sourceElementId == customerCls.id && it.targetArtifactId == customerEntity.id }
            classToEntityLink shouldNotBe null

            val nameAttr = customerCls.attributes.first { it.name == "name" }
            val nameColumn = customerEntity.attributeByName("name")!!
            val propertyLink =
                result.trace.links.firstOrNull { it.sourceElementId == nameAttr.id && it.targetArtifactId == nameColumn.id }
            propertyLink shouldNotBe null
        }

        test("trace contains a link from a many-to-many association to its junction entity") {
            val diagram =
                classDiagram(name = "Enrollment") {
                    val student = classOf(name = "Student") { attribute(name = "id", type = "UUID") }
                    val course = classOf(name = "Course") { attribute(name = "id", type = "UUID") }
                    association(source = student, target = course, id = "assoc-enroll") {
                        source { multiplicity("0..*") }
                        target { multiplicity("0..*") }
                    }
                }
            val result = transformer.transform(source = diagram, ctx = TransformContext()) as TransformResult.Success
            val junction = result.output.entities.first { it.name == "students_courses" }
            val link = result.trace.links.firstOrNull { it.sourceElementId == "assoc-enroll" && it.targetArtifactId == junction.id }
            link shouldNotBe null
        }
    })
