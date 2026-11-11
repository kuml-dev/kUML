// Wave AJ (g) CLI fixture: a fork with two incoming flows must produce the warning ACT316 (exit code stays 0).
sysml2Model(name = "ForkTwoIncoming") {
    val a = actionDef(name = "A")
    val b = actionDef(name = "B")
    val c = actionDef(name = "C")
    val fork = forkNode(name = "F")
    controlFlow(name = "af", source = a, target = fork)
    controlFlow(name = "bf", source = b, target = fork)
    controlFlow(name = "fc", source = fork, target = c)
    actDiagram(name = "D") {
        include(a)
        include(b)
        include(c)
        include(fork)
    }
}
