package dev.kuml.layout.bridge

import dev.kuml.kerml.KermlSpecialization
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
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

        // ── IBD (V2.0.6) ──────────────────────────────────────────────────────

        "IBD with two part-usages and a connection → 2 nodes + 1 edge" {
            val model =
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val batteryDef = partDef("Battery")
                    val powerLine = connectionDef("PowerLine")
                    val vehicle =
                        partDef("Vehicle") {
                            part("engine", typeId = engineDef.id)
                            part("battery", typeId = batteryDef.id)
                            connect(
                                name = "wiring",
                                typeId = powerLine.id,
                                sourceEndId = "Vehicle::engine::out",
                                targetEndId = "Vehicle::battery::in",
                            )
                        }
                    ibd("Vehicle wiring", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, ibd)

            graph.nodes shouldHaveSize 2
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("Vehicle::engine", "Vehicle::battery")
            graph.edges shouldHaveSize 1
            graph.edges
                .single()
                .id.value shouldBe "conn:Vehicle::wiring"
            graph.edges
                .single()
                .source.nodeId.value shouldBe "Vehicle::engine"
            graph.edges
                .single()
                .target.nodeId.value shouldBe "Vehicle::battery"

            SampleOutput.write(
                "sysml2-layout-bridge/ibd-two-parts-one-connection.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "IBD with no part-usages → empty graph (nodes + edges both empty)" {
            val model =
                sysml2Model("M") {
                    val empty = partDef("EmptyShell")
                    ibd("Empty shell", owner = empty)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, ibd)
            graph.nodes shouldHaveSize 0
            graph.edges shouldHaveSize 0

            SampleOutput.write(
                "sysml2-layout-bridge/ibd-empty-shell.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "IBD with elementIds filter selects subset" {
            val model =
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val batteryDef = partDef("Battery")
                    val controllerDef = partDef("Controller")
                    val powerLine = connectionDef("PowerLine")
                    val vehicle =
                        partDef("Vehicle") {
                            part("engine", typeId = engineDef.id)
                            part("battery", typeId = batteryDef.id)
                            part("controller", typeId = controllerDef.id)
                            connect(
                                name = "powerWire",
                                typeId = powerLine.id,
                                sourceEndId = "Vehicle::engine::out",
                                targetEndId = "Vehicle::battery::in",
                            )
                        }
                    ibd("Power-train only", owner = vehicle) {
                        includeById("Vehicle::engine")
                        includeById("Vehicle::battery")
                    }
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, ibd)

            graph.nodes shouldHaveSize 2
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("Vehicle::engine", "Vehicle::battery")
            graph.edges shouldHaveSize 1

            SampleOutput.write(
                "sysml2-layout-bridge/ibd-filtered-subset.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "IBD with connection to dangling endpoint → edge dropped" {
            val model =
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val batteryDef = partDef("Battery")
                    val powerLine = connectionDef("PowerLine")
                    val vehicle =
                        partDef("Vehicle") {
                            part("engine", typeId = engineDef.id)
                            part("battery", typeId = batteryDef.id)
                            // Connection target points at a non-existent part-usage.
                            connect(
                                name = "dangling",
                                typeId = powerLine.id,
                                sourceEndId = "Vehicle::engine::out",
                                targetEndId = "Vehicle::nonexistent::in",
                            )
                        }
                    ibd("Vehicle wiring (dangling)", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, ibd)
            graph.nodes shouldHaveSize 2
            // The dangling connection is silently dropped — validator's job to
            // flag this; bridge stays render-friendly.
            graph.edges shouldHaveSize 0

            SampleOutput.write(
                "sysml2-layout-bridge/ibd-dangling-connection.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "IBD with missing owner → empty graph (validator's job, not bridge's)" {
            val model =
                sysml2Model("M") {
                    partDef("Vehicle")
                }
            // Hand-craft an IbdDiagram for a non-existent owner id.
            val ibd = IbdDiagram(name = "Ghost", ownerId = "NotInModel")
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, ibd)
            graph.nodes shouldHaveSize 0
            graph.edges shouldHaveSize 0
        }

        "IBD default size matches IBD_DEFAULT_WIDTH × IBD_DEFAULT_HEIGHT" {
            val model =
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val vehicle =
                        partDef("Vehicle") {
                            part("engine", typeId = engineDef.id)
                        }
                    ibd("D", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, ibd)
            graph.nodes
                .single()
                .intrinsicSize.width shouldBe Sysml2LayoutBridge.IBD_DEFAULT_WIDTH
            graph.nodes
                .single()
                .intrinsicSize.height shouldBe Sysml2LayoutBridge.IBD_DEFAULT_HEIGHT
        }
    })
