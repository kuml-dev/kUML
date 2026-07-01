package dev.kuml.uml.dsl

import dev.kuml.core.dsl.activityDiagram
import dev.kuml.core.dsl.communicationDiagram
import dev.kuml.core.dsl.compositeStructureDiagram
import dev.kuml.core.dsl.deploymentDiagram
import dev.kuml.core.dsl.interactionOverviewDiagram
import dev.kuml.core.dsl.packageDiagram
import dev.kuml.core.dsl.profileDiagram
import dev.kuml.core.dsl.timingDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind
import dev.kuml.uml.UmlArtifact
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlInstanceSpecification
import dev.kuml.uml.UmlInteractionFrameKind
import dev.kuml.uml.UmlInteractionOverviewFrame
import dev.kuml.uml.UmlLink
import dev.kuml.uml.UmlNode
import dev.kuml.uml.UmlStereotype
import dev.kuml.uml.UmlTimingLifeline
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class V11DiagramBuildersTest :
    FunSpec({

        test("packageDiagram builds DiagramType.PACKAGE") {
            val d = packageDiagram(name = "Pkg") { }
            d.type shouldBe DiagramType.PACKAGE
        }

        test("compositeStructureDiagram builds DiagramType.COMPOSITE_STRUCTURE") {
            val d = compositeStructureDiagram(name = "CS") { }
            d.type shouldBe DiagramType.COMPOSITE_STRUCTURE
        }

        test("deploymentDiagram builds nodes, artifacts and deploy dependencies") {
            val d =
                deploymentDiagram(name = "Prod") {
                    val server = node(name = "Server")
                    val webApp = artifact(name = "webapp.war")
                    deploy(artifact = webApp, node = server)
                    val db = device(name = "PostgreSQL")
                    communicationPath(end1 = server, end2 = db)
                }
            d.type shouldBe DiagramType.DEPLOYMENT
            val nodes = d.elements.filterIsInstance<UmlNode>()
            nodes shouldHaveSize 2
            d.elements.filterIsInstance<UmlArtifact>() shouldHaveSize 1
            val deps = d.elements.filterIsInstance<UmlDependency>()
            deps shouldHaveSize 2
            deps.any { it.name == "«deploy»" } shouldBe true
            deps.any { it.name == "«communicationPath»" } shouldBe true
        }

        test("nested nodes inside a deployment node are captured under children") {
            val d =
                deploymentDiagram(name = "AWS") {
                    executionEnvironment(name = "EKS Cluster") {
                        node(name = "Pod")
                        artifact(name = "config.yaml")
                    }
                }
            val cluster = d.elements.filterIsInstance<UmlNode>().single()
            cluster.children.filterIsInstance<UmlNode>() shouldHaveSize 1
            cluster.artifacts shouldHaveSize 1
        }

        test("profileDiagram declares stereotypes and extension dependencies") {
            val d =
                profileDiagram(name = "Java EE") {
                    val entity =
                        stereotype(name = "Entity", metaclasses = listOf("Class")) {
                            tag(name = "tableName", type = "String")
                        }
                    val mc = metaclass(name = "Class")
                    extension(stereotype = entity, metaclass = mc)
                }
            d.type shouldBe DiagramType.PROFILE
            val stereotypes = d.elements.filterIsInstance<UmlStereotype>()
            stereotypes shouldHaveSize 1
            stereotypes.single().tagDefinitions shouldHaveSize 1
            stereotypes.single().metaclasses shouldBe listOf("Class")
        }

        test("activityDiagram builds nodes and edges with guards") {
            val d =
                activityDiagram(name = "Checkout") {
                    val init = initialNode()
                    val verify = action(name = "Verify cart")
                    val ok = decision(name = "valid?")
                    val charge = action(name = "Charge card")
                    val end = finalNode()
                    edge(from = init, to = verify)
                    edge(from = verify, to = ok)
                    edge(from = ok, to = charge, guard = "ok")
                    edge(from = charge, to = end)
                }
            d.type shouldBe DiagramType.ACTIVITY
            val nodes = d.elements.filterIsInstance<UmlActivityNode>()
            nodes.any { it.kind == UmlActivityNodeKind.INITIAL } shouldBe true
            nodes.any { it.kind == UmlActivityNodeKind.DECISION } shouldBe true
            val edges = d.elements.filterIsInstance<UmlActivityEdge>()
            (edges.firstOrNull { it.guard == "ok" } != null) shouldBe true
        }

        test("communicationDiagram numbers messages") {
            val d =
                communicationDiagram(name = "Place Order") {
                    val ui = role(classifierName = "UI", roleName = "ui")
                    val api = role(classifierName = "API", roleName = "api")
                    message(from = ui, to = api, label = "submit()")
                    message(from = api, to = ui, label = "201 Created")
                }
            d.type shouldBe DiagramType.COMMUNICATION
            d.elements.filterIsInstance<UmlInstanceSpecification>() shouldHaveSize 2
            // The two messages share a single link with combined labels.
            d.elements.filterIsInstance<UmlLink>().shouldNotBeEmpty()
        }

        test("timingDiagram captures lifeline ticks") {
            val d =
                timingDiagram(name = "TCP handshake") {
                    lifeline(name = "client", states = listOf("CLOSED", "SYN_SENT", "ESTABLISHED")) {
                        tick(t = 0, state = "CLOSED")
                        tick(t = 1, state = "SYN_SENT")
                        tick(t = 3, state = "ESTABLISHED")
                    }
                }
            d.type shouldBe DiagramType.TIMING
            val lifeline = d.elements.filterIsInstance<UmlTimingLifeline>().single()
            lifeline.timeline shouldHaveSize 3
            lifeline.states shouldBe listOf("CLOSED", "SYN_SENT", "ESTABLISHED")
        }

        test("interactionOverviewDiagram mixes refs with control flow") {
            val d =
                interactionOverviewDiagram(name = "Order overview") {
                    val init = initial()
                    val login = interactionRef(name = "Login")
                    val checkout = interactionRef(name = "Checkout")
                    val end = final()
                    edge(from = init, to = login)
                    edge(from = login, to = checkout)
                    edge(from = checkout, to = end)
                }
            d.type shouldBe DiagramType.INTERACTION_OVERVIEW
            val frames = d.elements.filterIsInstance<UmlInteractionOverviewFrame>()
            frames.any { it.kind == UmlInteractionFrameKind.INTERACTION_REF } shouldBe true
            frames.any { it.kind == UmlInteractionFrameKind.INITIAL } shouldBe true
            d.elements.filterIsInstance<UmlActivityEdge>() shouldHaveSize 3
        }
    })
