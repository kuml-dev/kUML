// erm-bachman-selfref.kuml.kts — Nacharbeit 127: an ERM self-reference (bachman notation) for the LaTeX tests.
ermModel(name = "Staff") {
    val employee =
        entity(name = "Employee") {
            id()
            attribute(name = "name", type = ErmDataType.Varchar(120), nullable = false)
            attribute(name = "manager_id", type = ErmDataType.Uuid)
        }

    relationship(
        from = employee,
        to = employee,
        name = "manages",
        sourceRole = "manager",
        targetRole = "report",
        sourceCardinality = Cardinality.ZERO_ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )

    diagram(name = "Overview", notation = ErmNotation.BACHMAN)
}
