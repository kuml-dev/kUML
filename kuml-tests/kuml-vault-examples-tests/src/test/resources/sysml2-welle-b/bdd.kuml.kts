sysml2Model("Bdd") {
    attributeDef("Mass", unitSymbol = "kg")
    portDef("FuelPort") { flowProperty("fuel", "Mass", PortDirection.Out) }
    connectionDef("Drives") {
        part("driver", "Engine")
        part("driven", "Wheel")
    }
    partDef("Wheel")
    partDef("Tank")
    partDef("Engine") {
        port("out", "FuelPort", direction = PortDirection.Out)
        action("burn", "Burn")
    }
    val vehicle = partDef("Vehicle") {
        part("engine", "Engine")
        part("wheels", "Wheel", multiplicity = dev.kuml.kerml.KermlMultiplicity(4, 4))
        refPart("tank", "Tank")
    }
    val behavior = partDef("Controller")
    allocate(behavior, vehicle, name = "ctl")
    comment("A vehicle owns its engine.", vehicle)
    bdd("Vehicle structure") {
        includeById("Vehicle"); includeById("Engine"); includeById("Wheel"); includeById("Tank")
        includeById("Mass"); includeById("Controller")
    }
}
