// erm-idef1x.kuml.kts — Nacharbeit 126: IDEF1X-notation ERM for the LaTeX tests.
// Identifying and non-identifying relationships, a dependent (weak) entity, a complete category with a discriminator,
// the cardinality letters Z, 1 and P, an optional-parent diamond and one special-character name.

ermModel(name = "Shop") {
    var kindAttr = ""
    val customer =
        entity(name = "Customer_Account") {
            id()
            kindAttr = attribute(name = "kind_%", type = ErmDataType.Varchar(10), nullable = false)
        }
    val retail = entity(name = "Retail") { }
    val wholesale = entity(name = "Wholesale") { }
    val order =
        entity(name = "Order") {
            id()
            foreignKey(name = "customer_id", references = customer, nullable = false)
        }
    val item =
        entity(name = "OrderItem", weak = true) {
            foreignKey(name = "order_id", references = order, nullable = false)
            attribute(name = "quantity", type = ErmDataType.Integer(), nullable = false)
        }

    category(supertype = customer, subtypes = listOf(retail, wholesale), name = "Kind", complete = true, discriminator = kindAttr)

    relationship(
        from = order,
        to = item,
        name = "contains",
        sourceCardinality = Cardinality.ZERO_ONE,
        targetCardinality = Cardinality.ONE_MANY,
        kind = RelationshipKind.IDENTIFYING,
    )
    relationship(from = customer, to = order, name = "places", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = order, to = retail, name = "ships_to", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_ONE)
    relationship(from = item, to = wholesale, name = "bulk", sourceCardinality = Cardinality.ZERO_ONE, targetCardinality = Cardinality.ONE)

    diagram(name = "Overview", notation = ErmNotation.IDEF1X)
}
