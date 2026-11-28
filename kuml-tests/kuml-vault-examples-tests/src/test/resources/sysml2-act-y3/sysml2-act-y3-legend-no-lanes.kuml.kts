import dev.kuml.sysml2.dsl.sysml2Model

// PAL Welle Y3 fixture: no lanes; a long left chain and a short right branch leave the bottom-right corner free.
sysml2Model(name = "Legende ohne Bahnen") {
    val start = initialNode()
    val a0 = actionDef(name = "Verteilen")
    val l1 = actionDef(name = "Links eins")
    val l2 = actionDef(name = "Links zwei")
    val l3 = actionDef(name = "Links drei")
    val l4 = actionDef(name = "Links vier")
    val endL = finalNode()
    val r1 = actionDef(name = "Rechts eins")
    val endR = finalNode()
    controlFlow(name = "f1", source = start, target = a0)
    controlFlow(name = "f2", source = a0, target = l1)
    controlFlow(name = "f3", source = l1, target = l2)
    controlFlow(name = "f4", source = l2, target = l3)
    controlFlow(name = "f5", source = l3, target = l4)
    controlFlow(name = "f6", source = l4, target = endL)
    controlFlow(name = "f7", source = a0, target = r1)
    controlFlow(name = "f8", source = r1, target = endR)
    actDiagram(name = "Legende ohne Bahnen") {
        include(node = start)
        include(node = a0)
        include(node = l1)
        include(node = l2)
        include(node = l3)
        include(node = l4)
        include(node = endL)
        include(node = r1)
        include(node = endR)
        comment(text = "Legende", id = "legende", kind = NoteKind.INFO)
        layout {
            direction = Down
        }
    }
}
