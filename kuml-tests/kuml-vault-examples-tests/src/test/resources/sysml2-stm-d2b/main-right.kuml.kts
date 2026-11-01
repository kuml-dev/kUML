import dev.kuml.sysml2.dsl.sysml2Model

// PAL wave 2D-b fixture: gefuellter Initial-Punkt, Initial nahe am Ziel, sameLayer mit Mittenausrichtung (Hoehe 44 und
// 92 mit einer Kante dazwischen), Composite mit Entry/Exit-Text und Kanten hinein, Zustaende mit [invariant] sowie
// entry/do/exit, acht Notizen (einige mit langem Text).
sysml2Model("Wave2dB") {
    val start = stateDef("Start", isInitial = true)
    val idle = stateDef("Idle", entryAction = "init()", invariant = "ready")
    val small = stateDef("Small")
    val big =
        stateDef(
            "Big",
            entryAction = "open()",
            doAction = "regulate()",
            exitAction = "close()",
            invariant = "pressure < 10",
        )
    val safe = stateDef("Safe")
    val gehalten = stateDef("Gehalten", entryAction = "lock()", exitAction = "unlock()")
    val k1 = stateDef("K1", parent = gehalten)
    val k2 = stateDef("K2", parent = gehalten)
    val ende = stateDef("Ende", isFinal = true)

    transition("init", start, idle)
    transition("t1", idle, small, trigger = "go")
    transition("t2", small, big, trigger = "grow")
    transition("t3", idle, gehalten, trigger = "hold")
    transition("t4", gehalten, safe, trigger = "release")
    transition("t5", big, safe, trigger = "settle")
    transition("t6", safe, ende, trigger = "stop")

    note("Start of the machine; the first transition is short and straight.", start, id = "n1")
    note("Idle waits for the go command. It records the invariant ready and runs init() on entry, which is a rather long explanation.", idle, id = "n2")
    note("Small and Big share their layer; their centres are aligned.", small, big, id = "n3")
    note("Big shows the invariant above entry, do and exit.", big, id = "n4")
    note("Gehalten has an entry text that must not be crossed by an edge.", gehalten, id = "n5")
    note("K1 is the first child of the composite state, entered when the machine holds.", k1, id = "n6")
    note("K2 is the second child of the composite state and is left through release.", k2, id = "n7")
    note("Safe is reached after settling or releasing; from there the machine stops, which ends the run for good.", safe, id = "n8")

    stmDiagram("Wave2dB") {
        include(start)
        include(idle)
        include(small)
        include(big)
        include(safe)
        include(gehalten)
        include(ende)
        layout {
            sameLayerById("Small", "Big")
            last(ende)
            direction = Right
        }
    }
}
