@file:Suppress("unused")

import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.sysml2.dsl.sysml2Model
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
sysml2Model("HybridVehicle") {

    // ── Attribute (value) types ────────────────────────────────────────────
    val mass = attributeDef("Mass")
    val power = attributeDef("Power")
    val energy = attributeDef("Energy")
    val voltage = attributeDef("Voltage")
    val speed = attributeDef("Speed")
    val angle = attributeDef("Angle")

    // ── Port types ─────────────────────────────────────────────────────────
    val powerPort = portDef("PowerPort")
    val mechanicalShaft = portDef("MechanicalShaft")

    // ── Connection types ───────────────────────────────────────────────────
    val powerLine = connectionDef("PowerLine")
    val driveshaft = connectionDef("Driveshaft")

    // ── Parts ──────────────────────────────────────────────────────────────
    val battery = partDef("Battery") {
        attribute("capacity", typeId = energy.id, default = 60.kWh)
        attribute("nominalVoltage", typeId = voltage.id, default = 400.V)
        port("dcOut", typeId = powerPort.id)
    }

    val electricMotor = partDef("ElectricMotor") {
        attribute("ratedPower", typeId = power.id, default = 150.kW)
        port("dcIn", typeId = powerPort.id)
        port("shaft", typeId = mechanicalShaft.id)
    }

    val iceEngine = partDef("InternalCombustionEngine") {
        attribute("ratedPower", typeId = power.id, default = 90.kW)
        port("shaft", typeId = mechanicalShaft.id)
    }

    val powerSplitter = partDef("PowerSplitter") {
        attribute("ratio", typeId = angle.id, default = 35.deg)
        port("iceIn", typeId = mechanicalShaft.id)
        port("emIn", typeId = mechanicalShaft.id)
        port("wheelOut", typeId = mechanicalShaft.id)
    }

    val vehicle = partDef("Vehicle", isAbstract = true) {
        attribute("curbWeight", typeId = mass.id, default = 1500.kg)
        attribute("topSpeed", typeId = speed.id, default = 180.kmph)
    }

    val hybrid = partDef("HybridVehicle", specializesId = vehicle.id) {
        part("battery", typeId = battery.id)
        part("electricMotor", typeId = electricMotor.id)
        part("ice", typeId = iceEngine.id)
        part("splitter", typeId = powerSplitter.id)

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
    bdd("HybridVehicle — structural overview") {
        include(vehicle)
        include(hybrid)
        include(iceEngine)
        include(electricMotor)
        include(battery)
        include(powerSplitter)
    }
}
