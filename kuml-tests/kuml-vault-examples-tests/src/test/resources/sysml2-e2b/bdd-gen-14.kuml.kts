sysml2Model("Many") {
    val base = partDef("Base", isAbstract = true)
    val specs = (1..14).map { partDef("Variant$it", specializesId = base.id) }
    bdd("Fourteen specialisations") {
        include(base)
        specs.forEach { include(it) }
        layout {
            wrapColumns = 5
        }
    }
}
