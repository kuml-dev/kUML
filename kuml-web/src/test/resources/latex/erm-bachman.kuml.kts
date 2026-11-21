// erm-bachman.kuml.kts — Nacharbeit 126: Bachman-notation ERM for the LaTeX tests.
// Three entities (one weak), all four cardinality glyphs, one identifying and one non-identifying relationship, one note.

ermModel(name = "Shop") {
    val customer =
        entity(name = "Customer") {
            id()
            attribute(name = "e_mail", type = ErmDataType.Varchar(255), unique = true, nullable = false)
        }
    val order =
        entity(name = "Order") {
            id()
            foreignKey(name = "customer_id", references = customer, nullable = false)
            attribute(name = "placed_at", type = ErmDataType.Timestamp(), default = "now()")
        }
    val item =
        entity(name = "OrderItem", weak = true) {
            foreignKey(name = "order_id", references = order, nullable = false)
            attribute(name = "quantity", type = ErmDataType.Integer(), nullable = false)
        }

    relationship(from = customer, to = order, name = "places", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(
        from = order,
        to = item,
        name = "contains",
        sourceCardinality = Cardinality.ZERO_ONE,
        targetCardinality = Cardinality.ONE_MANY,
        kind = RelationshipKind.IDENTIFYING,
    )

    note("Orders are never deleted", order, placement = dev.kuml.core.model.note.NotePlacement.RIGHT)

    diagram(name = "Overview", notation = ErmNotation.BACHMAN)
}
