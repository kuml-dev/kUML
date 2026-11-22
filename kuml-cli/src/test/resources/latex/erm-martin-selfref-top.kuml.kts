// erm-martin-selfref-top.kuml.kts — Nacharbeit 132: a self-reference whose right- and left-hand sides are both taken by
// other edges, so the loop is drawn above (or, without room above, below) the entity with name and roles.

ermModel(name = "Top Loop") {
    val alpha = entity(name = "Alpha") { id() }
    val beta =
        entity(name = "Beta") {
            id()
            attribute(name = "name", type = ErmDataType.Varchar(120))
        }
    val gamma = entity(name = "Gamma") { id() }
    relationship(
        from = beta,
        to = beta,
        name = "parent of",
        sourceRole = "parent",
        targetRole = "child",
        sourceCardinality = Cardinality.ZERO_ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )
    relationship(from = alpha, to = beta, name = "feeds", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = beta, to = gamma, name = "owns", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    diagram(name = "Overview") {
        layout { direction = Right }
    }
}
