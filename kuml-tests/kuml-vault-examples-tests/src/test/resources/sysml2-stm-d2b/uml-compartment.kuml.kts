// PAL wave 2D-b fixture: UML-Zustaende mit Aktionskompartiment (entry, do, exit, invariant).
stateDiagram(name = "Automat") {
    val start = initialState()
    val offen = state(name = "Offen") {
        entry = "entriegeln()"
    }
    val zu =
        state(name = "Zu") {
            entry = "verriegeln()"
            doActivity = "pruefen()"
            exit = "entriegeln()"
            invariant = "gesichert"
        }
    val lang =
        state(name = "Wartung mit sehr langem Namen") {
            entry = "an extremely long entry expression that needs more room than the default state width"
        }
    val ende = finalState(name = "Ende")
    transition(source = start, target = offen)
    transition(source = offen, target = zu) { trigger = "schliessen" }
    transition(source = zu, target = lang) { trigger = "wartung" }
    transition(source = lang, target = ende) { trigger = "fertig" }
}
