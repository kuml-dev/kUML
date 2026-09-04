package dev.kuml.ai.tools.context

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlAssociationClass
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Regression tests for a MAJOR review finding: [UmlAssociationClass] implements
 * BOTH [dev.kuml.uml.UmlNamedElement] and [dev.kuml.uml.UmlRelationship], so
 * [AnyKumlModel.Uml.fromKumlDiagram] necessarily files it under both
 * [AnyKumlModel.Uml.elements] and [AnyKumlModel.Uml.relationships] (each bucket's
 * `filterIsInstance` matches it). Without deduplication, [AnyKumlModel.Uml.toKumlModel]
 * would concatenate both lists and emit the SAME association class twice into
 * [KumlDiagram.elements] — corrupting the model on every AI-panel roundtrip
 * (`AiPanelState.seedEditingContextFromScript()` calls exactly this path).
 */
class AnyKumlModelTest :
    FunSpec({

        test("fromKumlDiagram files an association class in both elements and relationships") {
            val party = UmlClass(id = "Party", name = "Party")
            val district = UmlClass(id = "District", name = "District")
            val tally =
                UmlAssociationClass(
                    id = "Tally",
                    name = "Tally",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Party"),
                            UmlAssociationEnd(typeId = "District"),
                        ),
                )
            val diagram =
                KumlDiagram(
                    name = "Election",
                    type = DiagramType.CLASS,
                    elements = listOf(party, district, tally),
                )

            val seed = AnyKumlModel.Uml.fromKumlDiagram(diagram)

            seed.elements.map { it.id } shouldBe listOf("Party", "District", "Tally")
            seed.relationships.map { it.id } shouldBe listOf("Tally")
        }

        test("toKumlModel emits an association class exactly once despite dual list membership") {
            val party = UmlClass(id = "Party", name = "Party")
            val district = UmlClass(id = "District", name = "District")
            val tally =
                UmlAssociationClass(
                    id = "Tally",
                    name = "Tally",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Party"),
                            UmlAssociationEnd(typeId = "District"),
                        ),
                )
            val diagram =
                KumlDiagram(
                    name = "Election",
                    type = DiagramType.CLASS,
                    elements = listOf(party, district, tally),
                )
            val seed = AnyKumlModel.Uml.fromKumlDiagram(diagram)

            val rebuilt = seed.toKumlModel()
            val rebuiltDiagram = rebuilt.root as KumlDiagram

            // Exactly one "Tally" entry — not two, as a naive
            // `elements + relationships` concatenation would produce.
            rebuiltDiagram.elements.count { it.id == "Tally" } shouldBe 1
            rebuiltDiagram.elements shouldHaveSize 3
        }

        test("toKumlModel roundtrip via fromKumlDiagram twice stays stable (no cumulative duplication)") {
            val employee = UmlClass(id = "Employee", name = "Employee")
            val reports =
                UmlAssociationClass(
                    id = "Reports",
                    name = "Reports",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Employee"),
                            UmlAssociationEnd(typeId = "Employee"),
                        ),
                )
            val diagram =
                KumlDiagram(
                    name = "Org",
                    type = DiagramType.CLASS,
                    elements = listOf(employee, reports),
                )

            val firstPass = AnyKumlModel.Uml.fromKumlDiagram(diagram).toKumlModel()
            val secondPass = AnyKumlModel.Uml.fromKumlDiagram(firstPass.root as KumlDiagram).toKumlModel()
            val secondDiagram = secondPass.root as KumlDiagram

            secondDiagram.elements.count { it.id == "Reports" } shouldBe 1
            secondDiagram.elements shouldHaveSize 2
        }
    })
