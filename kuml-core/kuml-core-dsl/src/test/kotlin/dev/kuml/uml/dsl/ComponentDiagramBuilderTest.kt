package dev.kuml.uml.dsl

import dev.kuml.core.dsl.componentDiagram
import dev.kuml.core.model.ComponentDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlInterfaceRealization
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ComponentDiagramBuilderTest :
    FunSpec(body = {

        test(name = "empty component diagram builds without error") {
            val d = componentDiagram("Empty") {}
            d.name shouldBe "Empty"
            d.type shouldBe DiagramType.COMPONENT
            d.elements.shouldHaveSize(0)
        }

        test(name = "component is added with deterministic id") {
            val d = componentDiagram("Arch") { component("OrderService") }
            d.elements
                .filterIsInstance<UmlComponent>()
                .single()
                .id shouldBe "OrderService"
        }

        test(name = "port is created with qualified id under its component") {
            val d =
                componentDiagram("Arch") {
                    component("OrderService") { port("api") }
                }
            d.elements
                .filterIsInstance<UmlComponent>()
                .single()
                .ports
                .single()
                .id shouldBe "OrderService::api"
        }

        test(name = "provides stores interface id on component") {
            val d =
                componentDiagram("Arch") {
                    val orderApi = interfaceOf("IOrderApi")
                    component("OrderService") { provides(orderApi) }
                }
            val comp = d.elements.filterIsInstance<UmlComponent>().single()
            comp.providedInterfaceIds shouldBe listOf("IOrderApi")
        }

        test(name = "requires stores interface id on component") {
            val d =
                componentDiagram("Arch") {
                    val eventBus = interfaceOf("IEventBus")
                    component("InvoiceService") { requires(eventBus) }
                }
            val comp = d.elements.filterIsInstance<UmlComponent>().single()
            comp.requiredInterfaceIds shouldBe listOf("IEventBus")
        }

        test(name = "nested component is stored inside parent component") {
            val d =
                componentDiagram("Arch") {
                    component("OrderService") { component("OrderRepository") }
                }
            val parent = d.elements.filterIsInstance<UmlComponent>().single()
            parent.nestedComponents.single().id shouldBe "OrderService::OrderRepository"
        }

        test(name = "connect by component+port-name creates UmlConnector with full port ids") {
            val d =
                componentDiagram("Arch") {
                    val a = component("A") { port("out") }
                    val b = component("B") { port("in") }
                    connect(end1 = a, port1 = "out", end2 = b, port2 = "in")
                }
            val c = d.elements.filterIsInstance<UmlConnector>().single()
            c.end1Id shouldBe "A::out"
            c.end2Id shouldBe "B::in"
        }

        test(name = "diagram type is COMPONENT") {
            componentDiagram("X") {}.type shouldBe DiagramType.COMPONENT
        }

        test(name = "adding UmlClass to component diagram throws") {
            val builder = ComponentDiagramBuilder("Bad")
            shouldThrow<IllegalArgumentException> {
                builder.addNamedElement(UmlClass(id = "X", name = "X"))
            }
        }

        test(name = "adding UmlAssociation to component diagram throws") {
            val builder = ComponentDiagramBuilder("Bad")
            val assoc =
                UmlAssociation(
                    id = "assoc::A-->B",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "A"),
                            UmlAssociationEnd(typeId = "B"),
                        ),
                )
            shouldThrow<IllegalArgumentException> { builder.addRelationship(assoc) }
        }

        test(name = "config is ComponentDiagramConfig with defaults") {
            val d = componentDiagram("Config Test") {}
            val config = d.config
            config.shouldBeInstanceOf<ComponentDiagramConfig>()
            config.showPortLabels shouldBe true
            config.showInterfaceContracts shouldBe true
            config.showNestedComponents shouldBe true
            config.showStereotype shouldBe true
        }

        // V2.0.47 — Auto-Synthese der Realization-/Dependency-Kanten aus
        // `provides`/`requires` (siehe Vault-Beispiel
        // [[03 Bereiche/kUML/Beispiele/12 UML Component – Order Architecture]]).
        test(name = "provides synthesizes a UmlInterfaceRealization when the interface is a diagram node") {
            val d =
                componentDiagram("Order Architecture") {
                    val orderApi = interfaceOf("IOrderApi")
                    component("OrderService") { provides(orderApi) }
                }
            val realization = d.elements.filterIsInstance<UmlInterfaceRealization>().single()
            realization.implementingId shouldBe "OrderService"
            realization.interfaceId shouldBe "IOrderApi"
            realization.id shouldBe "OrderService-provides-IOrderApi"
        }

        test(name = "requires synthesizes a UmlDependency with use stereotype when interface is a node") {
            val d =
                componentDiagram("Order Architecture") {
                    val orderApi = interfaceOf("IOrderApi")
                    component("InvoiceService") { requires(orderApi) }
                }
            val dependency = d.elements.filterIsInstance<UmlDependency>().single()
            dependency.clientId shouldBe "InvoiceService"
            dependency.supplierId shouldBe "IOrderApi"
            dependency.name shouldBe "use"
            dependency.id shouldBe "InvoiceService-requires-IOrderApi"
        }

        test(name = "no synthesis when interface is not declared as a diagram node") {
            // Komponente verweist auf eine externe Interface-ID, die nicht im
            // Diagramm als Knoten erscheint → kein synthetisches Edge, sonst
            // hätten wir freischwebende Beziehungen ohne sichtbares Ziel.
            val d =
                componentDiagram("External Reference") {
                    component("OrderService") { providesById("com.example.IOrderApi") }
                }
            d.elements.filterIsInstance<UmlInterfaceRealization>().shouldHaveSize(0)
        }
    })
