package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ErmScriptRendererTest :
    FunSpec({

        val transformer = UmlToErmTransformer()

        fun renderedOf(): String {
            val diagram =
                classDiagram("Orders") {
                    val customer = classOf("Customer") { attribute("id", "UUID") }
                    val order = classOf("Order") { attribute("id", "UUID") }
                    association(source = customer, target = order) {
                        source { multiplicity("1") }
                        target { multiplicity("0..*") }
                    }
                }
            val model = (transformer.transform(diagram, TransformContext()) as TransformResult.Success).output
            return ErmScriptRenderer.render(model)
        }

        test("rendered script opens with ermModel(...) and declares an entity() per table") {
            val script = renderedOf()
            script shouldContain "ermModel(name = \"Orders\")"
            script shouldContain "entity(\"customers\")"
            script shouldContain "entity(\"orders\")"
        }

        test("rendered script declares a relationship() call") {
            val script = renderedOf()
            script shouldContain "relationship(from ="
        }

        test("rendered script is idempotent for the same input model") {
            val diagram =
                classDiagram("Orders") {
                    classOf("Customer") { attribute("id", "UUID") }
                    classOf("Order") { attribute("id", "UUID") }
                }
            val model = (transformer.transform(diagram, TransformContext()) as TransformResult.Success).output
            ErmScriptRenderer.render(model) shouldBe ErmScriptRenderer.render(model)
        }

        test("the customer entity (referenced by the order's FK) is declared before the order entity") {
            val script = renderedOf()
            val customerIndex = script.indexOf("entity(\"customers\")")
            val orderIndex = script.indexOf("entity(\"orders\")")
            (customerIndex in 0 until orderIndex) shouldBe true
        }
    })
