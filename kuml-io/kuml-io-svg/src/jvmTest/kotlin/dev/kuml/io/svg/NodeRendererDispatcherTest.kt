package dev.kuml.io.svg

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.uml.UmlActor
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NodeRendererDispatcherTest :
    FunSpec({

        test("NodeRendererDispatcher selects correct renderer for all 11 element kinds") {
            // UML × 7
            NodeRendererDispatcher.dispatchKey(
                UmlClass(id = "c1", name = "Foo"),
            ) shouldBe "UmlClass"

            NodeRendererDispatcher.dispatchKey(
                UmlInterface(id = "i1", name = "IFoo"),
            ) shouldBe "UmlInterface"

            NodeRendererDispatcher.dispatchKey(
                UmlEnumeration(id = "e1", name = "Status"),
            ) shouldBe "UmlEnumeration"

            NodeRendererDispatcher.dispatchKey(
                UmlComponent(id = "co1", name = "Auth"),
            ) shouldBe "UmlComponent"

            NodeRendererDispatcher.dispatchKey(
                UmlActor(id = "a1", name = "User"),
            ) shouldBe "UmlActor"

            NodeRendererDispatcher.dispatchKey(
                UmlUseCase(id = "uc1", name = "Login"),
            ) shouldBe "UmlUseCase"

            NodeRendererDispatcher.dispatchKey(
                UmlState(id = "s1", name = "Active"),
            ) shouldBe "UmlState"

            // C4 × 4
            NodeRendererDispatcher.dispatchKey(
                C4Person(id = "p1", name = "Customer"),
            ) shouldBe "C4Person"

            NodeRendererDispatcher.dispatchKey(
                C4SoftwareSystem(id = "ss1", name = "BankApp"),
            ) shouldBe "C4SoftwareSystem"

            NodeRendererDispatcher.dispatchKey(
                C4Container(id = "ct1", name = "WebApp"),
            ) shouldBe "C4Container"

            NodeRendererDispatcher.dispatchKey(
                C4Component(id = "cp1", name = "AuthService"),
            ) shouldBe "C4Component"
        }
    })
