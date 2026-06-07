package dev.kuml.sysml2.dsl

import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ConnectionUsage
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
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.units.kW
import dev.kuml.sysml2.units.kWh
import dev.kuml.sysml2.units.kg
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Exercises the `sysml2Model { … }` top-level DSL end-to-end. The example is
 * a hand-shrunk version of the `example-automotive` HybridVehicle from the
 * V2.0 roadmap — it's small enough to read in one screen but exercises
 * every DSL surface the V2.0.3 MVP provides: partDef/attributeDef/portDef
 * /connectionDef, nested usages with units, specialisation, and a BDD.
 */
class Sysml2DslTest :
    StringSpec({

        "sysml2Model builds a tiny hybrid vehicle model" {
            val model =
                sysml2Model("HybridDemo") {
                    // ── Attribute types (value types) ─────────────────────────
                    val mass = attributeDef("Mass")
                    val power = attributeDef("Power")
                    val energy = attributeDef("Energy")

                    // ── Port type ─────────────────────────────────────────────
                    val powerPort = portDef("PowerPort")

                    // ── Parts ─────────────────────────────────────────────────
                    val engine =
                        partDef("Engine") {
                            attribute("ratedPower", typeId = power.id, default = 100.kW)
                            port("output", typeId = powerPort.id)
                        }
                    val battery =
                        partDef("Battery") {
                            attribute("capacity", typeId = energy.id, default = 60.kWh)
                            port("output", typeId = powerPort.id)
                        }
                    val vehicle =
                        partDef("Vehicle", isAbstract = true) {
                            attribute("curbWeight", typeId = mass.id, default = 1500.kg)
                        }
                    val hybrid =
                        partDef("HybridVehicle", specializesId = vehicle.id) {
                            part("engine", typeId = engine.id)
                            part("battery", typeId = battery.id)
                        }

                    bdd("Structural Overview") {
                        include(vehicle)
                        include(hybrid)
                        include(engine)
                        include(battery)
                    }
                }

            // ── Structural assertions ──────────────────────────────────
            // 3 attributes (Mass/Power/Energy) + 1 port (PowerPort) + 4 parts
            // (Engine/Battery/Vehicle/HybridVehicle) = 8 definitions in this MVP
            // model. The order matches insertion order, so layout / serialisation
            // / diff are deterministic.
            model.name shouldBe "HybridDemo"
            model.definitions shouldHaveSize 8
            model.definitions.filterIsInstance<AttributeDefinition>() shouldHaveSize 3
            model.definitions.filterIsInstance<PortDefinition>() shouldHaveSize 1
            model.definitions.filterIsInstance<PartDefinition>() shouldHaveSize 4

            // The BDD captured all four parts in declaration order.
            val bdd = model.diagrams.single() as BdDiagram
            bdd.name shouldBe "Structural Overview"
            bdd.elementIds shouldBe listOf("Vehicle", "HybridVehicle", "Engine", "Battery")

            // HybridVehicle specialises Vehicle.
            val hybrid =
                model.definitions
                    .filterIsInstance<PartDefinition>()
                    .single { it.name == "HybridVehicle" }
            hybrid.specializations.single().generalId shouldBe "Vehicle"
        }

        "DSL counts definitions correctly across kinds" {
            val model =
                sysml2Model("Counts") {
                    attributeDef("Mass")
                    portDef("Inlet")
                    partDef("Vehicle")
                    partDef("HybridVehicle")
                    connectionDef("PowerLine")
                }
            model.definitions shouldHaveSize 5
            model.definitions.filterIsInstance<AttributeDefinition>() shouldHaveSize 1
            model.definitions.filterIsInstance<PortDefinition>() shouldHaveSize 1
            model.definitions.filterIsInstance<PartDefinition>() shouldHaveSize 2
        }

        "attribute(default = unitValue) records the SysML 2 concrete-syntax form" {
            val model =
                sysml2Model("M") {
                    val mass = attributeDef("Mass")
                    partDef("Vehicle") {
                        attribute("curbWeight", typeId = mass.id, default = 1500.kg)
                    }
                }
            val vehicle = model.definitions.filterIsInstance<PartDefinition>().single()
            val feature = vehicle.features.single()
            feature.name shouldBe "curbWeight"
            feature.typeId shouldBe "Mass"
            // The default expression survives as raw spec-form text — easy to diff,
            // easy to round-trip through the future typed-expression layer in V2.x.
            feature.defaultExpression shouldBe "1500.0[kg]"
        }

        "qualifiedName uses the SysML 2 `::` separator" {
            val model =
                sysml2Model("M") {
                    partDef("Vehicle") {
                        attribute("curbWeight", typeId = "Mass")
                    }
                }
            val feature =
                model.definitions
                    .filterIsInstance<PartDefinition>()
                    .single()
                    .features
                    .single()
            feature.qualifiedName shouldBe "Vehicle::curbWeight"
        }

        "specializesId emits a KermlSpecialization on the resulting PartDefinition" {
            val model =
                sysml2Model("M") {
                    val v = partDef("Vehicle", isAbstract = true)
                    partDef("HybridVehicle", specializesId = v.id)
                }
            val hybrid =
                model.definitions
                    .filterIsInstance<PartDefinition>()
                    .single { it.name == "HybridVehicle" }
            hybrid.specializations shouldHaveSize 1
            hybrid.specializations.single().generalId shouldBe "Vehicle"
        }

        "bdd collects definitions in insertion order" {
            val model =
                sysml2Model("M") {
                    val v = partDef("Vehicle")
                    val e = partDef("Engine")
                    bdd("Overview") {
                        include(v)
                        include(e)
                    }
                }
            val bdd = model.diagrams.single()
            bdd.shouldBeInstanceOf<BdDiagram>()
            bdd.elementIds shouldBe listOf("Vehicle", "Engine")
        }

        "connect records both end ids on the connection-usage" {
            val model =
                sysml2Model("M") {
                    val powerLine = connectionDef("PowerLine")
                    partDef("Vehicle") {
                        connect(
                            name = "engineToBattery",
                            typeId = powerLine.id,
                            sourceEndId = "Vehicle::engine::output",
                            targetEndId = "Vehicle::battery::input",
                        )
                    }
                }
            val vehicle =
                model.definitions
                    .filterIsInstance<PartDefinition>()
                    .single()
            // The connection landed as a KerML feature on Vehicle — that's the
            // KerML-level view used by serialisation / layout. The SysML 2 view
            // is reachable through the builder return value (asserted above).
            vehicle.features.single().typeId shouldBe "PowerLine"
            vehicle.features.single().qualifiedName shouldBe "Vehicle::engineToBattery"
        }

        "DSL connection-usage shape is exposed via the builder return value" {
            var capturedSource = ""
            var capturedTarget = ""
            sysml2Model("M") {
                val powerLine = connectionDef("PowerLine")
                partDef("Vehicle") {
                    val cu: ConnectionUsage =
                        connect(
                            name = "link",
                            typeId = powerLine.id,
                            sourceEndId = "src",
                            targetEndId = "dst",
                        )
                    capturedSource = cu.sourceEndId
                    capturedTarget = cu.targetEndId
                }
            }
            capturedSource shouldBe "src"
            capturedTarget shouldBe "dst"
        }

        "multiplicity carries through the part-usage" {
            val model =
                sysml2Model("M") {
                    partDef("V8Engine") {
                        part(
                            name = "cylinders",
                            typeId = "Cylinder",
                            multiplicity = KermlMultiplicity(8, 8),
                        )
                    }
                }
            val v8 =
                model.definitions
                    .filterIsInstance<PartDefinition>()
                    .single()
            v8.features
                .single()
                .multiplicity
                .toSpecForm() shouldBe "8"
        }

        "ibd projects the part-usages of its owner" {
            // V2.0.6 — empty include-block means "all part-usages of the owner".
            val model =
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val batteryDef = partDef("Battery")
                    val vehicle =
                        partDef("Vehicle") {
                            part("engine", typeId = engineDef.id)
                            part("battery", typeId = batteryDef.id)
                        }
                    ibd("Vehicle wiring", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            ibd.name shouldBe "Vehicle wiring"
            ibd.ownerId shouldBe "Vehicle"
            // Empty elementIds = "include everything"; the bridge expands it.
            ibd.elementIds shouldBe emptyList()
        }

        "ibd with explicit include selects a subset" {
            val model =
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val batteryDef = partDef("Battery")
                    lateinit var enginePartUsage: PartUsage
                    val vehicle =
                        partDef("Vehicle") {
                            enginePartUsage = part("engine", typeId = engineDef.id)
                            part("battery", typeId = batteryDef.id)
                        }
                    ibd("Engine-only wiring", owner = vehicle) {
                        include(enginePartUsage)
                    }
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            ibd.ownerId shouldBe "Vehicle"
            ibd.elementIds shouldContainExactly listOf("Vehicle::engine")
        }

        "ibd includeById accepts forward-/id-only references" {
            val model =
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val vehicle =
                        partDef("Vehicle") {
                            part("engine", typeId = engineDef.id)
                        }
                    ibd("Vehicle wiring", owner = vehicle) {
                        includeById("Vehicle::engine")
                    }
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            ibd.elementIds shouldContainExactly listOf("Vehicle::engine")
        }

        "typed usages register on model.usages alongside KerML features (V2.0.6)" {
            // V2.0.6 surfaced this: the IBD bridge needs ConnectionUsage.sourceEndId
            // / targetEndId, which the KermlFeature view doesn't carry. The DSL now
            // pushes every typed usage into `model.usages` alongside the feature
            // shadow on the parent definition.
            val model =
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val batteryDef = partDef("Battery")
                    val powerLine = connectionDef("PowerLine")
                    val powerPort = portDef("PowerPort")
                    val mass = attributeDef("Mass")
                    partDef("Vehicle") {
                        attribute("curbWeight", typeId = mass.id, default = 1500.kg)
                        part("engine", typeId = engineDef.id)
                        part("battery", typeId = batteryDef.id)
                        port("output", typeId = powerPort.id)
                        connect(
                            name = "wiring",
                            typeId = powerLine.id,
                            sourceEndId = "Vehicle::engine::out",
                            targetEndId = "Vehicle::battery::in",
                        )
                    }
                }
            // 1 attribute, 2 parts, 1 port, 1 connection
            model.usages shouldHaveSize 5
            model.usages.filterIsInstance<PartUsage>() shouldHaveSize 2
            model.usages.filterIsInstance<AttributeUsage>() shouldHaveSize 1
            model.usages.filterIsInstance<PortUsage>() shouldHaveSize 1
            model.usages.filterIsInstance<ConnectionUsage>() shouldHaveSize 1

            // The ConnectionUsage round-trips its endpoint ids through the model.
            val cu = model.usages.filterIsInstance<ConnectionUsage>().single()
            cu.sourceEndId shouldBe "Vehicle::engine::out"
            cu.targetEndId shouldBe "Vehicle::battery::in"
        }

        // ── UC Diagram (V2.0.7) ───────────────────────────────────────────────

        "ucDiagram captures actors, use cases, associations, includes, and extends" {
            val model =
                sysml2Model("Library") {
                    val reader = actorDef("Reader")
                    val librarian = actorDef("Librarian")
                    val borrow = useCaseDef("BorrowBook")
                    val auth = useCaseDef("Authenticate")
                    val returnBook = useCaseDef("ReturnBook")
                    val payFee = useCaseDef("PayLateFee")
                    ucDiagram("Top-level UC") {
                        include(reader)
                        include(librarian)
                        include(borrow)
                        include(auth)
                        include(returnBook)
                        include(payFee)
                        association(reader, borrow)
                        association(librarian, borrow)
                        include(borrow, auth)
                        extend(payFee, returnBook)
                    }
                }
            // 2 actors + 4 use-cases = 6 definitions.
            model.definitions.filterIsInstance<ActorDefinition>() shouldHaveSize 2
            model.definitions.filterIsInstance<UseCaseDefinition>() shouldHaveSize 4

            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            uc.name shouldBe "Top-level UC"
            uc.elementIds shouldBe
                listOf("Reader", "Librarian", "BorrowBook", "Authenticate", "ReturnBook", "PayLateFee")
            uc.associations shouldHaveSize 2
            uc.includes shouldHaveSize 1
            uc.extends shouldHaveSize 1
        }

        "ucDiagram include relationship is disambiguated from include(definition)" {
            // Same name, different argument types: include(Sysml2Definition) vs
            // include(UseCaseDefinition, UseCaseDefinition). Kotlin overload
            // resolution picks the right one — assert both work in one block.
            val model =
                sysml2Model("DisambigDemo") {
                    val borrow = useCaseDef("BorrowBook")
                    val auth = useCaseDef("Authenticate")
                    ucDiagram("UC") {
                        // One-arg form → add use-case as a node.
                        include(borrow)
                        include(auth)
                        // Two-arg form → create the «include» relationship.
                        include(borrow, auth)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            uc.elementIds shouldBe listOf("BorrowBook", "Authenticate")
            uc.includes shouldHaveSize 1
            uc.includes
                .single()
                .id shouldBe "include:BorrowBook::Authenticate"
            uc.includes
                .single()
                .sourceUseCaseId shouldBe "BorrowBook"
            uc.includes
                .single()
                .targetUseCaseId shouldBe "Authenticate"
        }

        "ucDiagram associations / includes / extends have deterministic ids" {
            val model =
                sysml2Model("IdShape") {
                    val reader = actorDef("Reader")
                    val borrow = useCaseDef("BorrowBook")
                    val auth = useCaseDef("Authenticate")
                    val returnBook = useCaseDef("ReturnBook")
                    val payFee = useCaseDef("PayLateFee")
                    ucDiagram("UC") {
                        include(reader)
                        include(borrow)
                        include(auth)
                        include(returnBook)
                        include(payFee)
                        association(reader, borrow)
                        include(borrow, auth)
                        extend(payFee, returnBook)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            uc.associations
                .single()
                .id shouldBe "assoc:Reader::BorrowBook"
            uc.includes
                .single()
                .id shouldBe "include:BorrowBook::Authenticate"
            uc.extends
                .single()
                .id shouldBe "extend:PayLateFee::ReturnBook"
        }

        "ucDiagram supports forward references via id-only overloads" {
            val model =
                sysml2Model("Forward") {
                    actorDef("Reader")
                    useCaseDef("BorrowBook")
                    useCaseDef("Authenticate")
                    ucDiagram("UC") {
                        includeById("Reader")
                        includeById("BorrowBook")
                        includeById("Authenticate")
                        associationById("Reader", "BorrowBook")
                        includeById("BorrowBook", "Authenticate")
                        extendById("Authenticate", "BorrowBook")
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            uc.elementIds shouldBe listOf("Reader", "BorrowBook", "Authenticate")
            uc.associations
                .single()
                .id shouldBe "assoc:Reader::BorrowBook"
            uc.includes
                .single()
                .id shouldBe "include:BorrowBook::Authenticate"
            uc.extends
                .single()
                .id shouldBe "extend:Authenticate::BorrowBook"
        }

        // ── REQ Diagram (V2.0.8) ──────────────────────────────────────────────

        "reqDiagram captures requirements, satisfies, verifies, derives, contains" {
            val model =
                sysml2Model("VehicleReqs") {
                    val topSpeed =
                        requirementDef(
                            "TopSpeedRequirement",
                            reqId = "R-001",
                            text = "The vehicle shall reach at least 180 km/h on flat road",
                        )
                    val fuel =
                        requirementDef(
                            "FuelEfficiencyRequirement",
                            reqId = "R-003",
                            text = "The vehicle shall consume less than 4 l/100km combined",
                        )
                    val emissions = requirementDef("EmissionsRequirement", reqId = "R-004")
                    val nox = requirementDef("NOxRequirement", reqId = "R-005")
                    val vehicle = partDef("Vehicle")
                    val verifyTopSpeed = useCaseDef("VerifyTopSpeed")
                    reqDiagram("Top-level") {
                        include(topSpeed)
                        include(fuel)
                        include(emissions)
                        include(nox)
                        include(vehicle)
                        include(verifyTopSpeed)
                        satisfy(vehicle, topSpeed)
                        verify(verifyTopSpeed, topSpeed)
                        derive(topSpeed, fuel)
                        contains(emissions, nox)
                    }
                }
            model.definitions.filterIsInstance<RequirementDefinition>() shouldHaveSize 4

            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            req.name shouldBe "Top-level"
            req.elementIds shouldBe
                listOf(
                    "TopSpeedRequirement",
                    "FuelEfficiencyRequirement",
                    "EmissionsRequirement",
                    "NOxRequirement",
                    "Vehicle",
                    "VerifyTopSpeed",
                )
            req.satisfies shouldHaveSize 1
            req.verifies shouldHaveSize 1
            req.derives shouldHaveSize 1
            req.contains shouldHaveSize 1
        }

        "RequirementDefinition stores text, reqId, and subject" {
            val model =
                sysml2Model("M") {
                    requirementDef(
                        "TopSpeedRequirement",
                        reqId = "R-001",
                        text = "The vehicle shall reach at least 180 km/h on flat road",
                        subject = "Vehicle",
                    )
                }
            val req = model.definitions.filterIsInstance<RequirementDefinition>().single()
            req.reqId shouldBe "R-001"
            req.text shouldBe "The vehicle shall reach at least 180 km/h on flat road"
            req.subject shouldBe "Vehicle"
        }

        "reqDiagram edge ids are deterministic" {
            val model =
                sysml2Model("IdShape") {
                    val r1 = requirementDef("TopSpeed", reqId = "R-001")
                    val r2 = requirementDef("Fuel", reqId = "R-003")
                    val r3 = requirementDef("Emissions", reqId = "R-004")
                    val r4 = requirementDef("NOx", reqId = "R-005")
                    val vehicle = partDef("Vehicle")
                    val verifier = useCaseDef("VerifyTopSpeed")
                    reqDiagram("REQ") {
                        include(r1)
                        include(r2)
                        include(r3)
                        include(r4)
                        include(vehicle)
                        include(verifier)
                        satisfy(vehicle, r1)
                        verify(verifier, r1)
                        derive(r1, r2)
                        contains(r3, r4)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            req.satisfies
                .single()
                .id shouldBe "satisfy:Vehicle::TopSpeed"
            req.verifies
                .single()
                .id shouldBe "verify:VerifyTopSpeed::TopSpeed"
            req.derives
                .single()
                .id shouldBe "derive:TopSpeed::Fuel"
            req.contains
                .single()
                .id shouldBe "contains:Emissions::NOx"
        }

        "reqDiagram forward refs via includeById / *ById overloads work" {
            val model =
                sysml2Model("Forward") {
                    requirementDef("TopSpeed", reqId = "R-001")
                    requirementDef("Fuel", reqId = "R-003")
                    requirementDef("Emissions", reqId = "R-004")
                    requirementDef("NOx", reqId = "R-005")
                    partDef("Vehicle")
                    useCaseDef("VerifyTopSpeed")
                    reqDiagram("REQ") {
                        includeById("TopSpeed")
                        includeById("Fuel")
                        includeById("Emissions")
                        includeById("NOx")
                        includeById("Vehicle")
                        includeById("VerifyTopSpeed")
                        satisfyById("Vehicle", "TopSpeed")
                        verifyById("VerifyTopSpeed", "TopSpeed")
                        deriveById("TopSpeed", "Fuel")
                        containsById("Emissions", "NOx")
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            req.elementIds shouldBe
                listOf("TopSpeed", "Fuel", "Emissions", "NOx", "Vehicle", "VerifyTopSpeed")
            req.satisfies
                .single()
                .id shouldBe "satisfy:Vehicle::TopSpeed"
            req.verifies
                .single()
                .id shouldBe "verify:VerifyTopSpeed::TopSpeed"
            req.derives
                .single()
                .id shouldBe "derive:TopSpeed::Fuel"
            req.contains
                .single()
                .id shouldBe "contains:Emissions::NOx"
        }

        // ── STM Diagram (V2.0.9) ──────────────────────────────────────────────

        "stateDef stores isInitial/isFinal/entry/exit/do action fields" {
            val model =
                sysml2Model("StateFields") {
                    stateDef("Initial", isInitial = true)
                    stateDef("Final", isFinal = true)
                    stateDef(
                        "Red",
                        entryAction = "switchLights('red')",
                        exitAction = "logTransition('red')",
                        doAction = "tickTimer()",
                    )
                }
            val states = model.definitions.filterIsInstance<StateDefinition>()
            states shouldHaveSize 3

            val initial = states.single { it.name == "Initial" }
            initial.isInitial shouldBe true
            initial.isFinal shouldBe false

            val final = states.single { it.name == "Final" }
            final.isFinal shouldBe true
            final.isInitial shouldBe false

            val red = states.single { it.name == "Red" }
            red.entryAction shouldBe "switchLights('red')"
            red.exitAction shouldBe "logTransition('red')"
            red.doAction shouldBe "tickTimer()"
            red.isInitial shouldBe false
            red.isFinal shouldBe false
        }

        "transition registers a TransitionUsage in model.usages with source/target/trigger/guard/effect" {
            val model =
                sysml2Model("Lights") {
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
                }
            val transitions = model.usages.filterIsInstance<TransitionUsage>()
            transitions shouldHaveSize 1
            val t = transitions.single()
            t.name shouldBe "redToGreen"
            t.sourceStateId shouldBe "Red"
            t.targetStateId shouldBe "Green"
            t.trigger shouldBe "timer60s"
            t.guard shouldBe "!emergency"
            t.effect shouldBe "switchLights('green')"
            // Default id convention: `transition:<source>::<target>`.
            t.id shouldBe "transition:Red::Green"
        }

        "stmDiagram captures state ids in declaration order" {
            val model =
                sysml2Model("TrafficLights") {
                    val initial = stateDef("Initial", isInitial = true)
                    val red = stateDef("Red")
                    val green = stateDef("Green")
                    val yellow = stateDef("Yellow")
                    transition("initial", initial, red)
                    transition("redToGreen", red, green, trigger = "timer60s")
                    transition("greenToYellow", green, yellow, trigger = "timer45s")
                    transition("yellowToRed", yellow, red, trigger = "timer5s")
                    stmDiagram("Phase cycle") {
                        include(initial)
                        include(red)
                        include(green)
                        include(yellow)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            stm.name shouldBe "Phase cycle"
            stm.elementIds shouldBe listOf("Initial", "Red", "Green", "Yellow")
            // Transitions remain on the model.usages (not on the diagram).
            model.usages.filterIsInstance<TransitionUsage>() shouldHaveSize 4
        }

        "transition forward-ref via transitionById accepts id-only endpoints" {
            // Useful when replaying a state machine from an external source where
            // the StateDefinition references are not yet in scope.
            val model =
                sysml2Model("ForwardRefSTM") {
                    stateDef("Red")
                    stateDef("Green")
                    transitionById(
                        name = "redToGreen",
                        sourceStateId = "Red",
                        targetStateId = "Green",
                        trigger = "timer60s",
                    )
                    stmDiagram("STM") {
                        includeById("Red")
                        includeById("Green")
                    }
                }
            val t = model.usages.filterIsInstance<TransitionUsage>().single()
            t.id shouldBe "transition:Red::Green"
            t.sourceStateId shouldBe "Red"
            t.targetStateId shouldBe "Green"
            t.trigger shouldBe "timer60s"

            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            stm.elementIds shouldBe listOf("Red", "Green")
        }

        // ── V2.0.10 ACT ──────────────────────────────────────────────────

        "V2.0.10 actionDef stores kind + action body" {
            val model =
                sysml2Model("ActTest") {
                    actionDef(
                        name = "ValidateOrder",
                        action = "validate(order)",
                    )
                }
            val act = model.definitions.filterIsInstance<ActionDefinition>().single()
            act.id shouldBe "ValidateOrder"
            act.name shouldBe "ValidateOrder"
            act.kind shouldBe ActivityNodeKind.Action
            act.action shouldBe "validate(order)"
        }

        "V2.0.10 pseudo-node helpers produce the right ActivityNodeKind" {
            val model =
                sysml2Model("ActTest") {
                    initialNode()
                    finalNode()
                    flowFinalNode()
                    decisionNode("Valid?")
                    mergeNode("Done?")
                    forkNode("Split")
                    joinNode("Sync")
                }
            val byName = model.definitions.filterIsInstance<ActionDefinition>().associateBy { it.name }
            byName.getValue("Initial").kind shouldBe ActivityNodeKind.Initial
            byName.getValue("Final").kind shouldBe ActivityNodeKind.Final
            byName.getValue("FlowFinal").kind shouldBe ActivityNodeKind.FlowFinal
            byName.getValue("Valid?").kind shouldBe ActivityNodeKind.Decision
            byName.getValue("Done?").kind shouldBe ActivityNodeKind.Merge
            byName.getValue("Split").kind shouldBe ActivityNodeKind.Fork
            byName.getValue("Sync").kind shouldBe ActivityNodeKind.Join
        }

        "V2.0.10 controlFlow registers a ControlFlowUsage with source/target/guard" {
            val model =
                sysml2Model("ActTest") {
                    val a = actionDef("A")
                    val b = actionDef("B")
                    controlFlow(name = "aToB", source = a, target = b, guard = "valid")
                }
            val flow = model.usages.filterIsInstance<ControlFlowUsage>().single()
            flow.id shouldBe "controlFlow:A::B"
            flow.sourceNodeId shouldBe "A"
            flow.targetNodeId shouldBe "B"
            flow.guard shouldBe "valid"
            flow.definitionId shouldBe "sysml2.controlFlow"
        }

        "V2.0.10 objectFlow registers an ObjectFlowUsage with objectType" {
            val model =
                sysml2Model("ActTest") {
                    val a = actionDef("Validate")
                    val b = actionDef("Process")
                    objectFlow(name = "carry", source = a, target = b, objectType = "Order")
                }
            val flow = model.usages.filterIsInstance<ObjectFlowUsage>().single()
            flow.id shouldBe "objectFlow:Validate::Process"
            flow.sourceNodeId shouldBe "Validate"
            flow.targetNodeId shouldBe "Process"
            flow.objectType shouldBe "Order"
            flow.definitionId shouldBe "sysml2.objectFlow"
        }

        "V2.0.10 actDiagram captures element ids in declaration order" {
            val model =
                sysml2Model("ActTest") {
                    val init = initialNode()
                    val validate = actionDef("Validate")
                    val fin = finalNode()
                    controlFlow("start", init, validate)
                    controlFlow("end", validate, fin)
                    actDiagram("Workflow") {
                        include(init)
                        include(validate)
                        include(fin)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            act.name shouldBe "Workflow"
            act.elementIds shouldContainExactly listOf("Initial", "Validate", "Final")
            // Flows are on the model, not on the diagram.
            model.usages.filterIsInstance<ControlFlowUsage>() shouldHaveSize 2
        }

        // ── V2.0.11 SEQ ──────────────────────────────────────────────────

        "V2.0.11 lifelineDef stores represents reference" {
            val model =
                sysml2Model("SeqTest") {
                    partDef("Browser")
                    lifelineDef(name = "browser", id = "browser", represents = "Browser")
                }
            val lifeline = model.definitions.filterIsInstance<LifelineDefinition>().single()
            lifeline.id shouldBe "browser"
            lifeline.name shouldBe "browser"
            lifeline.represents shouldBe "Browser"
        }

        "V2.0.11 message registers a MessageUsage with sourceLifelineId/targetLifelineId/seqNo/messageLabel/kind" {
            val model =
                sysml2Model("SeqTest") {
                    val user = lifelineDef("user")
                    val browser = lifelineDef("browser")
                    message(label = "login(user, pwd)", source = user, target = browser, seqNo = 1)
                }
            val msg = model.usages.filterIsInstance<MessageUsage>().single()
            msg.id shouldBe "message:user-browser-1"
            msg.sourceLifelineId shouldBe "user"
            msg.targetLifelineId shouldBe "browser"
            msg.seqNo shouldBe 1
            msg.messageLabel shouldBe "login(user, pwd)"
            msg.kind shouldBe MessageKind.Sync
            msg.definitionId shouldBe "sysml2.message"
        }

        "V2.0.11 message kinds default to Sync; explicit Async / Reply are preserved" {
            val model =
                sysml2Model("SeqTest") {
                    val a = lifelineDef("a")
                    val b = lifelineDef("b")
                    message("syncCall", a, b, seqNo = 1) // default Sync
                    message("asyncCall", a, b, seqNo = 2, kind = MessageKind.Async)
                    message("reply", b, a, seqNo = 3, kind = MessageKind.Reply)
                }
            val messages = model.usages.filterIsInstance<MessageUsage>().sortedBy { it.seqNo }
            messages shouldHaveSize 3
            messages[0].kind shouldBe MessageKind.Sync
            messages[1].kind shouldBe MessageKind.Async
            messages[2].kind shouldBe MessageKind.Reply
        }

        "V2.0.11 seqDiagram captures lifeline ids in declaration order" {
            val model =
                sysml2Model("SeqTest") {
                    val user = lifelineDef("user")
                    val browser = lifelineDef("browser")
                    val auth = lifelineDef("authService")
                    message("enterCredentials", user, browser, seqNo = 1)
                    message("login", browser, auth, seqNo = 2)
                    seqDiagram("Login flow") {
                        include(user)
                        include(browser)
                        include(auth)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            seq.name shouldBe "Login flow"
            seq.elementIds shouldContainExactly listOf("user", "browser", "authService")
            // Messages live on the model, not on the diagram.
            model.usages.filterIsInstance<MessageUsage>() shouldHaveSize 2
        }

        "V2.0.11 messageById accepts id-only endpoints for forward refs" {
            val model =
                sysml2Model("ForwardRefSEQ") {
                    lifelineDef("a")
                    lifelineDef("b")
                    messageById(
                        label = "ping",
                        sourceLifelineId = "a",
                        targetLifelineId = "b",
                        seqNo = 1,
                        kind = MessageKind.Async,
                    )
                    seqDiagram("SEQ") {
                        includeById("a")
                        includeById("b")
                    }
                }
            val msg = model.usages.filterIsInstance<MessageUsage>().single()
            msg.id shouldBe "message:a-b-1"
            msg.kind shouldBe MessageKind.Async
        }

        // ───────────────────────────── V2.0.12 PAR ────────────────────────────

        "V2.0.12 constraintDef stores expression and parameters" {
            val model =
                sysml2Model("PARExpression") {
                    constraintDef(
                        "NewtonsLaw",
                        expression = "F = m * a",
                        parameters =
                            listOf(
                                ConstraintParameter("F", typeId = "Force", direction = ConstraintParameterDirection.Out),
                                ConstraintParameter("m", typeId = "Mass", direction = ConstraintParameterDirection.In),
                                ConstraintParameter("a", typeId = "Acceleration", direction = ConstraintParameterDirection.In),
                            ),
                    )
                }
            val constraint = model.definitions.filterIsInstance<ConstraintDefinition>().single()
            constraint.name shouldBe "NewtonsLaw"
            constraint.expression shouldBe "F = m * a"
            constraint.parameters shouldHaveSize 3
            constraint.parameters[0].name shouldBe "F"
            constraint.parameters[0].direction shouldBe ConstraintParameterDirection.Out
            constraint.parameters[0].typeId shouldBe "Force"
        }

        "V2.0.12 ConstraintParameter direction defaults to Inout; explicit In / Out are preserved" {
            val pDefault = ConstraintParameter("p")
            pDefault.direction shouldBe ConstraintParameterDirection.Inout
            pDefault.typeId shouldBe null

            val pIn = ConstraintParameter("m", typeId = "Mass", direction = ConstraintParameterDirection.In)
            pIn.direction shouldBe ConstraintParameterDirection.In
            pIn.typeId shouldBe "Mass"

            val pOut = ConstraintParameter("F", typeId = "Force", direction = ConstraintParameterDirection.Out)
            pOut.direction shouldBe ConstraintParameterDirection.Out
            pOut.typeId shouldBe "Force"
        }

        "V2.0.12 bind registers a BindingConnectorUsage in model.usages with both endpoint ids" {
            val model =
                sysml2Model("PARBinding") {
                    val mass = attributeDef("Mass")
                    constraintDef(
                        "NewtonsLaw",
                        expression = "F = m * a",
                        parameters = listOf(ConstraintParameter("m", typeId = mass.id, direction = ConstraintParameterDirection.In)),
                    )
                    partDef("Vehicle") {
                        attribute("mass", typeId = mass.id)
                    }
                    bind("bindMass", source = "NewtonsLaw::m", target = "Vehicle::mass")
                }
            val binding = model.usages.filterIsInstance<BindingConnectorUsage>().single()
            binding.name shouldBe "bindMass"
            binding.sourceEndId shouldBe "NewtonsLaw::m"
            binding.targetEndId shouldBe "Vehicle::mass"
            binding.id shouldBe "binding:NewtonsLaw::m::Vehicle::mass"
            binding.definitionId shouldBe "sysml2.bindingConnector"
        }

        "V2.0.12 parDiagram captures element ids including a mix of constraints and parts" {
            val model =
                sysml2Model("PARMix") {
                    val mass = attributeDef("Mass")
                    val accel = attributeDef("Acceleration")
                    val force = attributeDef("Force")
                    val newton =
                        constraintDef(
                            "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter("F", typeId = force.id, direction = ConstraintParameterDirection.Out),
                                    ConstraintParameter("m", typeId = mass.id, direction = ConstraintParameterDirection.In),
                                    ConstraintParameter("a", typeId = accel.id, direction = ConstraintParameterDirection.In),
                                ),
                        )
                    val vehicle =
                        partDef("Vehicle") {
                            attribute("mass", typeId = mass.id)
                            attribute("acceleration", typeId = accel.id)
                            attribute("force", typeId = force.id)
                        }
                    bind("bindMass", source = "NewtonsLaw::m", target = "Vehicle::mass")
                    bind("bindAccel", source = "NewtonsLaw::a", target = "Vehicle::acceleration")
                    bind("bindForce", source = "NewtonsLaw::F", target = "Vehicle::force")
                    parDiagram("Newton — F = m·a applied to Vehicle") {
                        include(newton)
                        include(vehicle)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            par.name shouldBe "Newton — F = m·a applied to Vehicle"
            par.elementIds shouldContainExactly listOf("NewtonsLaw", "Vehicle")
            // Bindings live on the model, not on the diagram.
            model.usages.filterIsInstance<BindingConnectorUsage>() shouldHaveSize 3
        }

        "DSL is sysml2Dsl-scoped — inner scopes can't reach outer builders accidentally" {
            // This is a *compile-time* contract enforced by the @Sysml2Dsl marker.
            // Smoke-asserting at runtime: building a model still works and the
            // result type matches the public API. The exhaustive scope check is
            // a kotlinc-level invariant.
            val model =
                sysml2Model("Smoke") {
                    partDef("A") {
                        attribute("a", typeId = "Real")
                    }
                }
            model.definitions.shouldContain(
                PartDefinition(
                    id = "A",
                    name = "A",
                    features =
                        listOf(
                            dev.kuml.kerml.KermlFeature(
                                id = "A::a",
                                name = "a",
                                qualifiedName = "A::a",
                                typeId = "Real",
                                definitionId = "Real",
                            ),
                        ),
                ),
            )
        }
    })
