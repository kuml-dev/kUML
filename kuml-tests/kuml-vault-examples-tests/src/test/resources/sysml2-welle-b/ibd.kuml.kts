sysml2Model("Ibd") {
    attributeDef("Fuel")
    portDef("FuelPort") {
        flowProperty("fuel", "Fuel", PortDirection.In)
    }
    connectionDef("FuelLine")
    partDef("Crank") { port("injector", "FuelPort", direction = PortDirection.In) }
    partDef("Engine") {
        part("crank", "Crank")
    }
    partDef("Tank") { port("outlet", "FuelPort", direction = PortDirection.Out) }
    val vehicle = partDef("Vehicle") {
        port("fuelIn", "FuelPort", direction = PortDirection.In)
        part("engine", "Engine")
        part("tank", "Tank")
        connect("delegation", "FuelLine", "Vehicle::fuelIn", "engine.crank.injector")
        connect("feed", "FuelLine", "tank.outlet", "engine.crank.injector")
        flow("Fuel", "tank.outlet", "engine.crank.injector", via = "feed")
    }
    ibd("Vehicle wiring", owner = vehicle)
}
