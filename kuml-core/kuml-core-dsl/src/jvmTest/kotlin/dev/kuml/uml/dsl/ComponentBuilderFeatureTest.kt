package dev.kuml.uml.dsl

import dev.kuml.core.dsl.componentDiagram
import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile
import dev.kuml.uml.UmlComponent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * V1.1.3 Ticket 4 — component-level attributes and operations,
 * including stereotype applications on both feature levels.
 */
class ComponentBuilderFeatureTest :
    FunSpec(body = {

        val componentProfile: KumlProfile =
            profile(name = "ComponentFeatures") {
                namespace = "dev.kuml.test.profiles.componentfeatures"
                stereotype(name = "Runnable") {
                    extends(UmlMetaclass.Operation)
                    property<Int>(name = "period_ms") { default = 100 }
                }
                stereotype(name = "InPort") { extends(UmlMetaclass.Parameter) }
                stereotype(name = "Persistent") { extends(UmlMetaclass.Property) }
            }

        test(name = "component without features compiles and builds empty") {
            val d = componentDiagram(name = "Arch") { component(name = "X") }
            val cmp = d.elements.filterIsInstance<UmlComponent>().single()
            cmp.attributes.shouldBeEmpty()
            cmp.operations.shouldBeEmpty()
        }

        test(name = "component with single attribute") {
            val d =
                componentDiagram(name = "Arch") {
                    component(name = "X") {
                        attribute(name = "a", type = "Int")
                    }
                }
            val cmp = d.elements.filterIsInstance<UmlComponent>().single()
            cmp.attributes shouldHaveSize 1
            cmp.attributes.first().name shouldBe "a"
            cmp.attributes
                .first()
                .type.name shouldBe "Int"
        }

        test(name = "component with single operation") {
            val d =
                componentDiagram(name = "Arch") {
                    component(name = "X") {
                        operation(name = "doIt")
                    }
                }
            val cmp = d.elements.filterIsInstance<UmlComponent>().single()
            cmp.operations shouldHaveSize 1
            cmp.operations.first().name shouldBe "doIt"
        }

        test(name = "component with multiple features in mixed order preserves entry order") {
            val d =
                componentDiagram(name = "Arch") {
                    component(name = "X") {
                        attribute(name = "a1", type = "Int")
                        operation(name = "op1")
                        attribute(name = "a2", type = "String")
                    }
                }
            val cmp = d.elements.filterIsInstance<UmlComponent>().single()
            cmp.attributes shouldHaveSize 2
            cmp.attributes.map { it.name } shouldBe listOf("a1", "a2")
            cmp.operations shouldHaveSize 1
            cmp.operations.first().name shouldBe "op1"
        }

        test(name = "component attribute with stereotype") {
            val d =
                componentDiagram(name = "Arch") {
                    applyProfile(componentProfile)
                    component(name = "X") {
                        attribute(name = "data", type = "Double") {
                            stereotype(name = "Persistent")
                        }
                    }
                }
            val cmp = d.elements.filterIsInstance<UmlComponent>().single()
            cmp.attributes shouldHaveSize 1
            cmp.attributes.first().appliedStereotypes shouldHaveSize 1
            cmp.attributes
                .first()
                .appliedStereotypes
                .first()
                .stereotypeName shouldBe "Persistent"
        }

        test(name = "component operation with stereotype and parameter stereotype") {
            val d =
                componentDiagram(name = "Arch") {
                    applyProfile(componentProfile)
                    component(name = "SteeringControlSWC") {
                        operation(name = "computeSteeringAngle") {
                            stereotype(name = "Runnable") { "period_ms" to 10 }
                            parameter(name = "rawInput", type = "Double") { stereotype(name = "InPort") }
                        }
                    }
                }
            val cmp = d.elements.filterIsInstance<UmlComponent>().single()
            cmp.operations shouldHaveSize 1
            val op = cmp.operations.first()
            op.appliedStereotypes shouldHaveSize 1
            op.appliedStereotypes.first().stereotypeName shouldBe "Runnable"
            op.parameters shouldHaveSize 1
            op.parameters.first().appliedStereotypes shouldHaveSize 1
            op.parameters
                .first()
                .appliedStereotypes
                .first()
                .stereotypeName shouldBe "InPort"
        }

        test(name = "existing component without features remains unchanged (backward-compat)") {
            // No attributes/operations declared → both lists empty.
            val d =
                componentDiagram(name = "Arch") {
                    component(name = "X") {
                        port(name = "api")
                    }
                }
            val cmp = d.elements.filterIsInstance<UmlComponent>().single()
            cmp.ports shouldHaveSize 1
            cmp.attributes.shouldBeEmpty()
            cmp.operations.shouldBeEmpty()
        }
    })
