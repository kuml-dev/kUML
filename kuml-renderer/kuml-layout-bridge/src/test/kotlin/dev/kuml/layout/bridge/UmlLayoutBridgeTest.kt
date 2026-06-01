package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.GroupId
import dev.kuml.layout.NodeId
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlPackage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class UmlLayoutBridgeTest : FunSpec({

    test("UmlLayoutBridge converts a minimal class diagram") {
        val classA = UmlClass(id = "classA", name = "ClassA")
        val classB = UmlClass(id = "classB", name = "ClassB")
        val assoc =
            UmlAssociation(
                id = "assoc1",
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = "classA"),
                        UmlAssociationEnd(typeId = "classB"),
                    ),
            )
        val diagram =
            KumlDiagram(
                name = "Minimal",
                elements = listOf(classA, classB, assoc),
            )

        val graph = UmlLayoutBridge.toLayoutGraph(diagram)

        graph.nodes shouldHaveSize 2
        graph.edges shouldHaveSize 1
        graph.groups shouldHaveSize 0

        val nodeIds = graph.nodes.map { it.id }
        nodeIds shouldBe listOf(NodeId("classA"), NodeId("classB"))

        val edge = graph.edges[0]
        edge.source.nodeId shouldBe NodeId("classA")
        edge.target.nodeId shouldBe NodeId("classB")
    }

    test("UmlLayoutBridge wraps classes in a package group") {
        val classA = UmlClass(id = "classA", name = "ClassA")
        val classB = UmlClass(id = "classB", name = "ClassB")
        val pkg =
            UmlPackage(
                id = "pkg1",
                name = "com.example",
                members = listOf(classA, classB),
            )
        val diagram =
            KumlDiagram(
                name = "Packaged",
                elements = listOf(pkg),
            )

        val graph = UmlLayoutBridge.toLayoutGraph(diagram)

        graph.groups shouldHaveSize 1
        graph.groups[0].id shouldBe GroupId("pkg1")

        graph.nodes shouldHaveSize 2
        graph.nodes.forEach { node ->
            node.groupId shouldBe GroupId("pkg1")
        }
    }

    test("UmlLayoutBridge maps generalization as edge") {
        val parent = UmlClass(id = "parent", name = "Animal")
        val child = UmlClass(id = "child", name = "Dog")
        val gen = UmlGeneralization(id = "gen1", specificId = "child", generalId = "parent")
        val diagram =
            KumlDiagram(
                name = "Generalization",
                elements = listOf(parent, child, gen),
            )

        val graph = UmlLayoutBridge.toLayoutGraph(diagram)

        graph.edges shouldHaveSize 1
        val edge = graph.edges[0]
        edge.source.nodeId shouldBe NodeId("child")
        edge.target.nodeId shouldBe NodeId("parent")
        edge.hints shouldNotBe null
    }
})
