import kotlin.time.Duration.Companion.seconds

// PAL wave 2D-a density fixture: parallel and antiparallel edges, a composite with several incoming edges,
// a 60 character guard and a named transition with trigger, timer, guard and effect.
sysml2Model("Density") {
    val start = stateDef("Start", isInitial = true)
    val a = stateDef("A")
    val b = stateDef("B")
    val c = stateDef("C")
    val aussen = stateDef("Aussen")
    val d = stateDef("D")
    val gehalten = stateDef("Gehalten")
    val k1 = stateDef("K1", parent = gehalten)
    val k2 = stateDef("K2", parent = gehalten)
    val k3 = stateDef("K3", parent = gehalten)

    transition("init", start, a)
    transition("T49", a, b, trigger = "alpha", guard = "x > 1", id = "T49")
    transition("T50", a, b, trigger = "beta", effect = "log()", id = "T50")
    transition("T52", b, c, trigger = "check", id = "T52")
    transition("T51", c, b, trigger = "gamma", id = "T51")
    transition(
        "T37",
        aussen,
        d,
        trigger = "tick",
        guard = "ready",
        effect = "advance()",
        timer = after(5.seconds),
        id = "T37",
        showName = true,
    )
    transition("in1", aussen, k1, trigger = "enter1", id = "in1")
    transition("in2", aussen, k2, trigger = "enter2", id = "in2")
    transition("in3", aussen, k3, trigger = "enter3", id = "in3")
    transition(
        "T28",
        k1,
        aussen,
        trigger = "exit1",
        guard = "stroemungOk and not stbAusgeloest and druckImBereich(p1,p22)",
        id = "T28",
    )

    stmDiagram("Density") {
        include(start)
        include(a)
        include(b)
        include(c)
        include(aussen)
        include(d)
        include(gehalten)
    }
}
