package dev.kuml.layout.bridge

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.layout.bridge.LayoutHintWriter.GridCell
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe

class LayoutHintWriterTest :
    FunSpec({

        fun classElement(
            id: String,
            meta: Map<String, KumlMetaValue> = emptyMap(),
        ) = UmlClass(id = id, name = id, metadata = meta)

        fun simpleDiagram(vararg elements: UmlClass) =
            KumlDiagram(
                name = "Test",
                type = DiagramType.CLASS,
                elements = elements.toList(),
            )

        test("single element placement writes gridCol and gridRow metadata") {
            val diagram = simpleDiagram(classElement("A"))
            val result = LayoutHintWriter.writeGridHints(diagram, mapOf("A" to GridCell(2, 3)))
            val aMeta = (result.elements.first() as UmlClass).metadata
            aMeta shouldContainKey BridgeLayoutKeys.GRID_COL
            aMeta shouldContainKey BridgeLayoutKeys.GRID_ROW
            (aMeta[BridgeLayoutKeys.GRID_COL] as KumlMetaValue.Integer).value shouldBe 2L
            (aMeta[BridgeLayoutKeys.GRID_ROW] as KumlMetaValue.Integer).value shouldBe 3L
        }

        test("multiple elements all receive their placements") {
            val diagram = simpleDiagram(classElement("A"), classElement("B"), classElement("C"))
            val placements =
                mapOf(
                    "A" to GridCell(0, 0),
                    "B" to GridCell(1, 0),
                    "C" to GridCell(2, 1),
                )
            val result = LayoutHintWriter.writeGridHints(diagram, placements)
            result.elements.forEach { element ->
                element as UmlClass
                val meta = element.metadata
                meta shouldContainKey BridgeLayoutKeys.GRID_COL
                meta shouldContainKey BridgeLayoutKeys.GRID_ROW
            }
            val aCol = (result.elements[0] as UmlClass).metadata[BridgeLayoutKeys.GRID_COL] as KumlMetaValue.Integer
            val bCol = (result.elements[1] as UmlClass).metadata[BridgeLayoutKeys.GRID_COL] as KumlMetaValue.Integer
            val cRow = (result.elements[2] as UmlClass).metadata[BridgeLayoutKeys.GRID_ROW] as KumlMetaValue.Integer
            aCol.value shouldBe 0L
            bCol.value shouldBe 1L
            cRow.value shouldBe 1L
        }

        test("unknown element id in placements leaves element unchanged") {
            val diagram = simpleDiagram(classElement("A"))
            val result = LayoutHintWriter.writeGridHints(diagram, mapOf("X" to GridCell(5, 5)))
            val aMeta = (result.elements.first() as UmlClass).metadata
            aMeta shouldNotContainKey BridgeLayoutKeys.GRID_COL
            aMeta shouldNotContainKey BridgeLayoutKeys.GRID_ROW
        }

        test("idempotent: writing same placement twice produces identical result") {
            val diagram = simpleDiagram(classElement("A"))
            val placements = mapOf("A" to GridCell(1, 2))
            val first = LayoutHintWriter.writeGridHints(diagram, placements)
            val second = LayoutHintWriter.writeGridHints(first, placements)
            val firstMeta = (first.elements.first() as UmlClass).metadata
            val secondMeta = (second.elements.first() as UmlClass).metadata
            firstMeta[BridgeLayoutKeys.GRID_COL] shouldBe secondMeta[BridgeLayoutKeys.GRID_COL]
            firstMeta[BridgeLayoutKeys.GRID_ROW] shouldBe secondMeta[BridgeLayoutKeys.GRID_ROW]
        }

        test("written metadata round-trips through HintsReader") {
            val diagram = simpleDiagram(classElement("A"))
            val result = LayoutHintWriter.writeGridHints(diagram, mapOf("A" to GridCell(3, 7)))
            val aMeta = (result.elements.first() as UmlClass).metadata
            val hints = HintsReader.read(aMeta)
            hints.gridCol shouldBe 3
            hints.gridRow shouldBe 7
        }

        test("writeGridHints with empty placements returns diagram unchanged") {
            val diagram = simpleDiagram(classElement("A"))
            val result = LayoutHintWriter.writeGridHints(diagram, emptyMap())
            result shouldBe diagram
        }
    })
