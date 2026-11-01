sysml2Model("Vehicles up") {
    val vehicle = partDef("Vehicle", isAbstract = true)
    val names = listOf("Car", "Truck", "Bus", "Van", "Bike", "Scooter", "Tram", "Tractor")
    val specs = names.map { partDef(it, specializesId = vehicle.id) }
    bdd("Vehicle family up") {
        include(vehicle)
        specs.forEach { include(it) }
        layout {
            wrapColumns = 4
            direction = Up
        }
    }
}
