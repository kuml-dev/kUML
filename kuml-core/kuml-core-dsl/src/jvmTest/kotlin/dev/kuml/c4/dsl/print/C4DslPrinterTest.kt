package dev.kuml.c4.dsl.print

import dev.kuml.c4.dsl.c4Model
import dev.kuml.c4.model.C4CodeElement
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ComponentDiagram
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.DeploymentDiagram
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.dsl.layout.layout
import dev.kuml.core.model.KumlMetaValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

class C4DslPrinterTest :
    FunSpec(body = {

        test("empty model prints a valid empty c4Model block") {
            val model = c4Model(name = "Empty")
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "c4Model(name = \"Empty\")"
            dsl shouldContain "{"
            dsl shouldContain "}"
        }

        test("person + softwareSystem + relationship without description override") {
            val model =
                c4Model(name = "Test") {
                    val customer = person(name = "Customer") { description = "A customer" }
                    val system = softwareSystem(name = "Banking System") { description = "The system" }
                    relationship(source = customer, target = system) { technology = "HTTPS" }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "person(\"Customer\")"
            dsl shouldContain "description = \"A customer\""
            dsl shouldContain "softwareSystem(\"Banking System\")"
            dsl shouldContain "relationship("
            dsl shouldContain "technology = \"HTTPS\""
            // No explicit description block on the relationship — the label is the default.
            dsl shouldNotContain "description = \"Customer -> Banking System\""
        }

        test("relationship with explicit description is preserved") {
            val model =
                c4Model(name = "Test") {
                    val a = person(name = "A")
                    val b = softwareSystem(name = "B")
                    relationship(source = a, target = b) { description = "Uses via API" }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "description = \"Uses via API\""
        }

        test("nested container referenced by a relationship gets a lateinit var, unreferenced sibling does not") {
            lateinit var webAppRef: C4Container
            val model =
                c4Model(name = "Test") {
                    softwareSystem(name = "System") {
                        webAppRef = container(name = "Web App") { technology = "React" }
                        container(name = "API") { technology = "Spring Boot" }
                    }
                    val db = softwareSystem(name = "Database")
                    relationship(source = webAppRef, target = db) { technology = "JDBC" }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "lateinit var"
            dsl shouldContain ": C4Container"
            dsl shouldContain "container(\"Web App\")"
            dsl shouldContain "container(\"API\")"
            // API is not referenced by any relationship/diagram — no assignment for it.
            dsl shouldNotContain "= container(\"API\")"
        }

        test("deployment node with nested node and containerInstance") {
            lateinit var webAppId: String
            val model =
                c4Model(name = "Test") {
                    val system = softwareSystem(name = "System") { container(name = "Web App") }
                    webAppId = system.containers.first()
                    deploymentNode(name = "AWS") {
                        node(name = "EC2") {
                            containerInstance(name = "Web App Instance", containerId = webAppId)
                        }
                    }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "deploymentNode(\"AWS\")"
            dsl shouldContain "node(\"EC2\")"
            dsl shouldContain "containerInstance(\"Web App Instance\""
        }

        test("systemContextDiagram round trips its included elements") {
            val model =
                c4Model(name = "Test") {
                    val a = person(name = "A")
                    val b = softwareSystem(name = "B")
                    systemContextDiagram(name = "Context") { include(a, b) }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "systemContextDiagram(\"Context\")"
            dsl shouldContain "include("
        }

        test("systemLandscapeDiagram with full defaults emits no extra calls") {
            val model =
                c4Model(name = "Test") {
                    person(name = "A")
                    softwareSystem(name = "B")
                    systemLandscapeDiagram(name = "Landscape")
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "systemLandscapeDiagram(\"Landscape\")"
            dsl shouldNotContain "includeAllSystems = false"
        }

        test("systemLandscapeDiagram with a subset disables the auto-include flags") {
            val model =
                c4Model(name = "Test") {
                    val a = person(name = "A")
                    person(name = "Unused")
                    val b = softwareSystem(name = "B")
                    systemLandscapeDiagram(name = "Landscape") {
                        includeAllSystems = false
                        includeAllPersons = false
                        include(a, b)
                    }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "includeAllSystems = false"
            dsl shouldContain "includeAllPersons = false"
        }

        test("containerDiagram default reconstruction needs no TODO") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Web App")
                            container(name = "API")
                        }
                    containerDiagram(name = "Containers") { this.system = system }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "containerDiagram(\"Containers\")"
            dsl shouldContain "system ="
            dsl shouldNotContain "TODO: ContainerDiagram"
        }

        test("containerDiagram with exclude() reconstructs the excluded container via a lateinit var") {
            lateinit var apiRef: C4Container
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Web App")
                            apiRef = container(name = "API")
                        }
                    containerDiagram(name = "Containers") {
                        this.system = system
                        exclude(apiRef)
                    }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "exclude("
            dsl shouldNotContain "TODO: ContainerDiagram"
        }

        test("componentDiagram default reconstruction needs no TODO") {
            lateinit var webAppRef: C4Container
            val model =
                c4Model(name = "Test") {
                    softwareSystem(name = "System") {
                        webAppRef =
                            container(name = "Web App") {
                                component(name = "Controller")
                                component(name = "Service")
                            }
                    }
                    componentDiagram(name = "Components") { this.container = webAppRef }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "componentDiagram(\"Components\")"
            dsl shouldContain "container ="
            dsl shouldNotContain "TODO: ComponentDiagram"
        }

        test("deploymentDiagram default reconstruction needs no TODO") {
            val model =
                c4Model(name = "Test") {
                    deploymentNode(name = "AWS") { node(name = "EC2") }
                    deploymentDiagram(name = "Deployment")
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "deploymentDiagram(\"Deployment\")"
            dsl shouldNotContain "TODO: DeploymentDiagram"
        }

        test("dynamicDiagram replays interactions and responses in sequence") {
            val model =
                c4Model(name = "Test") {
                    val user = person(name = "User")
                    val web = softwareSystem(name = "WebApp")
                    dynamicDiagram(name = "Checkout") {
                        interaction(description = "Open page", from = user, to = web)
                        response(description = "HTML", from = web, to = user)
                    }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "dynamicDiagram(\"Checkout\")"
            dsl shouldContain "interaction(\"Open page\", from ="
            dsl shouldContain "response(\"HTML\", from ="
        }

        test("layout hints round-trip as a layout block") {
            val model =
                c4Model(name = "Test") {
                    person(name = "Customer") {
                        layout {
                            col = 2
                            row = 1
                            pinned = true
                        }
                    }
                }
            val dsl = C4DslPrinter.print(model)
            dsl shouldContain "import dev.kuml.core.dsl.layout.layout"
            dsl shouldContain "layout {"
            dsl shouldContain "col = 2"
            dsl shouldContain "row = 1"
            dsl shouldContain "pinned = true"
        }

        test("C4CodeElement has no DSL entry point and becomes a TODO comment") {
            val base = c4Model(name = "Test") { person(name = "A") }
            val withCode =
                base.copy(
                    elements =
                        base.elements +
                            C4CodeElement(id = "code-1", name = "OrderService", component = null),
                )
            val dsl = C4DslPrinter.print(withCode)
            dsl shouldContain "TODO: C4CodeElement \"OrderService\""
        }

        test("C4Model.description is not settable via the DSL and becomes a TODO comment") {
            val base = c4Model(name = "Test") { person(name = "A") }
            val withDescription: C4Model = base.copy(description = "Some description")
            val dsl = C4DslPrinter.print(withDescription)
            dsl shouldContain "TODO: C4Model.description"
        }

        test("layout metadata on a deployment node has no DSL entry point and becomes a TODO comment") {
            val base = c4Model(name = "Test") { deploymentNode(name = "AWS") }
            val node = base.elements.filterIsInstance<C4DeploymentNode>().single()
            val withLayout =
                base.copy(
                    elements =
                        base.elements.map { el ->
                            if (el.id == node.id) {
                                node.copy(
                                    metadata =
                                        mapOf(
                                            LayoutMetadataKeys.GRID_COL to KumlMetaValue.Integer(2),
                                            LayoutMetadataKeys.PINNED to KumlMetaValue.Flag(true),
                                        ),
                                )
                            } else {
                                el
                            }
                        },
                )
            val dsl = C4DslPrinter.print(withLayout)
            dsl shouldContain "deploymentNode(\"AWS\")"
            dsl shouldContain "TODO: layout metadata on C4DeploymentNode \"AWS\""
            // No actual layout { … } call is emitted (only mentioned in the TODO prose) — DeploymentNodeScope
            // has no such DSL entry point, so none of the concrete field assignments appear either.
            dsl shouldNotContain "col = 2"
            dsl shouldNotContain "pinned = true"
            // Nor should the `layout { … }` import be emitted — it would be unused dead code since no
            // layout { … } call is ever printed for this model.
            dsl shouldNotContain "import dev.kuml.core.dsl.layout.layout"
        }

        test("layout hints import is still emitted when a printable element AND a deployment node both carry hints") {
            val base =
                c4Model(name = "Test") {
                    person(name = "Customer") {
                        layout {
                            col = 2
                        }
                    }
                    deploymentNode(name = "AWS")
                }
            val node = base.elements.filterIsInstance<C4DeploymentNode>().single()
            val withNodeLayout =
                base.copy(
                    elements =
                        base.elements.map { el ->
                            if (el.id == node.id) {
                                node.copy(metadata = mapOf(LayoutMetadataKeys.PINNED to KumlMetaValue.Flag(true)))
                            } else {
                                el
                            }
                        },
                )
            val dsl = C4DslPrinter.print(withNodeLayout)
            // The import is still needed for the person's printable layout { … } call.
            dsl shouldContain "import dev.kuml.core.dsl.layout.layout"
            dsl shouldContain "col = 2"
            dsl shouldContain "TODO: layout metadata on C4DeploymentNode \"AWS\""
        }

        test("a relationship targeting a container-instance id becomes a TODO comment") {
            val base =
                c4Model(name = "Test") {
                    person(name = "User")
                    deploymentNode(name = "AWS") {
                        containerInstance(name = "Web App Instance", containerId = "")
                    }
                }
            val personId =
                base.elements
                    .filterIsInstance<C4Person>()
                    .single()
                    .id
            val instanceId =
                base.elements
                    .filterIsInstance<C4Container>()
                    .single()
                    .id
            val withRelationship =
                base.copy(
                    relationships =
                        base.relationships +
                            C4Relationship(id = "rel-x", source = personId, target = instanceId, label = "Uses"),
                )
            val dsl = C4DslPrinter.print(withRelationship)
            dsl shouldContain "TODO: C4Relationship"
            dsl shouldContain "container-instance id"
        }

        test("containerDiagram with an unresolvable external system triggers a reconstruction TODO") {
            lateinit var systemRef: C4SoftwareSystem
            val base =
                c4Model(name = "Test") {
                    systemRef = softwareSystem(name = "System") { container(name = "Web App") }
                    softwareSystem(name = "Unrelated External")
                    containerDiagram(name = "Containers") { this.system = systemRef }
                }
            val externalId =
                base.elements
                    .filterIsInstance<C4SoftwareSystem>()
                    .first { it.name == "Unrelated External" }
                    .id
            val diagram = base.diagrams.filterIsInstance<ContainerDiagram>().single()
            val mutatedDiagram = diagram.copy(elements = diagram.elements + externalId)
            val withMutatedDiagram = base.copy(diagrams = base.diagrams.map { if (it.id == diagram.id) mutatedDiagram else it })
            val dsl = C4DslPrinter.print(withMutatedDiagram)
            dsl shouldContain "TODO: ContainerDiagram"
        }

        test("componentDiagram with an unresolvable external container triggers a reconstruction TODO") {
            lateinit var webAppRef: C4Container
            val base =
                c4Model(name = "Test") {
                    softwareSystem(name = "System") {
                        webAppRef = container(name = "Web App") { component(name = "Controller") }
                    }
                    softwareSystem(name = "Other System") { container(name = "Unrelated Container") }
                    componentDiagram(name = "Components") { this.container = webAppRef }
                }
            val externalContainerId =
                base.elements
                    .filterIsInstance<C4Container>()
                    .first { it.name == "Unrelated Container" }
                    .id
            val diagram = base.diagrams.filterIsInstance<ComponentDiagram>().single()
            val mutatedDiagram = diagram.copy(elements = diagram.elements + externalContainerId)
            val withMutatedDiagram = base.copy(diagrams = base.diagrams.map { if (it.id == diagram.id) mutatedDiagram else it })
            val dsl = C4DslPrinter.print(withMutatedDiagram)
            dsl shouldContain "TODO: ComponentDiagram"
        }

        test("deploymentDiagram with an element outside the node hierarchy triggers a reconstruction TODO") {
            val base =
                c4Model(name = "Test") {
                    deploymentNode(name = "AWS") { node(name = "EC2") }
                    person(name = "Stray Person")
                    deploymentDiagram(name = "Deployment")
                }
            val strayId =
                base.elements
                    .filterIsInstance<C4Person>()
                    .single()
                    .id
            val diagram = base.diagrams.filterIsInstance<DeploymentDiagram>().single()
            val mutatedDiagram = diagram.copy(elements = diagram.elements + strayId)
            val withMutatedDiagram = base.copy(diagrams = base.diagrams.map { if (it.id == diagram.id) mutatedDiagram else it })
            val dsl = C4DslPrinter.print(withMutatedDiagram)
            dsl shouldContain "TODO: DeploymentDiagram"
        }

        // ── comment-injection regression tests ─────────────────────────────
        // C4Element.id / C4Relationship endpoints are unvalidated Strings and can contain
        // newlines. TODO/NOTE diagnostic comments interpolate several of these fields
        // *outside* of a quote()d Kotlin string literal — a raw embedded '\n' there would
        // terminate the '//' comment early and let attacker-controlled text re-appear as
        // live, uncommented code on the next line of the generated *.kuml.kts script.

        test("relationship endpoint id with an embedded newline cannot break out of the TODO comment") {
            val base = c4Model(name = "Test") { person(name = "User") }
            val personId =
                base.elements
                    .filterIsInstance<C4Person>()
                    .single()
                    .id
            val maliciousTargetId = "evil-id\nval injected = \"code\""
            val withRelationship =
                base.copy(
                    relationships =
                        base.relationships +
                            C4Relationship(id = "rel-x", source = personId, target = maliciousTargetId, label = "Uses"),
                )
            val dsl = C4DslPrinter.print(withRelationship)
            // The raw newline must not survive verbatim — it would split the injected text
            // onto its own, uncommented line.
            dsl shouldNotContain "evil-id\nval injected"
            // It must instead show up escaped, still on the same '// TODO' line.
            dsl shouldContain "evil-id\\nval injected"
            dsl.lines().filter { "injected" in it }.forEach { line -> line.trimStart() shouldStartWith "//" }
        }

        test("systemContextDiagram missing element ids with embedded newlines cannot break out of the TODO comment") {
            val base =
                c4Model(name = "Test") {
                    val a = person(name = "A")
                    systemContextDiagram(name = "Context") { include(a) }
                }
            val diagram = base.diagrams.filterIsInstance<SystemContextDiagram>().single()
            val maliciousId = "missing-id\nfun injected() = Unit"
            val mutatedDiagram = diagram.copy(elements = diagram.elements + maliciousId)
            val withMutatedDiagram = base.copy(diagrams = base.diagrams.map { if (it.id == diagram.id) mutatedDiagram else it })
            val dsl = C4DslPrinter.print(withMutatedDiagram)
            dsl shouldNotContain "missing-id\nfun injected"
            dsl shouldContain "missing-id\\nfun injected"
            dsl.lines().filter { "injected" in it }.forEach { line -> line.trimStart() shouldStartWith "//" }
        }

        test("containerDiagram with an unresolvable system id containing a newline cannot break out of the TODO comment") {
            val base =
                c4Model(name = "Test") {
                    val system = softwareSystem(name = "System") { container(name = "Web App") }
                    containerDiagram(name = "Containers") { this.system = system }
                }
            val diagram = base.diagrams.filterIsInstance<ContainerDiagram>().single()
            val maliciousSystemId = "evil-system\nobject Injected"
            val mutatedDiagram = diagram.copy(system = maliciousSystemId)
            val withMutatedDiagram = base.copy(diagrams = base.diagrams.map { if (it.id == diagram.id) mutatedDiagram else it })
            val dsl = C4DslPrinter.print(withMutatedDiagram)
            dsl shouldNotContain "evil-system\nobject Injected"
            dsl shouldContain "evil-system\\nobject Injected"
            dsl.lines().filter { "Injected" in it }.forEach { line -> line.trimStart() shouldStartWith "//" }
        }
    })
