package dev.kuml.layout.bridge

import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.layout.LayoutGraph
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BridgeLayoutKeysTest : FunSpec({

    test("BridgeLayoutKeys all start with kuml.layout") {
        val keysUnderNamespace =
            listOf(
                BridgeLayoutKeys.GRID_COL,
                BridgeLayoutKeys.GRID_ROW,
                BridgeLayoutKeys.GRID_COL_SPAN,
                BridgeLayoutKeys.GRID_ROW_SPAN,
                BridgeLayoutKeys.PINNED,
                BridgeLayoutKeys.RELATIVE,
            )
        // REL_KIND and REL_OTHER are sub-keys (not top-level namespace keys)
        keysUnderNamespace.forEach { key ->
            key.startsWith("kuml.layout") shouldBe true
        }
    }

    test("LayoutGraph round-trips through json") {
        val system = C4SoftwareSystem(id = "sys1", name = "System")
        val containerA = C4Container(id = "cA", name = "Frontend", system = "sys1")
        val containerB = C4Container(id = "cB", name = "Backend", system = "sys1")
        val rel = C4Relationship(id = "r1", source = "cA", target = "cB", label = "Uses")
        val model =
            C4Model(
                id = "m1",
                name = "Model",
                elements = listOf(system, containerA, containerB),
                relationships = listOf(rel),
            )
        val diagram =
            ContainerDiagram(
                id = "d1",
                name = "View",
                system = "sys1",
                elements = listOf("cA", "cB"),
                relationships = listOf("r1"),
            )

        val original = C4LayoutBridge.toLayoutGraph(diagram, model)

        val json = Json { prettyPrint = false }
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<LayoutGraph>(encoded)

        decoded.nodes.size shouldBe original.nodes.size
        decoded.edges.size shouldBe original.edges.size
        decoded.groups.size shouldBe original.groups.size
        decoded.nodes.map { it.id } shouldBe original.nodes.map { it.id }
        decoded.edges.map { it.id } shouldBe original.edges.map { it.id }
        decoded.groups.map { it.id } shouldBe original.groups.map { it.id }
    }
})
