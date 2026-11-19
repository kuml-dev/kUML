// Nacharbeit 112 CLI fixture: a join with two outgoing flows must produce the warning ACT318 (exit code stays 0).
sysml2Model(name = "JoinTwoOutgoing") {
    val a = actionDef(name = "A")
    val b = actionDef(name = "B")
    val c = actionDef(name = "C")
    val d = actionDef(name = "D")
    val join = joinNode(name = "J")
    controlFlow(name = "aj", source = a, target = join)
    controlFlow(name = "bj", source = b, target = join)
    controlFlow(name = "jc", source = join, target = c)
    controlFlow(name = "jd", source = join, target = d)
    actDiagram(name = "D") {
        include(a)
        include(b)
        include(c)
        include(d)
        include(join)
    }
}
