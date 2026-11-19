// Nacharbeit 121a CLI fixture: an initial node with an incoming flow must produce the warning ACT322 (exit code stays 0).
sysml2Model(name = "InitialIncoming") {
    val i = initialNode(name = "I")
    val a = actionDef(name = "A")
    controlFlow(name = "ia", source = i, target = a)
    controlFlow(name = "ai", source = a, target = i)
    actDiagram(name = "D") {
        include(i)
        include(a)
    }
}
