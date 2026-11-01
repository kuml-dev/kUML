sysml2Model("Two") {
    val vehicle = partDef("Vehicle", isAbstract = true)
    val a = partDef("Car", specializesId = vehicle.id)
    val b = partDef("Truck", specializesId = vehicle.id)
    bdd("Two specialisations") {
        include(vehicle)
        include(a)
        include(b)
    }
}
