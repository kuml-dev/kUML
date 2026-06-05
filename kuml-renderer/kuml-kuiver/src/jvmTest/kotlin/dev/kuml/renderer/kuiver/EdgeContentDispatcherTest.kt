package dev.kuml.renderer.kuiver

import dev.kuml.c4.model.C4Relationship
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class EdgeContentDispatcherTest {

    @Test
    fun `EdgeContentDispatcher dispatchKey covers all 8 relationship kinds`() {
        val cases = listOf(
            UmlAssociation(
                id = "r1",
                ends = listOf(
                    UmlAssociationEnd(typeId = "A"),
                    UmlAssociationEnd(typeId = "B"),
                ),
            ) to "UmlAssociation",
            UmlGeneralization(id = "r2", specificId = "Child", generalId = "Parent") to "UmlGeneralization",
            UmlInterfaceRealization(id = "r3", implementingId = "Impl", interfaceId = "Iface") to "UmlInterfaceRealization",
            UmlDependency(id = "r4", clientId = "Client", supplierId = "Supplier") to "UmlDependency",
            UmlConnector(id = "r5", end1Id = "P1", end2Id = "P2") to "UmlConnector",
            UmlInclude(id = "r6", baseId = "Base", additionId = "Add") to "UmlInclude",
            UmlExtend(id = "r7", baseId = "Base", extensionId = "Ext") to "UmlExtend",
            C4Relationship(id = "r8", source = "S", target = "T", label = "calls") to "C4Relationship",
        )

        cases.forEach { (relationship, expectedKey) ->
            EdgeContentDispatcher.dispatchKey(relationship) shouldBe expectedKey
        }
    }
}
