c4Model(name = "X7 Frame Notes Inside") {
    val sys = softwareSystem(name = "Control Architecture and FMCS") {
        description = "Fixture of PAL wave X7: notes at members of the frame (reduced copy of the PAL container diagram)."
        val fmcs = container(name = "FMCS") { technology = "external"; description = "Facility management." }
        val controller = container(name = "Controller") { technology = "PLC"; description = "Interlock and alarms." }
        val contacts = container(name = "Door Contacts") { technology = "digital"; description = "Two door contacts." }
        val lock = container(name = "Door Lock") { technology = "unspecified"; description = "Locks the opposite door." }
        val pressure = container(name = "Pressure Sensor") { technology = "Modbus"; description = "Differential pressure." }
        val heater = container(name = "Electric Heater") { technology = "Analog 0-10 V"; description = "Reheater." }
        val damper = container(name = "Damper Drives") { technology = "unspecified"; description = "Frozen when a door opens." }

        this@c4Model.relationship(source = fmcs, target = controller) { description = "Lock (I1)" }
        this@c4Model.relationship(source = controller, target = fmcs) { description = "States (I2)" }
        this@c4Model.relationship(source = contacts, target = controller) { description = "Door states (I3)" }
        this@c4Model.relationship(source = controller, target = lock) { description = "Lock door (I5)" }
        this@c4Model.relationship(source = pressure, target = fmcs) { description = "Pressure (I6)" }
        this@c4Model.relationship(source = fmcs, target = heater) { description = "Set point (I13)" }
        this@c4Model.relationship(source = fmcs, target = damper) { description = "Freeze (I11)" }

        this@c4Model.note(text = "OPEN **A14**: local automatic mode is not defined (FUBE 2.1, 2.3).", controller, id = "a14-x7", kind = NoteKind.QUESTION)
        this@c4Model.note(text = "ASSUMPTION **A15**: one lock per door; missing in the tables.", lock, id = "a15-x7", kind = NoteKind.ASSUMPTION)
        this@c4Model.note(text = "ASSUMPTION **A13**: flow switch and temperature limiter act on heater and FMCS, both readings.", heater, id = "a13-x7", kind = NoteKind.ASSUMPTION)
        this@c4Model.note(text = "ASSUMPTION **A5**: FMCS freezes the dampers; the source names no executor.", damper, id = "a5-x7", kind = NoteKind.ASSUMPTION)
    }

    containerDiagram(name = "X7 inside") {
        system = sys
        layout { direction = Down }
    }
}
