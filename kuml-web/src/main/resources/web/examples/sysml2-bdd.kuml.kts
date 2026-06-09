// sysml2-bdd.kuml.kts — minimal SysML 2 Block Definition Diagram example
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("VehicleSystem") {
    val massType = attributeDef("Mass")
    val powerType = attributeDef("Power")

    val vehicle = partDef("Vehicle") {
        attribute("mass", typeId = massType.id)
    }

    val engine = partDef("Engine") {
        attribute("ratedPower", typeId = powerType.id)
    }

    bdd("Vehicle BDD") {
        include(vehicle)
        include(engine)
    }
}
