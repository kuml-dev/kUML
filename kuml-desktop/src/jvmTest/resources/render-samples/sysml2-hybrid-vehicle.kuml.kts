import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "VehicleSystem") {
    val massType = attributeDef(name = "Mass")
    val powerType = attributeDef(name = "Power")

    // Abstrakte Basis-Definition — kann nicht direkt instanziiert werden
    val powertrain = partDef(name = "Powertrain", isAbstract = true) {
        attribute(name = "ratedPower", typeId = powerType.id)
    }

    val vehicle = partDef(name = "Vehicle") {
        attribute(name = "mass", typeId = massType.id)
    }

    // Spezialisierung: Engine ist eine konkrete Powertrain-Ausprägung
    val engine = partDef(name = "Engine", specializesId = powertrain.id) {
        attribute(name = "ratedPower", typeId = powerType.id)
    }

    bdd(name = "Vehicle BDD") {
        include(definition = powertrain)
        include(definition = vehicle)
        include(definition = engine)
    }
}
