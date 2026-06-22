@file:Suppress("unused")

import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.units.V
import dev.kuml.sysml2.units.deg
import dev.kuml.sysml2.units.kW
import dev.kuml.sysml2.units.kWh
import dev.kuml.sysml2.units.kg
import dev.kuml.sysml2.units.kmph

/**
 * Hybrid-Vehicle — SysML 2 BDD example (V2.0.3 MVP).
 *
 * Illustrates the V2.0.3 surface end-to-end:
 *  - attribute definitions (`Mass`, `Power`, `Energy`, `Voltage`, `Angle`, `Speed`)
 *  - port definitions (`PowerPort`, `MechanicalShaft`)
 *  - connection definitions (`PowerLine`, `Driveshaft`)
 *  - part definitions with attribute usages carrying SI-unit defaults
 *    (`100[kW]`, `60[kWh]`, `1500[kg]`)
 *  - inheritance via `specializesId` (`HybridVehicle :> Vehicle`)
 *  - part-usage, port-usage, connection-usage inside `partDef { … }`
 *  - a Block Definition Diagram (`bdd("…") { include(…) }`) for rendering
 *
 * This is the V2.0-roadmap `example-automotive` showcase, sized to fit on one
 * screen. Layout / rendering / CLI integration lands in V2.0.4+ — for V2.0.3
 * the script is exercised purely by the metamodel + DSL tests.
 */
sysml2Model(name = "HybridVehicle") {

    // ── Attribute (value) types ────────────────────────────────────────────
    val mass = attributeDef(name = "Mass")
    val power = attributeDef(name = "Power")
    val energy = attributeDef(name = "Energy")
    val voltage = attributeDef(name = "Voltage")
    val speed = attributeDef(name = "Speed")
    val angle = attributeDef(name = "Angle")

    // ── Port types ─────────────────────────────────────────────────────────
    val powerPort = portDef(name = "PowerPort")
    val mechanicalShaft = portDef(name = "MechanicalShaft")

    // ── Connection types ───────────────────────────────────────────────────
    val powerLine = connectionDef(name = "PowerLine")
    val driveshaft = connectionDef(name = "Driveshaft")

    // ── Parts ──────────────────────────────────────────────────────────────
    val battery =
        partDef(name = "Battery") {
            attribute(name = "capacity", typeId = energy.id, default = 60.kWh)
            attribute(name = "nominalVoltage", typeId = voltage.id, default = 400.V)
            port(name = "dcOut", typeId = powerPort.id)
        }

    val electricMotor =
        partDef(name = "ElectricMotor") {
            attribute(name = "ratedPower", typeId = power.id, default = 150.kW)
            port(name = "dcIn", typeId = powerPort.id)
            port(name = "shaft", typeId = mechanicalShaft.id)
        }

    val iceEngine =
        partDef(name = "InternalCombustionEngine") {
            attribute(name = "ratedPower", typeId = power.id, default = 90.kW)
            port(name = "shaft", typeId = mechanicalShaft.id)
        }

    val powerSplitter =
        partDef(name = "PowerSplitter") {
            attribute(name = "ratio", typeId = angle.id, default = 35.deg)
            port(name = "iceIn", typeId = mechanicalShaft.id)
            port(name = "emIn", typeId = mechanicalShaft.id)
            port(name = "wheelOut", typeId = mechanicalShaft.id)
        }

    val vehicle =
        partDef(name = "Vehicle", isAbstract = true) {
            attribute(name = "curbWeight", typeId = mass.id, default = 1500.kg)
            attribute(name = "topSpeed", typeId = speed.id, default = 180.kmph)
        }

    val hybrid =
        partDef(name = "HybridVehicle", specializesId = vehicle.id) {
            part(name = "battery", typeId = battery.id)
            part(name = "electricMotor", typeId = electricMotor.id)
            part(name = "ice", typeId = iceEngine.id)
            part(name = "splitter", typeId = powerSplitter.id)

            // Two cylinders — multiplicity demo. The real ICE has 4 cylinders;
            // we pin 2 here so the BDD example shows the multiplicity glyph.
            part(
                name = "auxiliaryFans",
                typeId = electricMotor.id,
                multiplicity = KermlMultiplicity(0, 2),
            )

            // Power-flow connections — wire ports up explicitly. SourceEnd/TargetEnd
            // ids use the SysML 2 `::`-qualified form so the model is unambiguous
            // even when several parts have the same port name.
            connect(
                name = "batteryToMotor",
                typeId = powerLine.id,
                sourceEndId = "Battery::dcOut",
                targetEndId = "ElectricMotor::dcIn",
            )
            connect(
                name = "motorToSplitter",
                typeId = driveshaft.id,
                sourceEndId = "ElectricMotor::shaft",
                targetEndId = "PowerSplitter::emIn",
            )
            connect(
                name = "iceToSplitter",
                typeId = driveshaft.id,
                sourceEndId = "InternalCombustionEngine::shaft",
                targetEndId = "PowerSplitter::iceIn",
            )
        }

    // ── Block Definition Diagram ───────────────────────────────────────────
    bdd(name = "HybridVehicle — structural overview") {
        include(definition = vehicle)
        include(definition = hybrid)
        include(definition = iceEngine)
        include(definition = electricMotor)
        include(definition = battery)
        include(definition = powerSplitter)
    }

    // ── Internal Block Diagram (V2.0.6) ────────────────────────────────────
    // Inner wiring view of the HybridVehicle — the four part-usages and the
    // power-flow connections between them. Empty include-block = "show all
    // part-usages of the owner"; the bridge expands it.
    ibd(name = "HybridVehicle — internal block diagram", owner = hybrid)
}
