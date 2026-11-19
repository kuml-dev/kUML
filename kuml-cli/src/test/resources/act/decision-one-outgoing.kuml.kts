// Nacharbeit 121a CLI fixture: a decision with one outgoing flow must produce the warning ACT325 (exit code stays 0).
sysml2Model(name = "DecisionOneOutgoing") {
    val a = actionDef(name = "A")
    val d = decisionNode(name = "D1")
    val b = actionDef(name = "B")
    controlFlow(name = "ad", source = a, target = d)
    controlFlow(name = "db", source = d, target = b)
    actDiagram(name = "D") {
        include(a)
        include(d)
        include(b)
    }
}
