import dev.kuml.sysml2.dsl.sysml2Model

// PAL Welle Y3 fixture: two free-standing notes form one block (all or nothing).
sysml2Model(name = "Zwei Legenden") {
    val bahnA = activityPartition(name = "Links")
    val bahnB = activityPartition(name = "Rechts")
    val start = initialNode(partition = bahnA)
    val a1 = actionDef(name = "Erster Schritt", partition = bahnA)
    val a2 = actionDef(name = "Zweiter Schritt", partition = bahnA)
    val a3 = actionDef(name = "Dritter Schritt", partition = bahnA)
    val b1 = actionDef(name = "Rechts", partition = bahnB)
    val ende = finalNode(partition = bahnA)
    controlFlow(name = "f1", source = start, target = a1)
    controlFlow(name = "f2", source = a1, target = a2)
    controlFlow(name = "f2b", source = a2, target = b1)
    controlFlow(name = "f4", source = b1, target = a3)
    controlFlow(name = "f5", source = a3, target = ende)
    actDiagram(name = "Zwei Legenden") {
        include(node = start)
        include(node = a1)
        include(node = a2)
        include(node = a3)
        include(node = b1)
        include(node = ende)
        comment(text = "Eins", id = "legende1", kind = NoteKind.INFO)
        comment(text = "Zwei", id = "legende2", kind = NoteKind.INFO)
        layout {
            direction = Down
        }
    }
}
