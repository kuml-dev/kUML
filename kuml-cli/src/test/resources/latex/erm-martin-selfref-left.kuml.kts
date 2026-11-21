// erm-martin-selfref-left.kuml.kts — Nacharbeit 129: a self-reference whose right-hand side is taken by another edge.
// Beta sits in a middle layer with nothing attached on its left, so the loop is drawn on the left of the entity.

ermModel(name = "Left Loop") {
    val alpha = entity(name = "Alpha") { id() }
    val delta = entity(name = "Delta") { id() }
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
    relationship(from = alpha, to = delta, name = "has", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = delta, to = gamma, name = "owns", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = beta, to = gamma, name = "feeds", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    diagram(name = "Overview") {
        layout { direction = Right }
    }
}
