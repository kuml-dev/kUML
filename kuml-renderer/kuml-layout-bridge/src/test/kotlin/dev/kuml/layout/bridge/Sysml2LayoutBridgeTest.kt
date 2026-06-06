package dev.kuml.layout.bridge

import dev.kuml.kerml.KermlSpecialization
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.ReqContains
import dev.kuml.sysml2.ReqDerive
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.ReqSatisfy
import dev.kuml.sysml2.ReqVerify
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcAssociation
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UcExtend
import dev.kuml.sysml2.UcInclude
import dev.kuml.sysml2.UseCaseDefinition
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

        // ── UC Diagram (V2.0.7) ───────────────────────────────────────────────

        "UC with one actor + two use cases + association + include → 3 nodes + 2 edges" {
            val model =
                sysml2Model("Library") {
                    val reader = actorDef("Reader")
                    val borrow = useCaseDef("BorrowBook")
                    val auth = useCaseDef("Authenticate")
                    ucDiagram("UC") {
                        include(reader)
                        include(borrow)
                        include(auth)
                        association(reader, borrow)
                        include(borrow, auth)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, uc)
            graph.nodes shouldHaveSize 3
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("Reader", "BorrowBook", "Authenticate")
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("assoc:Reader::BorrowBook", "include:BorrowBook::Authenticate")

            SampleOutput.write(
                "sysml2-layout-bridge/uc-library-association-and-include.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "UC drops associations / includes / extends with dangling endpoints" {
            // Reader is visible, Librarian isn't — assoc(Librarian, BorrowBook) is dropped.
            val reader = ActorDefinition(id = "Reader", name = "Reader")
            val librarian = ActorDefinition(id = "Librarian", name = "Librarian")
            val borrow = UseCaseDefinition(id = "BorrowBook", name = "BorrowBook")
            val auth = UseCaseDefinition(id = "Authenticate", name = "Authenticate")
            val ghost = UseCaseDefinition(id = "Ghost", name = "Ghost")
            val model =
                Sysml2Model(
                    name = "Dangle",
                    definitions = listOf(reader, librarian, borrow, auth, ghost),
                )
            val uc =
                UcDiagram(
                    name = "UC",
                    // Librarian + ghost NOT in elementIds → endpoints dangle.
                    elementIds = listOf("Reader", "BorrowBook", "Authenticate"),
                    associations =
                        listOf(
                            UcAssociation(id = "assoc:Reader::BorrowBook", actorId = "Reader", useCaseId = "BorrowBook"),
                            UcAssociation(
                                id = "assoc:Librarian::BorrowBook",
                                actorId = "Librarian",
                                useCaseId = "BorrowBook",
                            ),
                        ),
                    includes =
                        listOf(
                            UcInclude(
                                id = "include:BorrowBook::Authenticate",
                                sourceUseCaseId = "BorrowBook",
                                targetUseCaseId = "Authenticate",
                            ),
                            UcInclude(
                                id = "include:BorrowBook::Ghost",
                                sourceUseCaseId = "BorrowBook",
                                targetUseCaseId = "Ghost",
                            ),
                        ),
                    extends =
                        listOf(
                            UcExtend(
                                id = "extend:Ghost::Authenticate",
                                sourceUseCaseId = "Ghost",
                                targetUseCaseId = "Authenticate",
                            ),
                        ),
                )

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, uc)
            graph.nodes shouldHaveSize 3
            // Only the two edges with both endpoints visible survive.
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("assoc:Reader::BorrowBook", "include:BorrowBook::Authenticate")
        }

        "UC respects actor vs use-case default sizes" {
            val model =
                sysml2Model("Sizes") {
                    val reader = actorDef("Reader")
                    val borrow = useCaseDef("BorrowBook")
                    ucDiagram("UC") {
                        include(reader)
                        include(borrow)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, uc)

            val readerNode = graph.nodes.single { it.id.value == "Reader" }
            val borrowNode = graph.nodes.single { it.id.value == "BorrowBook" }
            readerNode.intrinsicSize.width shouldBe Sysml2LayoutBridge.UC_ACTOR_WIDTH
            readerNode.intrinsicSize.height shouldBe Sysml2LayoutBridge.UC_ACTOR_HEIGHT
            borrowNode.intrinsicSize.width shouldBe Sysml2LayoutBridge.UC_USECASE_WIDTH
            borrowNode.intrinsicSize.height shouldBe Sysml2LayoutBridge.UC_USECASE_HEIGHT
        }

        "UC missing definitions in the model are skipped silently" {
            val reader = ActorDefinition(id = "Reader", name = "Reader")
            val model = Sysml2Model(name = "M", definitions = listOf(reader))
            val uc =
                UcDiagram(
                    name = "UC",
                    elementIds = listOf("Reader", "NonExistent"),
                )
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, uc)
            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "Reader"

            SampleOutput.write(
                "sysml2-layout-bridge/uc-missing-definition.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "UC skips non-UC definitions in elementIds silently (e.g. PartDefinition)" {
            // V2.0.7-Konvention: UC-Diagramme zeigen nur Actors + UseCases.
            // PartDefinitions, die versehentlich in elementIds landen, werden
            // verworfen — Validator-Sache, gemäß BDD/IBD-Pattern.
            val reader = ActorDefinition(id = "Reader", name = "Reader")
            val borrow = UseCaseDefinition(id = "BorrowBook", name = "BorrowBook")
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle")
            val model = Sysml2Model(name = "M", definitions = listOf(reader, borrow, vehicle))
            val uc = UcDiagram(name = "UC", elementIds = listOf("Reader", "BorrowBook", "Vehicle"))

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, uc)
            graph.nodes shouldHaveSize 2
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder listOf("Reader", "BorrowBook")
        }

        // ── REQ Diagram (V2.0.8) ──────────────────────────────────────────────

        "REQ with two requirements + one satisfy + one verify → nodes + 2 edges" {
            val model =
                sysml2Model("VehicleReqs") {
                    val topSpeed = requirementDef("TopSpeed", reqId = "R-001", text = "≥180 km/h")
                    requirementDef("Fuel", reqId = "R-003", text = "≤4 l/100km")
                    val vehicle = partDef("Vehicle")
                    val verifier = useCaseDef("VerifyTopSpeed")
                    reqDiagram("REQ") {
                        include(topSpeed)
                        include(vehicle)
                        include(verifier)
                        satisfy(vehicle, topSpeed)
                        verify(verifier, topSpeed)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, req)
            graph.nodes shouldHaveSize 3
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("TopSpeed", "Vehicle", "VerifyTopSpeed")
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("satisfy:Vehicle::TopSpeed", "verify:VerifyTopSpeed::TopSpeed")

            SampleOutput.write(
                "sysml2-layout-bridge/req-satisfy-and-verify.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "REQ with derive between two requirements" {
            val model =
                sysml2Model("Derive") {
                    val r1 = requirementDef("TopSpeed", reqId = "R-001")
                    val r2 = requirementDef("Fuel", reqId = "R-003")
                    reqDiagram("REQ") {
                        include(r1)
                        include(r2)
                        derive(r1, r2)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, req)
            graph.nodes shouldHaveSize 2
            graph.edges shouldHaveSize 1
            graph.edges
                .single()
                .id.value shouldBe "derive:TopSpeed::Fuel"
            graph.edges
                .single()
                .source.nodeId.value shouldBe "TopSpeed"
            graph.edges
                .single()
                .target.nodeId.value shouldBe "Fuel"

            SampleOutput.write(
                "sysml2-layout-bridge/req-derive.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "REQ with contains between parent and child requirement" {
            val model =
                sysml2Model("Contains") {
                    val parent = requirementDef("Emissions", reqId = "R-004")
                    val child = requirementDef("NOx", reqId = "R-005")
                    reqDiagram("REQ") {
                        include(parent)
                        include(child)
                        contains(parent, child)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, req)
            graph.nodes shouldHaveSize 2
            graph.edges shouldHaveSize 1
            graph.edges
                .single()
                .id.value shouldBe "contains:Emissions::NOx"
            graph.edges
                .single()
                .source.nodeId.value shouldBe "Emissions"
            graph.edges
                .single()
                .target.nodeId.value shouldBe "NOx"

            SampleOutput.write(
                "sysml2-layout-bridge/req-contains.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "REQ drops dangling edges silently" {
            // Edges to non-visible endpoints disappear; same skip-logic as BDD/IBD/UC.
            val r1 = RequirementDefinition(id = "R1", name = "R1")
            val ghost = RequirementDefinition(id = "Ghost", name = "Ghost")
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle")
            val verifier = UseCaseDefinition(id = "Verifier", name = "Verifier")
            val model = Sysml2Model(name = "Dangle", definitions = listOf(r1, ghost, vehicle, verifier))
            val req =
                ReqDiagram(
                    name = "REQ",
                    // ghost is NOT in elementIds → all edges referencing it dangle.
                    elementIds = listOf("R1", "Vehicle", "Verifier"),
                    satisfies =
                        listOf(
                            ReqSatisfy(id = "satisfy:Vehicle::R1", sourceId = "Vehicle", requirementId = "R1"),
                            ReqSatisfy(id = "satisfy:Vehicle::Ghost", sourceId = "Vehicle", requirementId = "Ghost"),
                        ),
                    verifies =
                        listOf(
                            ReqVerify(id = "verify:Verifier::R1", sourceId = "Verifier", requirementId = "R1"),
                            ReqVerify(id = "verify:Verifier::Ghost", sourceId = "Verifier", requirementId = "Ghost"),
                        ),
                    derives =
                        listOf(
                            ReqDerive(
                                id = "derive:R1::Ghost",
                                sourceRequirementId = "R1",
                                targetRequirementId = "Ghost",
                            ),
                        ),
                    contains =
                        listOf(
                            ReqContains(
                                id = "contains:Ghost::R1",
                                parentRequirementId = "Ghost",
                                childRequirementId = "R1",
                            ),
                        ),
                )
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, req)
            graph.nodes shouldHaveSize 3
            // Only the two edges with both endpoints visible survive.
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("satisfy:Vehicle::R1", "verify:Verifier::R1")
        }

        "REQ missing definitions are skipped silently" {
            val r1 = RequirementDefinition(id = "R1", name = "R1")
            val model = Sysml2Model(name = "M", definitions = listOf(r1))
            val req = ReqDiagram(name = "REQ", elementIds = listOf("R1", "NonExistent"))
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, req)
            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "R1"

            SampleOutput.write(
                "sysml2-layout-bridge/req-missing-definition.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "REQ default size for RequirementDefinition matches REQ_DEFAULT_WIDTH × REQ_DEFAULT_HEIGHT" {
            val model =
                sysml2Model("Sizes") {
                    val r = requirementDef("R1")
                    reqDiagram("REQ") {
                        include(r)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, req)
            graph.nodes
                .single()
                .intrinsicSize.width shouldBe Sysml2LayoutBridge.REQ_DEFAULT_WIDTH
            graph.nodes
                .single()
                .intrinsicSize.height shouldBe Sysml2LayoutBridge.REQ_DEFAULT_HEIGHT
        }

        // ── STM Diagram (V2.0.9) ──────────────────────────────────────────────

        "STM with three states + two transitions → 3 nodes + 2 edges" {
            val model =
                sysml2Model("Lights") {
                    val red = stateDef("Red")
                    val green = stateDef("Green")
                    val yellow = stateDef("Yellow")
                    transition("redToGreen", red, green, trigger = "timer60s")
                    transition("greenToYellow", green, yellow, trigger = "timer45s")
                    stmDiagram("Phase cycle") {
                        include(red)
                        include(green)
                        include(yellow)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, stm)
            graph.nodes shouldHaveSize 3
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("Red", "Green", "Yellow")
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("transition:Red::Green", "transition:Green::Yellow")

            SampleOutput.write(
                "sysml2-layout-bridge/stm-three-states-two-transitions.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "STM initial and final pseudo-states are sized as pseudo (24×24)" {
            val model =
                sysml2Model("PseudoSizes") {
                    val initial = stateDef("Initial", isInitial = true)
                    val red = stateDef("Red")
                    val final = stateDef("Final", isFinal = true)
                    transition("initial", initial, red)
                    transition("end", red, final)
                    stmDiagram("STM") {
                        include(initial)
                        include(red)
                        include(final)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, stm)

            val initialNode = graph.nodes.single { it.id.value == "Initial" }
            val finalNode = graph.nodes.single { it.id.value == "Final" }
            val redNode = graph.nodes.single { it.id.value == "Red" }
            initialNode.intrinsicSize.width shouldBe Sysml2LayoutBridge.STM_PSEUDO_SIZE
            initialNode.intrinsicSize.height shouldBe Sysml2LayoutBridge.STM_PSEUDO_SIZE
            finalNode.intrinsicSize.width shouldBe Sysml2LayoutBridge.STM_PSEUDO_SIZE
            finalNode.intrinsicSize.height shouldBe Sysml2LayoutBridge.STM_PSEUDO_SIZE
            redNode.intrinsicSize.width shouldBe Sysml2LayoutBridge.STM_STATE_WIDTH
            redNode.intrinsicSize.height shouldBe Sysml2LayoutBridge.STM_STATE_HEIGHT
        }

        "STM drops transitions to dangling states silently" {
            // Same skip-logic as BDD/IBD/UC/REQ: validator flags dangling refs,
            // bridge stays render-friendly.
            val red = StateDefinition(id = "Red", name = "Red")
            val green = StateDefinition(id = "Green", name = "Green")
            val ghost = StateDefinition(id = "Ghost", name = "Ghost")
            val model =
                Sysml2Model(
                    name = "Dangle",
                    definitions = listOf(red, green, ghost),
                    usages =
                        listOf(
                            TransitionUsage(
                                id = "transition:Red::Green",
                                name = "redToGreen",
                                sourceStateId = "Red",
                                targetStateId = "Green",
                            ),
                            TransitionUsage(
                                id = "transition:Green::Ghost",
                                name = "greenToGhost",
                                sourceStateId = "Green",
                                targetStateId = "Ghost",
                            ),
                            TransitionUsage(
                                id = "transition:Ghost::Red",
                                name = "ghostToRed",
                                sourceStateId = "Ghost",
                                targetStateId = "Red",
                            ),
                        ),
                )
            // Ghost NOT in elementIds → both edges referencing it dangle.
            val stm = StmDiagram(name = "STM", elementIds = listOf("Red", "Green"))

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, stm)
            graph.nodes shouldHaveSize 2
            graph.edges shouldHaveSize 1
            graph.edges
                .single()
                .id.value shouldBe "transition:Red::Green"

            SampleOutput.write(
                "sysml2-layout-bridge/stm-dangling-transitions.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "STM missing definitions are skipped silently" {
            val red = StateDefinition(id = "Red", name = "Red")
            val model = Sysml2Model(name = "M", definitions = listOf(red))
            val stm = StmDiagram(name = "STM", elementIds = listOf("Red", "NonExistent"))
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, stm)
            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "Red"

            SampleOutput.write(
                "sysml2-layout-bridge/stm-missing-definition.layout.json",
                prettyJson.encodeToString(graph),
            )
        }

        "STM transitions retain trigger/guard/effect via model.usages lookup" {
            // Bridge doesn't strip transition metadata — the SVG/LaTeX
            // renderer can still find it via the model.usages list (it lives
            // there, not on the diagram). Asserts that we kept the original
            // usage in model.usages after the bridge has built the layout
            // graph; the trigger label V2.x renderer will use this.
            val model =
                sysml2Model("Labels") {
                    val red = stateDef("Red")
                    val green = stateDef("Green")
                    transition(
                        name = "redToGreen",
                        source = red,
                        target = green,
                        trigger = "timer60s",
                        guard = "!emergency",
                        effect = "switchLights('green')",
                    )
                    stmDiagram("STM") {
                        include(red)
                        include(green)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model, stm)
            graph.edges shouldHaveSize 1
            graph.edges
                .single()
                .id.value shouldBe "transition:Red::Green"

            // The trigger/guard/effect survive on the usage in the model — the
            // bridge does not strip them; renderers (V2.x label polish) can
            // recover them via the lookup below.
            val t = model.usages.filterIsInstance<TransitionUsage>().single()
            t.trigger shouldBe "timer60s"
            t.guard shouldBe "!emergency"
            t.effect shouldBe "switchLights('green')"
        }

        "STM skips non-State definitions in elementIds silently (e.g. PartDefinition)" {
            val red = StateDefinition(id = "Red", name = "Red")
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle")
            val model = Sysml2Model(name = "M", definitions = listOf(red, vehicle))
            val stm = StmDiagram(name = "STM", elementIds = listOf("Red", "Vehicle"))

            val graph = Sysml2LayoutBridge.toLayoutGraph(model, stm)
            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "Red"
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
