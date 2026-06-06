package dev.kuml.layout.bridge

import dev.kuml.kerml.KermlSpecialization
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Sysml2LayoutBridgeTest :
    StringSpec({

        val prettyJson = Json { prettyPrint = true }

        "BDD with two parts and a specialisation → 2 nodes + 1 edge" {
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle", isAbstract = true)
            val hybrid =
                PartDefinition(
                    id = "HybridVehicle",
                    name = "HybridVehicle",
                    specializations =
                        listOf(
                            KermlSpecialization(specificId = "HybridVehicle", generalId = "Vehicle"),
                        ),
                )
            val model = Sysml2Model(name = "Demo", definitions = listOf(vehicle, hybrid))
            val bdd = BdDiagram(name = "Overview", elementIds = listOf("Vehicle", "HybridVehicle"))

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, bdd)

            graph.nodes shouldHaveSize 2
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder listOf("Vehicle", "HybridVehicle")
            graph.edges shouldHaveSize 1
            graph.edges
                .single()
                .id.value shouldBe "gen:HybridVehicle::Vehicle"
            graph.edges
                .single()
                .source.nodeId.value shouldBe "HybridVehicle"
            graph.edges
                .single()
                .target.nodeId.value shouldBe "Vehicle"

            SampleOutput.write(
                "sysml2-layout-bridge/two-parts-one-specialisation.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "edges to definitions outside the BDD selection are dropped" {
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle")
            val hybrid =
                PartDefinition(
                    id = "HybridVehicle",
                    name = "HybridVehicle",
                    specializations =
                        listOf(
                            KermlSpecialization(specificId = "HybridVehicle", generalId = "Vehicle"),
                        ),
                )
            val model = Sysml2Model(name = "Demo", definitions = listOf(vehicle, hybrid))
            // Only HybridVehicle is included — the parent end is dangling.
            val bdd = BdDiagram(name = "PartialView", elementIds = listOf("HybridVehicle"))

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, bdd)

            graph.nodes shouldHaveSize 1
            graph.edges shouldHaveSize 0
        }

        "missing definitions are skipped silently (validator's job, not bridge's)" {
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle")
            val model = Sysml2Model(name = "Demo", definitions = listOf(vehicle))
            val bdd = BdDiagram(name = "Demo", elementIds = listOf("Vehicle", "NonExistent"))

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, bdd)

            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "Vehicle"
        }

        "resolveVisibleDefinitions returns the BDD selection in declaration order" {
            val a = PartDefinition(id = "A", name = "A")
            val b = PartDefinition(id = "B", name = "B")
            val model = Sysml2Model(name = "M", definitions = listOf(a, b))
            val bdd = BdDiagram(name = "D", elementIds = listOf("B", "A"))
            Sysml2LayoutBridge.resolveVisibleDefinitions(model, bdd) shouldBe listOf(b, a)
        }

        "default size matches the announced 220×140 constants" {
            val v = PartDefinition(id = "V", name = "V")
            val model = Sysml2Model(name = "M", definitions = listOf(v))
            val bdd = BdDiagram(name = "D", elementIds = listOf("V"))
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, bdd)
            graph.nodes
                .single()
                .intrinsicSize.width shouldBe Sysml2LayoutBridge.DEFAULT_WIDTH
            graph.nodes
                .single()
                .intrinsicSize.height shouldBe Sysml2LayoutBridge.DEFAULT_HEIGHT
        }
    })
