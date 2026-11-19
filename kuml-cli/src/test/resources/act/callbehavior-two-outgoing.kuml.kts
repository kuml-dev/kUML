// Nacharbeit 121a CLI fixture: a call-behavior node with two outgoing flows must produce the warning ACT324 (exit code stays 0).
sysml2Model(name = "CallTwoOutgoing") {
    val c = callBehavior(name = "C", activityId = "Other")
    val a = actionDef(name = "A")
    val b = actionDef(name = "B")
    controlFlow(name = "ca", source = c, target = a)
    controlFlow(name = "cb", source = c, target = b)
    actDiagram(name = "D") {
        include(c)
        include(a)
        include(b)
    }
}
