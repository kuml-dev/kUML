c4Model(name = "X7 Frame Notes Outside") {
    val sys = softwareSystem(name = "Control Architecture and FMCS") {
        description = "Fixture of PAL wave X7: notes that stay outside the frame."
        val fmcs = container(name = "FMCS") { technology = "external" }
        val vitec = container(name = "Controller") { technology = "PLC" }
        val damper = container(name = "Return Air Damper") { technology = "Analog 0-10 V" }
        val heater = container(name = "Electric Heater") { technology = "Analog 0-10 V" }
        val lock = container(name = "Door Lock") { technology = "unspecified" }

        this@c4Model.relationship(source = fmcs, target = vitec) { description = "Lock (I1)" }
        this@c4Model.relationship(source = fmcs, target = damper) { description = "Set point (I8)" }
        this@c4Model.relationship(source = fmcs, target = heater) { description = "Set point (I13)" }
        this@c4Model.relationship(source = vitec, target = lock) { description = "Lock door (I5)" }
    }
    val gms = softwareSystem(name = "Building Management") { external = true }
    relationship(source = sys, target = gms) { description = "alarms" }

    note(text = "HINT M2: the frame is a drawing aid.", sys, id = "m2-x7", kind = NoteKind.HINT)
    note(text = "QUESTION: who owns the building management?", gms, id = "q-x7", kind = NoteKind.QUESTION)
    note(text = "ASSUMPTION: alarms pass through the controller.", sys, gms, id = "mix-x7", kind = NoteKind.ASSUMPTION)

    containerDiagram(name = "X7 outside") {
        system = sys
        showExternalSystems = true
        layout { direction = Down }
    }
}
