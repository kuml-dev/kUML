sysml2Model("Pipeline") {
    val start = stateDef("Start", isInitial = true)
    val par = stateDef("Parallel")
    val left = region(par, "Left")
    val right = region(par, "Right")
    val a1 = stateDef("Load", parent = par, region = left)
    val a2 = stateDef("Parse", parent = par, region = left)
    val b1 = stateDef("Fetch", parent = par, region = right)
    val b2 = stateDef("Cache", parent = par, region = right)
    val split = fork("Split")
    val merge = join("Merge")
    val done = stateDef("Done", isFinal = true)

    transition("begin", start, split)
    transition("toLoad", split, a1)
    transition("toFetch", split, b1)
    transition("loaded", a1, a2, trigger = "loaded")
    transition("fetched", b1, b2, trigger = "fetched")
    transition("fromParse", a2, merge)
    transition("fromCache", b2, merge)
    transition("finish", merge, done)

    stmDiagram("Pipeline - orthogonal regions") {
        include(start)
        include(split)
        include(par)
        include(merge)
        include(done)
    }
}
