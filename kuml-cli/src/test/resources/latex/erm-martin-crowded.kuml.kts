// erm-martin-crowded.kuml.kts — Nacharbeit 130: one entity with several named relationships that carry role labels at both ends,
// so names and roles compete for the space beside the lines (the LaTeX output plans them with the SVG label planner).

ermModel(name = "Crowded Labels") {
    val customer = entity(name = "Customer") { id() }
    val order = entity(name = "Order") { id() }
    val invoice = entity(name = "Invoice") { id() }
    val address = entity(name = "Address") { id() }
    val ticket = entity(name = "Ticket") { id() }
    relationship(
        from = customer,
        to = order,
        name = "places",
        sourceRole = "buyer",
        targetRole = "purchases",
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )
    relationship(
        from = customer,
        to = invoice,
        name = "is billed with",
        sourceRole = "payer",
        targetRole = "bills",
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )
    relationship(
        from = customer,
        to = address,
        name = "lives at",
        sourceRole = "resident",
        targetRole = "residence",
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ONE_MANY,
    )
    relationship(
        from = customer,
        to = ticket,
        name = "opens",
        sourceRole = "requester",
        targetRole = "requests",
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )
    relationship(
        from = order,
        to = invoice,
        name = "results in",
        sourceRole = "origin",
        targetRole = "charge",
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ZERO_ONE,
    )
    diagram(name = "Overview")
}
