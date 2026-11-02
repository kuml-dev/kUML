// Wave 2I-b fixture: a part that performs two actions and exhibits two states.
sysml2Model("Behaviours") {
    val drive = actionDef("Drive")
    val brake = actionDef("Brake")
    val idle = stateDef("Idle")
    val charging = stateDef("Charging")
    val car = partDef("ElectricCar") {
        perform("drive", drive)
        perform("brake", brake)
        exhibit("idle", idle)
        exhibit("charging", charging)
    }
    bdd("Behaviours") {
        include(car)
        include(drive)
        include(brake)
        include(idle)
        include(charging)
    }
}
