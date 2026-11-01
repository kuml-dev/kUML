sysml2Model("Same layer") {
    val a = partDef("Alpha")
    val b = partDef("Beta")
    val c = partDef("Gamma") {
        part("alpha", a.id)
    }
    val d = partDef("Delta") {
        part("beta", b.id)
    }
    bdd("Same layer with an edge") {
        include(a)
        include(b)
        include(c)
        include(d)
        layout {
            sameLayer(a, c)
        }
    }
}
