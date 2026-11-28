import dev.kuml.sysml2.dsl.sysml2Model

// PAL Welle Y3 fixture: the bottom-right quadrant is filled (end node, edge labels, actions); the legend keeps the old place below.
sysml2Model(name = "Legende dichte Ecke") {
    val bahnA = activityPartition(name = "Links")
    val bahnB = activityPartition(name = "Rechts")
    val start = initialNode(partition = bahnA)
    val wahl = decisionNode(name = "Wahl", partition = bahnA)
    val a1 = actionDef(name = "Weg eins", partition = bahnA)
    val b1 = actionDef(name = "Weg zwei rechts", partition = bahnB)
    val b2 = actionDef(name = "Rechts abschliessen", partition = bahnB)
    val ende = finalNode(partition = bahnB)
    controlFlow(name = "f1", source = start, target = wahl)
    controlFlow(name = "f2", source = wahl, target = a1, guard = "links")
    controlFlow(name = "f3", source = wahl, target = b1, guard = "rechts, sehr ausfuehrliche Bedingung")
    controlFlow(name = "f4", source = b1, target = b2)
    controlFlow(name = "f5", source = b2, target = ende)
    controlFlow(name = "f6", source = a1, target = ende, guard = "fertig")
    actDiagram(name = "Legende dichte Ecke") {
        include(node = start)
        include(node = wahl)
        include(node = a1)
        include(node = b1)
        include(node = b2)
        include(node = ende)
        comment(text = "Legende: Modellhilfe, nicht Teil des Ablaufs", id = "legende", kind = NoteKind.INFO)
        layout {
            direction = Down
        }
    }
}
