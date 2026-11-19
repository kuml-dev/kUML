// Nacharbeit 112 CLI fixture: an action with two outgoing flows must produce the warning ACT321 (exit code stays 0).
sysml2Model(name = "ActionTwoOutgoing") {
    val a = actionDef(name = "A")
    val b = actionDef(name = "B")
    val c = actionDef(name = "C")
    controlFlow(name = "ab", source = a, target = b)
    controlFlow(name = "ac", source = a, target = c)
    actDiagram(name = "D") {
        include(a)
        include(b)
        include(c)
    }
}
