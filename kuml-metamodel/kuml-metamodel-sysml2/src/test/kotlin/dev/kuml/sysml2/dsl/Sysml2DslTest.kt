package dev.kuml.sysml2.dsl

import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.units.kW
import dev.kuml.sysml2.units.kWh
import dev.kuml.sysml2.units.kg
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
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
