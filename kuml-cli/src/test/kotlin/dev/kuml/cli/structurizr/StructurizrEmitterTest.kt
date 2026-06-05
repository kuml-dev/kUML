package dev.kuml.cli.structurizr

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.c4.model.SystemLandscapeDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * V1.1 — Structurizr-Export Emitter-Tests + Roundtrip.
 */
class StructurizrEmitterTest :
    FunSpec({

        test("emit empty workspace contains workspace + model + views blocks") {
            val model = C4Model(id = "BigBank", name = "BigBank")
            val dsl = StructurizrEmitter.emit(model)
            dsl shouldContain "workspace \"BigBank\""
            dsl shouldContain "model {"
            dsl shouldContain "views {"
            dsl shouldContain "!identifiers hierarchical"
        }

        test("emit workspace with description") {
            val model =
                C4Model(
                    id = "Demo",
                    name = "Demo",
                    description = "A small demo",
                )
            val dsl = StructurizrEmitter.emit(model)
            dsl shouldContain "workspace \"Demo\" \"A small demo\""
        }

        test("emit person element") {
            val model =
                C4Model(
                    id = "M",
                    name = "M",
                    elements =
                        listOf(
                            C4Person(id = "customer", name = "Customer", description = "A user of the system"),
                        ),
                )
            val dsl = StructurizrEmitter.emit(model)
            dsl shouldContain "customer = person \"Customer\" \"A user of the system\""
        }

        test("emit external person carries an 'External' tag") {
            val model =
                C4Model(
                    id = "M",
                    name = "M",
                    elements = listOf(C4Person(id = "p", name = "Auditor", external = true)),
                )
            val dsl = StructurizrEmitter.emit(model)
            dsl shouldContain "\"External\""
        }

        test("emit software system with containers and components") {
            val model =
                C4Model(
                    id = "M",
                    name = "M",
                    elements =
                        listOf(
                            C4SoftwareSystem(
                                id = "ib",
                                name = "Internet Banking",
                                description = "Online banking",
                                containers = listOf("web", "api"),
                            ),
                            C4Container(
                                id = "web",
                                name = "Web App",
                                description = "UI layer",
                                technology = "Spring MVC",
                                system = "ib",
                                components = listOf("loginCtrl"),
                            ),
                            C4Component(
                                id = "loginCtrl",
                                name = "Login Controller",
                                description = "Handles login",
                                technology = "Spring",
                                container = "web",
                            ),
                            C4Container(id = "api", name = "API", description = "REST API", technology = "Ktor", system = "ib"),
                        ),
                )
            val dsl = StructurizrEmitter.emit(model)
            dsl shouldContain "ib = softwareSystem \"Internet Banking\" \"Online banking\""
            dsl shouldContain "web = container \"Web App\" \"UI layer\" \"Spring MVC\""
            dsl shouldContain "loginCtrl = component \"Login Controller\""
            dsl shouldContain "api = container \"API\" \"REST API\" \"Ktor\""
        }

        test("emit relationship in arrow form with label and technology") {
            val model =
                C4Model(
                    id = "M",
                    name = "M",
                    elements =
                        listOf(
                            C4Person(id = "customer", name = "Customer"),
                            C4SoftwareSystem(id = "ib", name = "Internet Banking"),
                        ),
                    relationships =
                        listOf(
                            C4Relationship(
                                id = "r1",
                                source = "customer",
                                target = "ib",
                                label = "Uses",
                                technology = "HTTPS",
                            ),
                        ),
                )
            val dsl = StructurizrEmitter.emit(model)
            dsl shouldContain "customer -> ib \"Uses\" \"HTTPS\""
        }

        test("emit nested deployment nodes") {
            val model =
                C4Model(
                    id = "M",
                    name = "M",
                    elements =
                        listOf(
                            C4DeploymentNode(id = "aws", name = "AWS", technology = "Cloud", children = listOf("eu_region")),
                            C4DeploymentNode(id = "eu_region", name = "EU Region", technology = "Region"),
                        ),
                )
            val dsl = StructurizrEmitter.emit(model)
            dsl shouldContain "aws = deploymentNode \"AWS\""
            dsl shouldContain "eu_region = deploymentNode \"EU Region\""
            // Nested form expected
            dsl shouldContain "{"
        }

        test("emit views block contains systemContext / container / systemLandscape entries") {
            val model =
                C4Model(
                    id = "M",
                    name = "M",
                    elements = listOf(C4SoftwareSystem(id = "ib", name = "IB")),
                    diagrams =
                        listOf(
                            SystemContextDiagram(id = "ib-context", name = "Context", description = "Top level", elements = listOf("ib")),
                            ContainerDiagram(id = "ib-containers", name = "Containers", system = "ib", elements = listOf("ib")),
                            SystemLandscapeDiagram(id = "landscape", name = "Landscape"),
                        ),
                )
            val dsl = StructurizrEmitter.emit(model)
            dsl shouldContain "systemContext ib"
            dsl shouldContain "container ib"
            dsl shouldContain "systemLandscape"
            dsl shouldContain "autolayout lr"
        }

        test("emit sanitizes identifiers with spaces and special chars") {
            val model =
                C4Model(
                    id = "M",
                    name = "M",
                    elements =
                        listOf(
                            C4Person(id = "Important User!", name = "VIP"),
                            C4SoftwareSystem(id = "System A", name = "A"),
                        ),
                    relationships =
                        listOf(
                            C4Relationship(id = "r", source = "Important User!", target = "System A", label = "Uses"),
                        ),
                )
            val dsl = StructurizrEmitter.emit(model)
            // Sanitized identifiers used on both ends of the arrow
            dsl shouldContain "Important_User"
            dsl shouldContain "System_A"
            dsl shouldNotContain "Important User!"
            dsl shouldContain "Important_User = person"
        }

        test("emit escapes double quotes in names and descriptions") {
            val model =
                C4Model(
                    id = "M",
                    name = "M",
                    elements = listOf(C4Person(id = "p", name = "User \"alice\"")),
                )
            val dsl = StructurizrEmitter.emit(model)
            dsl shouldContain """p = person "User \"alice\"""""
        }

        // ── Roundtrip Tests ───────────────────────────────────────────────

        test("roundtrip: emit + parse yields the same persons and systems") {
            val model =
                C4Model(
                    id = "RT",
                    name = "RT",
                    elements =
                        listOf(
                            C4Person(id = "customer", name = "Customer", description = "User"),
                            C4SoftwareSystem(id = "ib", name = "Internet Banking", description = "System"),
                        ),
                    relationships =
                        listOf(
                            C4Relationship(
                                id = "r1",
                                source = "customer",
                                target = "ib",
                                label = "Uses",
                                technology = "HTTPS",
                            ),
                        ),
                )
            val dsl = StructurizrEmitter.emit(model)
            val parsed = StructurizrDslParser.parse(dsl)

            parsed.name shouldBe "RT"
            val parsedPersons = parsed.model.elements.filterIsInstance<StructurizrElement.Person>()
            shouldContainOne(parsedPersons) {
                it.identifier shouldBe "customer"
                it.name shouldBe "Customer"
                it.description shouldBe "User"
            }
            val parsedSystems = parsed.model.elements.filterIsInstance<StructurizrElement.SoftwareSystem>()
            shouldContainOne(parsedSystems) {
                it.identifier shouldBe "ib"
                it.name shouldBe "Internet Banking"
            }
            val rels = parsed.model.relationships
            rels.size shouldBe 1
            rels.first().sourceIdentifier shouldBe "customer"
            rels.first().targetIdentifier shouldBe "ib"
            rels.first().description shouldBe "Uses"
            rels.first().technology shouldBe "HTTPS"
        }

        test("roundtrip: nested software system → containers → components survives") {
            val model =
                C4Model(
                    id = "RT2",
                    name = "RT2",
                    elements =
                        listOf(
                            C4SoftwareSystem(id = "ib", name = "Banking", containers = listOf("web")),
                            C4Container(
                                id = "web",
                                name = "WebApp",
                                description = null,
                                technology = "Spring",
                                system = "ib",
                                components = listOf("ctrl"),
                            ),
                            C4Component(id = "ctrl", name = "Login Controller", technology = "Spring", container = "web"),
                        ),
                )
            val dsl = StructurizrEmitter.emit(model)
            val parsed = StructurizrDslParser.parse(dsl)
            val systems = parsed.model.elements.filterIsInstance<StructurizrElement.SoftwareSystem>()
            systems.size shouldBe 1
            systems.first().containers.size shouldBe 1
            systems
                .first()
                .containers
                .first()
                .components.size shouldBe 1
            systems
                .first()
                .containers
                .first()
                .components
                .first()
                .name shouldBe "Login Controller"
        }
    })

// ── small helper because kotest's `shouldHaveSingleElement` matcher doesn't exist ─

private fun <T> shouldContainOne(
    list: List<T>,
    asserter: (T) -> Unit,
) {
    if (list.size != 1) error("Expected exactly one element, got ${list.size}")
    asserter(list.first())
}
