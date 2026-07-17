package dev.kuml.renderer.kuiver

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.core.model.KumlElement
import dev.kuml.uml.UmlActor
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlUseCase
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class NodeContentDispatcherTest {
    @Test
    fun `NodeContentDispatcher dispatchKey returns simpleName for all 11 element kinds`() {
        val cases =
            listOf(
                UmlClass(id = "c1", name = "A") to "UmlClass",
                UmlInterface(id = "i1", name = "B") to "UmlInterface",
                UmlEnumeration(id = "e1", name = "C") to "UmlEnumeration",
                UmlComponent(id = "cp1", name = "D") to "UmlComponent",
                UmlActor(id = "a1", name = "E") to "UmlActor",
                UmlUseCase(id = "uc1", name = "F") to "UmlUseCase",
                UmlState(id = "s1", name = "G") to "UmlState",
                C4Person(id = "p1", name = "H") to "C4Person",
                C4SoftwareSystem(id = "ss1", name = "I") to "C4SoftwareSystem",
                C4Container(id = "ct1", name = "J") to "C4Container",
                C4Component(id = "cc1", name = "K") to "C4Component",
            )

        cases.forEach { (element, expectedKey) ->
            NodeContentDispatcher.dispatchKey(element) shouldBe expectedKey
        }
    }

    @Test
    fun `NodeContentDispatcher dispatchKey returns class name for unknown element`() {
        val unknown =
            object : KumlElement {
                override val id = "unknown-1"
                override val metadata = emptyMap<String, dev.kuml.core.model.KumlMetaValue>()
            }
        // Anonymous objects have a simpleName of null — fallback is "Unknown"
        // but named objects will have their class simpleName.
        // The dispatchKey contract: return simpleName ?: "Unknown"
        val key = NodeContentDispatcher.dispatchKey(unknown)
        // For anonymous objects simpleName is null → "Unknown"
        key shouldBe "Unknown"
    }
}
