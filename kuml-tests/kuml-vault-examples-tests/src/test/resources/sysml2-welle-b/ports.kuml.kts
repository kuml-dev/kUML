sysml2Model("Ports") {
    attributeDef("Fuel", unitSymbol = "l")
    connectionDef("Line")
    portDef("FuelPort") {
        flowProperty("fuel", "Fuel", PortDirection.In)
        flowProperty("exhaust", "Fuel", PortDirection.Out)
    }
    partDef("Tank") {
        port("outlet", "FuelPort", direction = PortDirection.Out)
    }
    partDef("Engine") {
        port("injector", "FuelPort", direction = PortDirection.In, conjugated = true)
        port("sensor", "FuelPort", direction = PortDirection.InOut)
    }
    val vehicle = partDef("Vehicle") {
        port("fuelIn", "FuelPort", direction = PortDirection.In)
        port("telemetry", "FuelPort")
        part("tank", "Tank")
        part("engine", "Engine")
        connect("c1", "Line", "Vehicle::fuelIn", "tank.outlet")
        connect("c2", "Line", "engine.injector", "Vehicle::telemetry")
    }
    ibd("Vehicle ports", owner = vehicle)
}
