package dev.kuml.layout.bridge

import dev.kuml.kerml.KermlSpecialization
import dev.kuml.layout.LayoutDirection
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.ReqContains
import dev.kuml.sysml2.ReqDerive
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.ReqSatisfy
import dev.kuml.sysml2.ReqVerify
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.SeqDiagram
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
import io.kotest.matchers.collections.shouldContainExactly
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

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = bdd)

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
                filename = "sysml2-layout-bridge/two-parts-one-specialisation.layout.json",
                content = prettyJson.encodeToString(graph),
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

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = bdd)

            graph.nodes shouldHaveSize 1
            graph.edges shouldHaveSize 0
        }

        "missing definitions are skipped silently (validator's job, not bridge's)" {
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle")
            val model = Sysml2Model(name = "Demo", definitions = listOf(vehicle))
            val bdd = BdDiagram(name = "Demo", elementIds = listOf("Vehicle", "NonExistent"))

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = bdd)

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
            Sysml2LayoutBridge.resolveVisibleDefinitions(model = model, diagram = bdd) shouldBe listOf(b, a)
        }

        "default size matches the announced 220×140 constants" {
            val v = PartDefinition(id = "V", name = "V")
            val model = Sysml2Model(name = "M", definitions = listOf(v))
            val bdd = BdDiagram(name = "D", elementIds = listOf("V"))
            val graph =
                Sysml2LayoutBridge.toLayoutGraph(
                    model = model,
                    diagram = bdd,
                    sizeProvider =
                        SizeProvider.constant(
                            width = Sysml2LayoutBridge.DEFAULT_WIDTH,
                            height = Sysml2LayoutBridge.DEFAULT_HEIGHT,
                        ),
                )
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
                sysml2Model(name = "M") {
                    val engineDef = partDef(name = "Engine")
                    val batteryDef = partDef(name = "Battery")
                    val powerLine = connectionDef(name = "PowerLine")
                    val vehicle =
                        partDef(name = "Vehicle") {
                            part(name = "engine", typeId = engineDef.id)
                            part(name = "battery", typeId = batteryDef.id)
                            connect(
                                name = "wiring",
                                typeId = powerLine.id,
                                sourceEndId = "Vehicle::engine::out",
                                targetEndId = "Vehicle::battery::in",
                            )
                        }
                    ibd(name = "Vehicle wiring", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)

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
                filename = "sysml2-layout-bridge/ibd-two-parts-one-connection.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "IBD with no part-usages → empty graph (nodes + edges both empty)" {
            val model =
                sysml2Model(name = "M") {
                    val empty = partDef(name = "EmptyShell")
                    ibd(name = "Empty shell", owner = empty)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)
            graph.nodes shouldHaveSize 0
            graph.edges shouldHaveSize 0

            SampleOutput.write(
                filename = "sysml2-layout-bridge/ibd-empty-shell.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "IBD with elementIds filter selects subset" {
            val model =
                sysml2Model(name = "M") {
                    val engineDef = partDef(name = "Engine")
                    val batteryDef = partDef(name = "Battery")
                    val controllerDef = partDef(name = "Controller")
                    val powerLine = connectionDef(name = "PowerLine")
                    val vehicle =
                        partDef(name = "Vehicle") {
                            part(name = "engine", typeId = engineDef.id)
                            part(name = "battery", typeId = batteryDef.id)
                            part(name = "controller", typeId = controllerDef.id)
                            connect(
                                name = "powerWire",
                                typeId = powerLine.id,
                                sourceEndId = "Vehicle::engine::out",
                                targetEndId = "Vehicle::battery::in",
                            )
                        }
                    ibd(name = "Power-train only", owner = vehicle) {
                        includeById("Vehicle::engine")
                        includeById("Vehicle::battery")
                    }
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)

            graph.nodes shouldHaveSize 2
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("Vehicle::engine", "Vehicle::battery")
            graph.edges shouldHaveSize 1

            SampleOutput.write(
                filename = "sysml2-layout-bridge/ibd-filtered-subset.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "IBD with connection to dangling endpoint → edge dropped" {
            val model =
                sysml2Model(name = "M") {
                    val engineDef = partDef(name = "Engine")
                    val batteryDef = partDef(name = "Battery")
                    val powerLine = connectionDef(name = "PowerLine")
                    val vehicle =
                        partDef(name = "Vehicle") {
                            part(name = "engine", typeId = engineDef.id)
                            part(name = "battery", typeId = batteryDef.id)
                            // Connection target points at a non-existent part-usage.
                            connect(
                                name = "dangling",
                                typeId = powerLine.id,
                                sourceEndId = "Vehicle::engine::out",
                                targetEndId = "Vehicle::nonexistent::in",
                            )
                        }
                    ibd(name = "Vehicle wiring (dangling)", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)
            graph.nodes shouldHaveSize 2
            // The dangling connection is silently dropped — validator's job to
            // flag this; bridge stays render-friendly.
            graph.edges shouldHaveSize 0

            SampleOutput.write(
                filename = "sysml2-layout-bridge/ibd-dangling-connection.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "IBD with missing owner → empty graph (validator's job, not bridge's)" {
            val model =
                sysml2Model(name = "M") {
                    partDef(name = "Vehicle")
                }
            // Hand-craft an IbdDiagram for a non-existent owner id.
            val ibd = IbdDiagram(name = "Ghost", ownerId = "NotInModel")
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)
            graph.nodes shouldHaveSize 0
            graph.edges shouldHaveSize 0
        }

        // ── UC Diagram (V2.0.7) ───────────────────────────────────────────────

        "UC with one actor + two use cases + association + include → 3 nodes + 2 edges" {
            val model =
                sysml2Model(name = "Library") {
                    val reader = actorDef(name = "Reader")
                    val borrow = useCaseDef(name = "BorrowBook")
                    val auth = useCaseDef(name = "Authenticate")
                    ucDiagram(name = "UC") {
                        include(reader)
                        include(borrow)
                        include(auth)
                        association(actor = reader, useCase = borrow)
                        include(source = borrow, target = auth)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = uc)
            graph.nodes shouldHaveSize 3
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("Reader", "BorrowBook", "Authenticate")
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("assoc:Reader::BorrowBook", "include:BorrowBook::Authenticate")

            SampleOutput.write(
                filename = "sysml2-layout-bridge/uc-library-association-and-include.layout.json",
                content = prettyJson.encodeToString(graph),
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

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = uc)
            graph.nodes shouldHaveSize 3
            // Only the two edges with both endpoints visible survive.
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("assoc:Reader::BorrowBook", "include:BorrowBook::Authenticate")
        }

        "UC respects actor vs use-case default sizes" {
            // toLayoutGraph's default sizeProvider is ucContentAwareSizeProvider(model);
            // these short names ("Reader", "BorrowBook") stay under the char budget that
            // would grow the box, so both still floor at their fixed default size.
            val model =
                sysml2Model(name = "Sizes") {
                    val reader = actorDef(name = "Reader")
                    val borrow = useCaseDef(name = "BorrowBook")
                    ucDiagram(name = "UC") {
                        include(reader)
                        include(borrow)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = uc)

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
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = uc)
            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "Reader"

            SampleOutput.write(
                filename = "sysml2-layout-bridge/uc-missing-definition.layout.json",
                content = prettyJson.encodeToString(graph),
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

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = uc)
            graph.nodes shouldHaveSize 2
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder listOf("Reader", "BorrowBook")
        }

        // ── REQ Diagram (V2.0.8) ──────────────────────────────────────────────

        "REQ with two requirements + one satisfy + one verify → nodes + 2 edges" {
            val model =
                sysml2Model(name = "VehicleReqs") {
                    val topSpeed = requirementDef(name = "TopSpeed", reqId = "R-001", text = "≥180 km/h")
                    requirementDef(name = "Fuel", reqId = "R-003", text = "≤4 l/100km")
                    val vehicle = partDef(name = "Vehicle")
                    val verifier = useCaseDef(name = "VerifyTopSpeed")
                    reqDiagram(name = "REQ") {
                        include(topSpeed)
                        include(vehicle)
                        include(verifier)
                        satisfy(source = vehicle, requirement = topSpeed)
                        verify(source = verifier, requirement = topSpeed)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = req)
            graph.nodes shouldHaveSize 3
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("TopSpeed", "Vehicle", "VerifyTopSpeed")
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("satisfy:Vehicle::TopSpeed", "verify:VerifyTopSpeed::TopSpeed")

            SampleOutput.write(
                filename = "sysml2-layout-bridge/req-satisfy-and-verify.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "REQ with derive between two requirements" {
            val model =
                sysml2Model(name = "Derive") {
                    val r1 = requirementDef(name = "TopSpeed", reqId = "R-001")
                    val r2 = requirementDef(name = "Fuel", reqId = "R-003")
                    reqDiagram(name = "REQ") {
                        include(r1)
                        include(r2)
                        derive(source = r1, target = r2)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = req)
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
                filename = "sysml2-layout-bridge/req-derive.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "REQ with contains between parent and child requirement" {
            val model =
                sysml2Model(name = "Contains") {
                    val parent = requirementDef(name = "Emissions", reqId = "R-004")
                    val child = requirementDef(name = "NOx", reqId = "R-005")
                    reqDiagram(name = "REQ") {
                        include(parent)
                        include(child)
                        contains(parent = parent, child = child)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = req)
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
                filename = "sysml2-layout-bridge/req-contains.layout.json",
                content = prettyJson.encodeToString(graph),
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
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = req)
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
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = req)
            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "R1"

            SampleOutput.write(
                filename = "sysml2-layout-bridge/req-missing-definition.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "REQ default size for RequirementDefinition matches REQ_DEFAULT_WIDTH × REQ_DEFAULT_HEIGHT" {
            val model =
                sysml2Model(name = "Sizes") {
                    val r = requirementDef(name = "R1")
                    reqDiagram(name = "REQ") {
                        include(r)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val graph =
                Sysml2LayoutBridge.toLayoutGraph(
                    model = model,
                    diagram = req,
                    sizeProvider = Sysml2LayoutBridge.reqDefaultSizeProvider(),
                )
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
                sysml2Model(name = "Lights") {
                    val red = stateDef(name = "Red")
                    val green = stateDef(name = "Green")
                    val yellow = stateDef(name = "Yellow")
                    transition(name = "redToGreen", source = red, target = green, trigger = "timer60s")
                    transition(name = "greenToYellow", source = green, target = yellow, trigger = "timer45s")
                    stmDiagram(name = "Phase cycle") {
                        include(red)
                        include(green)
                        include(yellow)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm)
            graph.nodes shouldHaveSize 3
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("Red", "Green", "Yellow")
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("transition:Red::Green", "transition:Green::Yellow")

            SampleOutput.write(
                filename = "sysml2-layout-bridge/stm-three-states-two-transitions.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "STM initial and final pseudo-states are sized as pseudo (24×24)" {
            val model =
                sysml2Model(name = "PseudoSizes") {
                    val initial = stateDef(name = "Initial", isInitial = true)
                    val red = stateDef(name = "Red")
                    val final = stateDef(name = "Final", isFinal = true)
                    transition(name = "initial", source = initial, target = red)
                    transition(name = "end", source = red, target = final)
                    stmDiagram(name = "STM") {
                        include(initial)
                        include(red)
                        include(final)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            // Use fixed default size provider to keep this test as a regression guard
            // for the constant values (content-aware sizing is tested separately).
            val graph =
                Sysml2LayoutBridge.toLayoutGraph(
                    model = model,
                    diagram = stm,
                    sizeProvider = Sysml2LayoutBridge.stmDefaultSizeProvider(),
                )

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

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm)
            graph.nodes shouldHaveSize 2
            graph.edges shouldHaveSize 1
            graph.edges
                .single()
                .id.value shouldBe "transition:Red::Green"

            SampleOutput.write(
                filename = "sysml2-layout-bridge/stm-dangling-transitions.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "STM missing definitions are skipped silently" {
            val red = StateDefinition(id = "Red", name = "Red")
            val model = Sysml2Model(name = "M", definitions = listOf(red))
            val stm = StmDiagram(name = "STM", elementIds = listOf("Red", "NonExistent"))
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm)
            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "Red"

            SampleOutput.write(
                filename = "sysml2-layout-bridge/stm-missing-definition.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "STM transitions retain trigger/guard/effect via model.usages lookup" {
            // Bridge doesn't strip transition metadata — the SVG/LaTeX
            // renderer can still find it via the model.usages list (it lives
            // there, not on the diagram). Asserts that we kept the original
            // usage in model.usages after the bridge has built the layout
            // graph; the trigger label V2.x renderer will use this.
            val model =
                sysml2Model(name = "Labels") {
                    val red = stateDef(name = "Red")
                    val green = stateDef(name = "Green")
                    transition(
                        name = "redToGreen",
                        source = red,
                        target = green,
                        trigger = "timer60s",
                        guard = "!emergency",
                        effect = "switchLights('green')",
                    )
                    stmDiagram(name = "STM") {
                        include(red)
                        include(green)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm)
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

        // V2.x — Renderer-Sizing-Heuristik für STM-States: Edge-Fan-Puffer
        // (CLAUDE.md "Knotengröße ∝ Anzahl Anschluss-Kanten").

        "STM regular state grows in width by N×puffer per anliegender Transition (TopToBottom)" {
            // Traffic-Light-Reproduktion: Red hat 4 anliegende Kanten
            // (Initial→Red, Red→Green, Yellow→Red, Red→Off).
            val model =
                sysml2Model(name = "TrafficLight") {
                    val initial = stateDef(name = "Initial", isInitial = true)
                    val red = stateDef(name = "Red")
                    val green = stateDef(name = "Green")
                    val yellow = stateDef(name = "Yellow")
                    val off = stateDef(name = "Off", isFinal = true)
                    transition(name = "init", source = initial, target = red)
                    transition(name = "redToGreen", source = red, target = green, trigger = "timer60s")
                    transition(name = "yellowToRed", source = yellow, target = red, trigger = "timer5s")
                    transition(name = "powerOff", source = red, target = off, trigger = "powerOff")
                    transition(name = "greenToYellow", source = green, target = yellow, trigger = "timer45s")
                    stmDiagram(name = "Phase cycle") {
                        include(initial)
                        include(red)
                        include(green)
                        include(yellow)
                        include(off)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm)

            val redNode = graph.nodes.single { it.id.value == "Red" }
            // 4 Transitionen → 4×14 = 56 px Breitenzuwachs.
            redNode.intrinsicSize.width shouldBe
                Sysml2LayoutBridge.STM_STATE_WIDTH + 4 * Sysml2LayoutBridge.STM_CONNECTION_PUFFER_PX
            // Höhe bleibt content-getrieben — `Red` hat hier keine Actions,
            // also fällt sie auf den content-aware Mindestwert (44 px) zurück.
            redNode.intrinsicSize.height shouldBe 44f
        }

        "STM pseudo-states ignore the edge-fan puffer (stay 24×24)" {
            // Selbst wenn ein Pseudo-State viele Transitionen hat — Marker
            // dürfen visuell nicht zur regulären Box anwachsen.
            val model =
                sysml2Model(name = "PseudoFan") {
                    val initial = stateDef(name = "Initial", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    val c = stateDef(name = "C")
                    transition(name = "toA", source = initial, target = a)
                    transition(name = "toB", source = initial, target = b)
                    transition(name = "toC", source = initial, target = c)
                    stmDiagram(name = "STM") {
                        include(initial)
                        include(a)
                        include(b)
                        include(c)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm)

            val initialNode = graph.nodes.single { it.id.value == "Initial" }
            initialNode.intrinsicSize.width shouldBe Sysml2LayoutBridge.STM_PSEUDO_SIZE
            initialNode.intrinsicSize.height shouldBe Sysml2LayoutBridge.STM_PSEUDO_SIZE
        }

        "STM self-transition counts twice (both endpoints on the same box)" {
            val model =
                sysml2Model(name = "SelfLoop") {
                    val s = stateDef(name = "S")
                    transition(name = "loop", source = s, target = s, trigger = "tick")
                    stmDiagram(name = "STM") { include(s) }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm)

            val sNode = graph.nodes.single { it.id.value == "S" }
            // 1 Self-Loop → 2 Endpunkt-Bumps → 2×14 = 28 px Breite extra.
            sNode.intrinsicSize.width shouldBe
                Sysml2LayoutBridge.STM_STATE_WIDTH + 2 * Sysml2LayoutBridge.STM_CONNECTION_PUFFER_PX
        }

        "STM edge-fan puffer is capped at STM_CONNECTION_PUFFER_MAX_PX" {
            // 12 anliegende Transitionen → 12×14 = 168 px, gedeckelt auf
            // STM_CONNECTION_PUFFER_MAX_PX (112 px). Verhindert, dass Hub-
            // States visuell aufblähen.
            val model =
                sysml2Model(name = "Hub") {
                    val hub = stateDef(name = "Hub")
                    val states = (1..12).map { stateDef(name = "S$it") }
                    states.forEachIndexed { idx, s -> transition(name = "to$idx", source = hub, target = s) }
                    stmDiagram(name = "STM") {
                        include(hub)
                        states.forEach { include(it) }
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm)

            val hubNode = graph.nodes.single { it.id.value == "Hub" }
            hubNode.intrinsicSize.width shouldBe
                Sysml2LayoutBridge.STM_STATE_WIDTH + Sysml2LayoutBridge.STM_CONNECTION_PUFFER_MAX_PX
        }

        "STM edge-fan puffer in LeftToRight direction grows height instead of width" {
            val model =
                sysml2Model(name = "HorizontalLayout") {
                    val red = stateDef(name = "Red")
                    val green = stateDef(name = "Green")
                    val yellow = stateDef(name = "Yellow")
                    val off = stateDef(name = "Off", isFinal = true)
                    transition(name = "rg", source = red, target = green)
                    transition(name = "yr", source = yellow, target = red)
                    transition(name = "ro", source = red, target = off)
                    stmDiagram(name = "STM") {
                        include(red)
                        include(green)
                        include(yellow)
                        include(off)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val provider =
                Sysml2LayoutBridge.stmContentAwareSizeProvider(
                    model = model,
                    diagram = stm,
                    layoutDirection = LayoutDirection.LeftToRight,
                )
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm, sizeProvider = provider)

            val redNode = graph.nodes.single { it.id.value == "Red" }
            // Red hat 3 Edges → Höhe wächst um 3×14 = 42 px ausgehend von
            // der content-aware Basis-Höhe (44 px ohne Actions); Breite
            // bleibt unverändert.
            redNode.intrinsicSize.width shouldBe Sysml2LayoutBridge.STM_STATE_WIDTH
            redNode.intrinsicSize.height shouldBe 44f + 3 * Sysml2LayoutBridge.STM_CONNECTION_PUFFER_PX
        }

        "STM single-arg stmContentAwareSizeProvider stays fan-puffer-free (backwards compat)" {
            // Wer den alten Single-Arg-Provider explizit übergibt, soll
            // unverändertes V2.0.9-Verhalten bekommen — sonst würde jedes
            // Bestandsbild plötzlich anders aussehen.
            val model =
                sysml2Model(name = "LegacyShape") {
                    val red = stateDef(name = "Red")
                    val green = stateDef(name = "Green")
                    val yellow = stateDef(name = "Yellow")
                    val off = stateDef(name = "Off", isFinal = true)
                    transition(name = "rg", source = red, target = green)
                    transition(name = "yr", source = yellow, target = red)
                    transition(name = "ro", source = red, target = off)
                    stmDiagram(name = "STM") {
                        include(red)
                        include(green)
                        include(yellow)
                        include(off)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val legacyProvider = Sysml2LayoutBridge.stmContentAwareSizeProvider(model)
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm, sizeProvider = legacyProvider)

            val redNode = graph.nodes.single { it.id.value == "Red" }
            redNode.intrinsicSize.width shouldBe Sysml2LayoutBridge.STM_STATE_WIDTH
            // Content-aware Basis-Höhe ohne Actions (gleiches Verhalten
            // wie V2.0.9): 44 px Mindesthöhe.
            redNode.intrinsicSize.height shouldBe 44f
        }

        "STM skips non-State definitions in elementIds silently (e.g. PartDefinition)" {
            val red = StateDefinition(id = "Red", name = "Red")
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle")
            val model = Sysml2Model(name = "M", definitions = listOf(red, vehicle))
            val stm = StmDiagram(name = "STM", elementIds = listOf("Red", "Vehicle"))

            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = stm)
            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "Red"
        }

        // ── ACT Diagram (V2.0.10) ─────────────────────────────────────────────

        "ACT with action + decision + two control flows → 3 nodes + 2 edges" {
            val model =
                sysml2Model(name = "Workflow") {
                    val validate = actionDef(name = "Validate")
                    val decide = decisionNode(name = "Valid?")
                    val process = actionDef(name = "Process")
                    controlFlow(name = "vToD", source = validate, target = decide)
                    controlFlow(name = "dToP", source = decide, target = process, guard = "valid")
                    actDiagram(name = "Pipeline") {
                        include(validate)
                        include(decide)
                        include(process)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act)
            graph.nodes shouldHaveSize 3
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("Validate", "Valid?", "Process")
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("controlFlow:Validate::Valid?", "controlFlow:Valid?::Process")

            SampleOutput.write(
                filename = "sysml2-layout-bridge/act-action-decision-two-flows.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "ACT initial/final/flowFinal pseudo-nodes are sized as pseudo (28×28)" {
            val model =
                sysml2Model(name = "Pseudo") {
                    val initial = initialNode()
                    val finalN = finalNode()
                    val ff = flowFinalNode()
                    actDiagram(name = "ACT") {
                        include(initial)
                        include(finalN)
                        include(ff)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act)
            graph.nodes shouldHaveSize 3
            for (n in graph.nodes) {
                n.intrinsicSize.width shouldBe Sysml2LayoutBridge.ACT_PSEUDO_SIZE
                n.intrinsicSize.height shouldBe Sysml2LayoutBridge.ACT_PSEUDO_SIZE
            }

            SampleOutput.write(
                filename = "sysml2-layout-bridge/act-pseudo-sizes.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "ACT decision/merge nodes are sized as diamond (50×50)" {
            val model =
                sysml2Model(name = "Diamonds") {
                    val d = decisionNode(name = "Valid?")
                    val m = mergeNode(name = "Joined")
                    actDiagram(name = "ACT") {
                        include(d)
                        include(m)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act)
            for (n in graph.nodes) {
                n.intrinsicSize.width shouldBe Sysml2LayoutBridge.ACT_DIAMOND_WIDTH
                n.intrinsicSize.height shouldBe Sysml2LayoutBridge.ACT_DIAMOND_HEIGHT
            }

            SampleOutput.write(
                filename = "sysml2-layout-bridge/act-diamond-sizes.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "ACT fork/join nodes are sized as bar (120×10)" {
            val model =
                sysml2Model(name = "Bars") {
                    val f = forkNode(name = "Split")
                    val j = joinNode(name = "Sync")
                    actDiagram(name = "ACT") {
                        include(f)
                        include(j)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act)
            for (n in graph.nodes) {
                n.intrinsicSize.width shouldBe Sysml2LayoutBridge.ACT_BAR_WIDTH
                n.intrinsicSize.height shouldBe Sysml2LayoutBridge.ACT_BAR_HEIGHT
            }

            SampleOutput.write(
                filename = "sysml2-layout-bridge/act-bar-sizes.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "ACT drops control flows to dangling endpoints silently" {
            val a = ActionDefinition(id = "A", name = "A")
            val b = ActionDefinition(id = "B", name = "B")
            val ghost = ActionDefinition(id = "Ghost", name = "Ghost")
            val model =
                Sysml2Model(
                    name = "Dangle",
                    definitions = listOf(a, b, ghost),
                    usages =
                        listOf(
                            ControlFlowUsage(
                                id = "controlFlow:A::B",
                                name = "aToB",
                                sourceNodeId = "A",
                                targetNodeId = "B",
                            ),
                            ControlFlowUsage(
                                id = "controlFlow:B::Ghost",
                                name = "bToGhost",
                                sourceNodeId = "B",
                                targetNodeId = "Ghost",
                            ),
                            ControlFlowUsage(
                                id = "controlFlow:Ghost::A",
                                name = "ghostToA",
                                sourceNodeId = "Ghost",
                                targetNodeId = "A",
                            ),
                        ),
                )
            val act = ActDiagram(name = "ACT", elementIds = listOf("A", "B"))
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act)
            graph.nodes shouldHaveSize 2
            graph.edges shouldHaveSize 1
            graph.edges
                .single()
                .id.value shouldBe "controlFlow:A::B"

            SampleOutput.write(
                filename = "sysml2-layout-bridge/act-dangling-flows.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "ACT object flow retains objectType via model.usages lookup" {
            val model =
                sysml2Model(name = "Carry") {
                    val a = actionDef(name = "Validate")
                    val b = actionDef(name = "Process")
                    objectFlow(name = "carry", source = a, target = b, objectType = "Order")
                    actDiagram(name = "ACT") {
                        include(a)
                        include(b)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act)
            graph.edges shouldHaveSize 1
            graph.edges
                .single()
                .id.value shouldBe "objectFlow:Validate::Process"

            // objectType survives on the usage in the model — V2.x label polish
            // recovers it via the lookup below.
            val flow = model.usages.filterIsInstance<ObjectFlowUsage>().single()
            flow.objectType shouldBe "Order"

            SampleOutput.write(
                filename = "sysml2-layout-bridge/act-object-flow.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "ACT regular action sized as ACT_ACTION_WIDTH × ACT_ACTION_HEIGHT" {
            val model =
                sysml2Model(name = "M") {
                    val a = actionDef(name = "Act", action = "x()")
                    actDiagram(name = "D") {
                        include(a)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act)
            graph.nodes
                .single()
                .intrinsicSize.width shouldBe Sysml2LayoutBridge.ACT_ACTION_WIDTH
            graph.nodes
                .single()
                .intrinsicSize.height shouldBe Sysml2LayoutBridge.ACT_ACTION_HEIGHT
            // ActivityNodeKind enum-name matching against size-provider hint.
            ActivityNodeKind.Action.name shouldBe "Action"
        }

        // ── V2.0.16 ACT Partitions ────────────────────────────────────────

        "ACT with 2 partitions and 3 actions emits 2 groups + 3 nodes" {
            val model =
                sysml2Model(name = "OrderProcessing") {
                    val customer = activityPartition(name = "Customer")
                    val warehouse = activityPartition(name = "Warehouse")
                    val place = actionDef(name = "PlaceOrder", partition = customer)
                    val reserve = actionDef(name = "ReserveInventory", partition = warehouse)
                    val ship = actionDef(name = "ShipOrder", partition = warehouse)
                    actDiagram(name = "Workflow") {
                        // V2.0.16: Partitions are auto-included via the
                        // partitionId reference on the action node — no
                        // explicit `include(...)` needed. Including them
                        // explicitly via `includeById(...)` is also valid
                        // (the bridge dedupes), but we exercise the implicit
                        // path here.
                        include(place)
                        include(reserve)
                        include(ship)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act)
            // Action nodes only — partitions are NOT layout nodes.
            graph.nodes shouldHaveSize 3
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("PlaceOrder", "ReserveInventory", "ShipOrder")
            // Two groups in DSL-declaration order.
            graph.groups shouldHaveSize 2
            graph.groups.map { it.id.value } shouldContainExactly listOf("Customer", "Warehouse")
            // groupId on each LayoutNode matches the partition assignment.
            val nodesById = graph.nodes.associateBy { it.id.value }
            nodesById.getValue("PlaceOrder").groupId?.value shouldBe "Customer"
            nodesById.getValue("ReserveInventory").groupId?.value shouldBe "Warehouse"
            nodesById.getValue("ShipOrder").groupId?.value shouldBe "Warehouse"

            SampleOutput.write(
                filename = "sysml2-layout-bridge/act-partitions-three-actions.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "ACT actions without partitionId stay outside groups" {
            val model =
                sysml2Model(name = "Standalone") {
                    val a = actionDef(name = "FreeAction")
                    actDiagram(name = "D") {
                        include(a)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act)
            graph.nodes shouldHaveSize 1
            graph.nodes.single().groupId shouldBe null
            graph.groups shouldHaveSize 0
        }

        "ACT with partitionId referencing missing partition has the node outside groups (silent)" {
            // The action carries a partitionId, but the referenced
            // ActivityPartitionDefinition does NOT exist in the model — the
            // bridge silently renders the node outside any lane (validator's
            // job to flag the dangling reference). The MVP achieves this by
            // constructing the ActionDefinition directly (the DSL would
            // resolve through the partition reference and fail at compile
            // time on a missing partition).
            val model =
                sysml2Model(name = "Dangling") {
                    val a =
                        actionDef(name = "OrphanAction") // No partition reference via DSL.
                    actDiagram(name = "D") {
                        include(a)
                    }
                }
            // Inject a partitionId on the action by replacing it in a fresh
            // Sysml2Model — the DSL doesn't support dangling refs by design,
            // so the test mutates the model directly.
            val actionWithDangling =
                model.definitions
                    .filterIsInstance<ActionDefinition>()
                    .single()
                    .copy(partitionId = "DoesNotExist")
            val mutated =
                Sysml2Model(
                    name = model.name,
                    definitions = listOf(actionWithDangling),
                    usages = model.usages,
                    diagrams = model.diagrams,
                )
            val act = mutated.diagrams.filterIsInstance<ActDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = mutated, diagram = act)
            graph.nodes shouldHaveSize 1
            graph.nodes.single().groupId shouldBe null
            // No partition exists in the model → no groups.
            graph.groups shouldHaveSize 0
        }

        // ── V2.0.11 SEQ ─────────────────────────────────────────────────

        "SEQ with three lifelines and four messages → 3 nodes, 0 edges" {
            val model =
                sysml2Model(name = "SeqDemo") {
                    val user = lifelineDef(name = "user")
                    val browser = lifelineDef(name = "browser")
                    val auth = lifelineDef(name = "authService")
                    message(label = "enterCredentials", source = user, target = browser, seqNo = 0)
                    message(label = "login", source = browser, target = auth, seqNo = 1)
                    message(label = "sessionToken", source = auth, target = browser, seqNo = 2, kind = MessageKind.Reply)
                    message(label = "welcomeScreen", source = browser, target = user, seqNo = 3, kind = MessageKind.Reply)
                    seqDiagram(name = "Login flow") {
                        include(user)
                        include(browser)
                        include(auth)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)

            graph.nodes shouldHaveSize 3
            graph.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("user", "browser", "authService")
            // Crucially: SEQ produces NO edges — messages are renderer-direct.
            graph.edges shouldHaveSize 0
            // Visible messages still live on the model for the renderer.
            model.usages.filterIsInstance<MessageUsage>() shouldHaveSize 4

            SampleOutput.write(
                filename = "sysml2-layout-bridge/seq-three-lifelines-four-messages.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "SEQ lifeline height scales with max seqNo" {
            val model =
                sysml2Model(name = "SeqHeights") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    // maxSeqNo = 5 → rowCount = 6 → 6+1 message rows of vertical space
                    message(label = "m0", source = a, target = b, seqNo = 0)
                    message(label = "m1", source = b, target = a, seqNo = 1)
                    message(label = "m2", source = a, target = b, seqNo = 2)
                    message(label = "m3", source = b, target = a, seqNo = 3)
                    message(label = "m4", source = a, target = b, seqNo = 4)
                    message(label = "m5", source = b, target = a, seqNo = 5)
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)

            // height = HEAD (40) + (5+1+1) * 32 + TAIL (40) = 40 + 224 + 40 = 304
            val expected =
                Sysml2LayoutBridge.SEQ_LIFELINE_HEAD_HEIGHT +
                    (6 + 1) * Sysml2LayoutBridge.SEQ_MESSAGE_ROW_HEIGHT +
                    Sysml2LayoutBridge.SEQ_LIFELINE_TAIL_PADDING
            for (n in graph.nodes) {
                n.intrinsicSize.width shouldBe Sysml2LayoutBridge.SEQ_LIFELINE_WIDTH
                n.intrinsicSize.height shouldBe expected
            }

            SampleOutput.write(
                filename = "sysml2-layout-bridge/seq-height-scales-with-seqno.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "SEQ with no messages → minimum lifeline height (head + tail only)" {
            val model =
                sysml2Model(name = "SeqEmpty") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)

            // rowCount = 0 → height = HEAD + 1*ROW + TAIL  (one row of breathing space)
            val expected =
                Sysml2LayoutBridge.SEQ_LIFELINE_HEAD_HEIGHT +
                    1 * Sysml2LayoutBridge.SEQ_MESSAGE_ROW_HEIGHT +
                    Sysml2LayoutBridge.SEQ_LIFELINE_TAIL_PADDING
            graph.nodes shouldHaveSize 2
            for (n in graph.nodes) {
                n.intrinsicSize.height shouldBe expected
            }
            graph.edges shouldHaveSize 0
        }

        "SEQ missing lifelines are skipped silently" {
            val model =
                sysml2Model(name = "SeqMissing") {
                    val a = lifelineDef(name = "a")
                    seqDiagram(name = "S") {
                        include(a)
                        includeById("ghost") // not declared
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)

            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "a"
        }

        "SEQ messages to dangling lifelines do not crash height calculation" {
            // A message references a lifeline that is not in the SEQ's element set.
            // The bridge must ignore that message for the maxSeqNo calculation
            // — only visible-pair messages contribute to the lifeline height.
            val a = LifelineDefinition(id = "a", name = "a")
            val b = LifelineDefinition(id = "b", name = "b")
            val ghost = LifelineDefinition(id = "ghost", name = "ghost")
            val model =
                Sysml2Model(
                    name = "Dangle",
                    definitions = listOf(a, b, ghost),
                    usages =
                        listOf(
                            MessageUsage(
                                id = "message:a-b-0",
                                name = "visible",
                                sourceLifelineId = "a",
                                targetLifelineId = "b",
                                seqNo = 0,
                                messageLabel = "visible",
                            ),
                            MessageUsage(
                                id = "message:a-ghost-99",
                                name = "dangling",
                                sourceLifelineId = "a",
                                targetLifelineId = "ghost",
                                seqNo = 99,
                                messageLabel = "dangling",
                            ),
                        ),
                )
            val seq = SeqDiagram(name = "S", elementIds = listOf("a", "b"))
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)

            // maxSeqNo = 0 (only visible pair) → rowCount = 1 → height = HEAD + 2*ROW + TAIL
            val expected =
                Sysml2LayoutBridge.SEQ_LIFELINE_HEAD_HEIGHT +
                    2 * Sysml2LayoutBridge.SEQ_MESSAGE_ROW_HEIGHT +
                    Sysml2LayoutBridge.SEQ_LIFELINE_TAIL_PADDING
            graph.nodes shouldHaveSize 2
            for (n in graph.nodes) {
                n.intrinsicSize.height shouldBe expected
            }
            graph.edges shouldHaveSize 0

            SampleOutput.write(
                filename = "sysml2-layout-bridge/seq-dangling-messages-ignored.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        // ───────────────── V2.0.15 SEQ — CF + ExecSpec height ────────────────

        "SEQ lifeline height extends to accommodate a combined fragment beyond the last message" {
            // The last message is at seqNo=2, but the fragment's operand
            // covers seqNo 1..5 → the lifeline must be tall enough for the
            // fragment's endSeqNo, not just the last-message seqNo.
            val model =
                sysml2Model(name = "CFHeight") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "ping", source = a, target = b, seqNo = 1)
                    message(label = "pong", source = b, target = a, seqNo = 2)
                    combinedFragment(name = "loopBlock", operator = CombinedFragmentOperator.Loop, startSeqNo = 1, endSeqNo = 5)
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)

            // maxSeqNo = 5 (from CF) → rowCount = 6 → height = HEAD + (6+1)*ROW + TAIL.
            // The loop fragment has one operand → one header band is added on top
            // so the operand guard clears its first message (SEQ_FRAGMENT_HEADER_BAND).
            val expected =
                Sysml2LayoutBridge.SEQ_LIFELINE_HEAD_HEIGHT +
                    (5 + 1 + 1) * Sysml2LayoutBridge.SEQ_MESSAGE_ROW_HEIGHT +
                    1 * Sysml2LayoutBridge.SEQ_FRAGMENT_HEADER_BAND +
                    Sysml2LayoutBridge.SEQ_LIFELINE_TAIL_PADDING
            for (n in graph.nodes) {
                n.intrinsicSize.height shouldBe expected
            }
            graph.edges shouldHaveSize 0

            SampleOutput.write(
                filename = "sysml2-layout-bridge/seq-height-extended-by-fragment.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "SEQ lifeline height extends to accommodate an execution spec beyond the last message" {
            val model =
                sysml2Model(name = "ESHeight") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "ping", source = a, target = b, seqNo = 1)
                    executionSpec(name = "activeB", lifeline = b, startSeqNo = 1, endSeqNo = 7)
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)

            // maxSeqNo = 7 (from ExecSpec) → rowCount = 8 → height = HEAD + (8+1)*ROW + TAIL
            val expected =
                Sysml2LayoutBridge.SEQ_LIFELINE_HEAD_HEIGHT +
                    (7 + 1 + 1) * Sysml2LayoutBridge.SEQ_MESSAGE_ROW_HEIGHT +
                    Sysml2LayoutBridge.SEQ_LIFELINE_TAIL_PADDING
            for (n in graph.nodes) {
                n.intrinsicSize.height shouldBe expected
            }

            SampleOutput.write(
                filename = "sysml2-layout-bridge/seq-height-extended-by-execspec.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "SEQ with fragment AND messages picks the larger of the two maxima" {
            // Messages reach seqNo=10 — that's the dominating maximum.
            // The fragment covers only 1..3. Height must follow messages.
            val model =
                sysml2Model(name = "MaxMix") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    for (i in 0..10) {
                        message(label = "m$i", source = if (i % 2 == 0) a else b, target = if (i % 2 == 0) b else a, seqNo = i)
                    }
                    combinedFragment(name = "smallFrag", operator = CombinedFragmentOperator.Opt, startSeqNo = 1, endSeqNo = 3)
                    executionSpec(name = "activeShort", lifeline = a, startSeqNo = 0, endSeqNo = 2)
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)

            // maxSeqNo = 10 (message > fragment 3 > execspec 2) → rowCount = 11.
            // The opt fragment has one operand → one header band is added on top
            // (SEQ_FRAGMENT_HEADER_BAND) so the operand guard clears its first message.
            val expected =
                Sysml2LayoutBridge.SEQ_LIFELINE_HEAD_HEIGHT +
                    (10 + 1 + 1) * Sysml2LayoutBridge.SEQ_MESSAGE_ROW_HEIGHT +
                    1 * Sysml2LayoutBridge.SEQ_FRAGMENT_HEADER_BAND +
                    Sysml2LayoutBridge.SEQ_LIFELINE_TAIL_PADDING
            for (n in graph.nodes) {
                n.intrinsicSize.height shouldBe expected
            }

            SampleOutput.write(
                filename = "sysml2-layout-bridge/seq-height-picks-larger-max.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        // ───────────────────────────── V2.0.12 PAR ────────────────────────────

        "PAR with one constraint + one part + two bindings → 2 nodes + 2 edges" {
            val model =
                sysml2Model(name = "PARTwoBindings") {
                    val mass = attributeDef(name = "Mass")
                    val accel = attributeDef(name = "Acceleration")
                    val newton =
                        constraintDef(
                            name = "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "m", typeId = mass.id, direction = ConstraintParameterDirection.In),
                                    ConstraintParameter(name = "a", typeId = accel.id, direction = ConstraintParameterDirection.In),
                                ),
                        )
                    val vehicle =
                        partDef(name = "Vehicle") {
                            attribute(name = "mass", typeId = mass.id)
                            attribute(name = "acceleration", typeId = accel.id)
                        }
                    bind(name = "bindMass", source = "NewtonsLaw::m", target = "Vehicle::mass")
                    bind(name = "bindAccel", source = "NewtonsLaw::a", target = "Vehicle::acceleration")
                    parDiagram(name = "PAR") {
                        include(newton)
                        include(vehicle)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = par)
            graph.nodes shouldHaveSize 2
            graph.edges shouldHaveSize 2
            graph.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf(
                    "binding:NewtonsLaw::m::Vehicle::mass",
                    "binding:NewtonsLaw::a::Vehicle::acceleration",
                )
            SampleOutput.write(
                filename = "sysml2-layout-bridge/par-newton-two-bindings.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "PAR constraint sized content-aware (short name/expression floors at PAR_CONSTRAINT_WIDTH, height from compartment count)" {
            // toLayoutGraph's default sizeProvider is parContentAwareSizeProvider(model)
            // — width floors at PAR_CONSTRAINT_WIDTH for short content (name/expression
            // here are well under the char budget that would grow it), height is
            // computed from the actual compartment count (stereotype + name + one
            // expression line, no parameters) rather than the old fixed
            // PAR_CONSTRAINT_HEIGHT, which over-allocated for constraints this short.
            val model =
                sysml2Model(name = "PARSize") {
                    val newton = constraintDef(name = "NewtonsLaw", expression = "F = m * a")
                    parDiagram(name = "PAR") { include(newton) }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = par)
            val node = graph.nodes.single()
            node.intrinsicSize.width shouldBe Sysml2LayoutBridge.PAR_CONSTRAINT_WIDTH
            node.intrinsicSize.height shouldBe 70f
            SampleOutput.write(
                filename = "sysml2-layout-bridge/par-constraint-default-size.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "PAR drops bindings to dangling endpoints" {
            val newton =
                ConstraintDefinition(
                    id = "NewtonsLaw",
                    name = "NewtonsLaw",
                    expression = "F = m * a",
                    parameters = listOf(ConstraintParameter(name = "m", direction = ConstraintParameterDirection.In)),
                )
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle")
            val model =
                Sysml2Model(
                    name = "PARDangling",
                    definitions = listOf(newton, vehicle),
                    usages =
                        listOf(
                            BindingConnectorUsage(
                                id = "binding:NewtonsLaw::m::Vehicle::mass",
                                name = "bindMass",
                                sourceEndId = "NewtonsLaw::m",
                                targetEndId = "Vehicle::mass",
                            ),
                            BindingConnectorUsage(
                                id = "binding:NewtonsLaw::m::Ghost::x",
                                name = "bindGhost",
                                sourceEndId = "NewtonsLaw::m",
                                targetEndId = "Ghost::x",
                            ),
                        ),
                )
            val par = ParDiagram(name = "PAR", elementIds = listOf("NewtonsLaw", "Vehicle"))
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = par)
            graph.nodes shouldHaveSize 2
            graph.edges shouldHaveSize 1
            graph.edges
                .single()
                .id.value shouldBe "binding:NewtonsLaw::m::Vehicle::mass"
            SampleOutput.write(
                filename = "sysml2-layout-bridge/par-dangling-bindings-dropped.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "PAR missing definitions are skipped silently" {
            val model =
                sysml2Model(name = "PARMissing") {
                    val newton = constraintDef(name = "NewtonsLaw", expression = "F = m * a")
                    parDiagram(name = "PAR") {
                        include(newton)
                        includeById("DoesNotExist")
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = par)
            graph.nodes shouldHaveSize 1
            graph.nodes
                .single()
                .id.value shouldBe "NewtonsLaw"
            SampleOutput.write(
                filename = "sysml2-layout-bridge/par-missing-definitions-skipped.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "PAR longest-prefix-match resolves `Vehicle::force` to Vehicle node" {
            val model =
                sysml2Model(name = "PARLongestPrefix") {
                    val force = attributeDef(name = "Force")
                    val newton =
                        constraintDef(
                            name = "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "F", typeId = force.id, direction = ConstraintParameterDirection.Out),
                                ),
                        )
                    val vehicle =
                        partDef(name = "Vehicle") {
                            attribute(name = "force", typeId = force.id)
                        }
                    // Endpoint id `Vehicle::force` does not exist as a top-level
                    // element — the longest-prefix-match must resolve it to
                    // the visible `Vehicle` node.
                    bind(name = "bindForce", source = "NewtonsLaw::F", target = "Vehicle::force")
                    parDiagram(name = "PAR") {
                        include(newton)
                        include(vehicle)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = par)
            graph.edges shouldHaveSize 1
            val edge = graph.edges.single()
            edge.source.nodeId.value shouldBe "NewtonsLaw"
            edge.target.nodeId.value shouldBe "Vehicle"
            SampleOutput.write(
                filename = "sysml2-layout-bridge/par-longest-prefix-match.layout.json",
                content = prettyJson.encodeToString(graph),
            )
        }

        "IBD default size matches IBD_DEFAULT_WIDTH × IBD_DEFAULT_HEIGHT" {
            val model =
                sysml2Model(name = "M") {
                    val engineDef = partDef(name = "Engine")
                    val vehicle =
                        partDef(name = "Vehicle") {
                            part(name = "engine", typeId = engineDef.id)
                        }
                    ibd(name = "D", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val graph =
                Sysml2LayoutBridge.toLayoutGraph(
                    model = model,
                    diagram = ibd,
                    sizeProvider =
                        SizeProvider.constant(
                            width = Sysml2LayoutBridge.IBD_DEFAULT_WIDTH,
                            height = Sysml2LayoutBridge.IBD_DEFAULT_HEIGHT,
                        ),
                )
            graph.nodes
                .single()
                .intrinsicSize.width shouldBe Sysml2LayoutBridge.IBD_DEFAULT_WIDTH
            graph.nodes
                .single()
                .intrinsicSize.height shouldBe Sysml2LayoutBridge.IBD_DEFAULT_HEIGHT
        }
    })
