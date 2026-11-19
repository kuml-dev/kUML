// Nacharbeit 121a CLI fixture: a final node with an outgoing flow must produce the warning ACT323 (exit code stays 0).
sysml2Model(name = "FinalOutgoing") {
    val a = actionDef(name = "A")
    val f = finalNode(name = "F")
    val b = actionDef(name = "B")
    controlFlow(name = "af", source = a, target = f)
    controlFlow(name = "fb", source = f, target = b)
    actDiagram(name = "D") {
        include(a)
        include(f)
        include(b)
    }
}
