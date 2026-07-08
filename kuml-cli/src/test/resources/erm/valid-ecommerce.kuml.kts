// valid-ecommerce.kuml.kts — V3.4.1 CLI smoke fixture: valid ERM schema
// exercising every first-class element (entity, weak entity, identifying
// relationship, foreign key, index, check constraint, view, notation).

ermModel("ECommerce") {
    val customer =
        entity("Customer") {
            id()
            attribute(name = "email", type = ErmDataType.Varchar(255), unique = true)
            index("email", unique = true, name = "idx_customer_email")
        }
    val order =
        entity("Order") {
            id()
            foreignKey(name = "customer_id", references = customer)
            attribute(name = "total", type = ErmDataType.Decimal(precision = 10, scale = 2))
            check(expression = "total >= 0", name = "non_negative_total")
        }
    val item =
        entity("OrderItem", weak = true) {
            foreignKey(name = "order_id", references = order)
            attribute(name = "quantity", type = ErmDataType.Integer())
        }

    relationship(from = customer, to = order, name = "places")
    relationship(from = order, to = item, name = "contains", kind = RelationshipKind.IDENTIFYING)

    view(name = "big_orders", query = "SELECT * FROM \"Order\" WHERE total > 100", references = listOf(order))

    diagram(name = "Overview", notation = ErmNotation.MARTIN, showIndexes = true)
}
