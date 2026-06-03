package dev.kuml.uml.dsl

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.dsl.objectDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.ObjectDiagramConfig
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlInstanceSpecification
import dev.kuml.uml.UmlInstanceValue
import dev.kuml.uml.UmlLink
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ObjectDiagramBuilderTest :
    FunSpec(body = {

        // A tiny class diagram used as the classifier source for the tests.
        // We build it once and reach into its `elements` for the UmlClass refs.
        fun buildClasses(): Pair<UmlClass, UmlClass> {
            val classes =
                classDiagram(name = "fixture") {
                    classOf(name = "Customer") {
                        attribute(name = "id", type = "UUID")
                        attribute(name = "name", type = "String")
                    }
                    classOf(name = "Order") {
                        attribute(name = "id", type = "UUID")
                        attribute(name = "amount", type = "BigDecimal")
                    }
                }
            val customer = classes.elements.first { it is UmlClass && it.name == "Customer" } as UmlClass
            val order = classes.elements.first { it is UmlClass && it.name == "Order" } as UmlClass
            return customer to order
        }

        test("objectDiagram builds a diagram of type OBJECT with default config") {
            val (customer, _) = buildClasses()
            val diagram =
                objectDiagram(name = "Empty snapshot") {
                    instanceOf(classifier = customer, name = "alice")
                }
            diagram.type shouldBe DiagramType.OBJECT
            diagram.name shouldBe "Empty snapshot"
            diagram.config.shouldBeInstanceOf<ObjectDiagramConfig>()
        }

        test("instanceOf records classifier id, name, and slots") {
            val (customer, _) = buildClasses()
            val diagram =
                objectDiagram(name = "snap") {
                    instanceOf(classifier = customer, name = "alice") {
                        slot(feature = "id", value = literal("c0ffee"))
                        slot(feature = "name", value = literal("\"Alice\""))
                    }
                }
            val alice = diagram.elements.filterIsInstance<UmlInstanceSpecification>().single()
            alice.name shouldBe "alice"
            alice.classifierId shouldBe customer.id
            alice.slots shouldHaveSize 2
            alice.slots[0].featureName shouldBe "id"
            val v0 = alice.slots[0].value
            v0.shouldBeInstanceOf<UmlInstanceValue.Literal>()
            v0.text shouldBe "c0ffee"
        }

        test("slot defining-feature id is resolved against the classifier") {
            val (customer, _) = buildClasses()
            val diagram =
                objectDiagram(name = "snap") {
                    instanceOf(classifier = customer, name = "alice") {
                        slot(feature = "name", value = literal("Alice"))
                    }
                }
            val alice = diagram.elements.filterIsInstance<UmlInstanceSpecification>().single()
            val nameAttr = customer.attributes.first { it.name == "name" }
            alice.slots[0].definingFeatureId shouldBe nameAttr.id
        }

        test("unknown slot feature name falls back to empty defining-feature id") {
            val (customer, _) = buildClasses()
            val diagram =
                objectDiagram(name = "snap") {
                    instanceOf(classifier = customer) {
                        slot(feature = "ghostField", value = literal("?"))
                    }
                }
            val instance = diagram.elements.filterIsInstance<UmlInstanceSpecification>().single()
            instance.slots[0].definingFeatureId shouldBe ""
            instance.slots[0].featureName shouldBe "ghostField"
        }

        test("link captures source, target and roles") {
            val (customer, order) = buildClasses()
            val diagram =
                objectDiagram(name = "snap") {
                    val alice = instanceOf(classifier = customer, name = "alice")
                    val ord = instanceOf(classifier = order, name = "order42")
                    link(from = alice, to = ord, sourceRole = "buyer", targetRole = "purchase")
                }
            val link = diagram.elements.filterIsInstance<UmlLink>().single()
            val instances = diagram.elements.filterIsInstance<UmlInstanceSpecification>()
            link.sourceInstanceId shouldBe instances[0].id
            link.targetInstanceId shouldBe instances[1].id
            link.sourceRoleName shouldBe "buyer"
            link.targetRoleName shouldBe "purchase"
        }

        test("ref helper produces an instance-reference slot value") {
            val (customer, order) = buildClasses()
            val diagram =
                objectDiagram(name = "snap") {
                    val alice = instanceOf(classifier = customer, name = "alice")
                    instanceOf(classifier = order, name = "order42") {
                        slot(feature = "customer", value = ref(alice))
                    }
                }
            val ord =
                diagram.elements
                    .filterIsInstance<UmlInstanceSpecification>()
                    .first { it.name == "order42" }
            val refValue = ord.slots[0].value
            refValue.shouldBeInstanceOf<UmlInstanceValue.InstanceRef>()
            refValue.instanceId shouldBe
                diagram.elements
                    .filterIsInstance<UmlInstanceSpecification>()
                    .first { it.name == "alice" }
                    .id
        }

        test("rejects UmlClass at the object-diagram top level") {
            val (customer, _) = buildClasses()
            shouldThrow<IllegalArgumentException> {
                objectDiagram(name = "snap") {
                    addNamedElement(customer)
                }
            }
        }

        test("anonymous instances get a generated id and an empty name") {
            val (customer, _) = buildClasses()
            val diagram =
                objectDiagram(name = "snap") {
                    instanceOf(classifier = customer)
                    instanceOf(classifier = customer)
                }
            val instances = diagram.elements.filterIsInstance<UmlInstanceSpecification>()
            instances shouldHaveSize 2
            instances[0].name shouldBe ""
            instances[1].name shouldBe ""
            (instances[0].id == instances[1].id) shouldBe false
        }

        test("display options propagate to ObjectDiagramConfig") {
            val (customer, _) = buildClasses()
            val diagram =
                objectDiagram(name = "snap") {
                    showClassifierType = false
                    showSlotCompartment = true
                    showNullSlots = false
                    instanceOf(classifier = customer, name = "x")
                }
            val cfg = diagram.config as ObjectDiagramConfig
            cfg.showClassifierType shouldBe false
            cfg.showSlotCompartment shouldBe true
            cfg.showNullSlots shouldBe false
        }
    })
