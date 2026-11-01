// Wave 2E-b fixture: eight specialisations of one block, wrapColumns = 4.
sysml2Model("Vehicles") {
    val vehicle = partDef("Vehicle", isAbstract = true)
    val names = listOf("Car", "Truck", "Bus", "Van", "Bike", "Scooter", "Tram", "Tractor")
    val specs = names.map { partDef(it, specializesId = vehicle.id) }
    bdd("Vehicle family") {
        include(vehicle)
        specs.forEach { include(it) }
        layout {
            wrapColumns = 4
        }
    }
}
