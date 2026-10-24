// erm-martin-ecommerce.kuml.kts — synced from vault example
// Source of truth: 03 Bereiche/kUML/Beispiele/39 ERM Martin – E-Commerce Schema.md
// Do not edit by hand; update the vault note and re-sync.

ermModel("E-Commerce Schema") {
    val customer =
        entity("Customer") {
            id()
            attribute(name = "email", type = ErmDataType.Varchar(255), unique = true, nullable = false)
            attribute(name = "name", type = ErmDataType.Varchar(255))
            attribute(name = "created_at", type = ErmDataType.Timestamp(), default = "now()")
        }

    val address =
        entity("Address") {
            id()
            foreignKey(name = "customer_id", references = customer, nullable = false)
            attribute(name = "street", type = ErmDataType.Varchar(255))
            attribute(name = "city", type = ErmDataType.Varchar(120))
            attribute(name = "zip", type = ErmDataType.Varchar(20))
        }

    val category =
        entity("Category") {
            id()
            attribute(name = "name", type = ErmDataType.Varchar(120))
            // Self-reference: der eigene id-String ist im Entity-Block noch nicht
            // verfügbar, daher hier nur ein normales Attribut — die Kante kommt
            // unten als separates relationship(from = category, to = category, …).
            attribute(name = "parent_id", type = ErmDataType.Uuid, nullable = true)
        }

    val product =
        entity("Product") {
            id()
            foreignKey(name = "category_id", references = category, nullable = false)
            attribute(name = "sku", type = ErmDataType.Varchar(64), unique = true)
            attribute(name = "name", type = ErmDataType.Varchar(255))
            attribute(name = "price", type = ErmDataType.Decimal(10, 2), nullable = false)
            check(expression = "price >= 0", name = "positive_price")
            index("sku", unique = true, name = "idx_product_sku")
        }

    val order =
        entity("Order") {
            id()
            foreignKey(name = "customer_id", references = customer, nullable = false)
            // Zwei Fremdschlüssel auf dieselbe Zieltabelle (Address) — Rechnungs-
            // und Lieferadresse. Unten als zwei relationship(...)-Aufrufe mit
            // unterschiedlichem targetRole abgebildet.
            foreignKey(name = "billing_address_id", references = address, nullable = false)
            foreignKey(name = "shipping_address_id", references = address, nullable = false)
            attribute(name = "status", type = ErmDataType.Varchar(32), default = "'pending'")
            attribute(name = "placed_at", type = ErmDataType.Timestamp(), default = "now()")
            index("status", name = "idx_order_status")
        }

    // Schwache Entität / identifizierende Beziehung: OrderItem existiert nur im
    // Kontext einer Order (Komposition, ON DELETE CASCADE).
    val orderItem =
        entity("OrderItem", weak = true) {
            foreignKey(name = "order_id", references = order, onDelete = ReferentialAction.CASCADE, nullable = false)
            foreignKey(name = "product_id", references = product, nullable = false)
            attribute(name = "quantity", type = ErmDataType.Integer(), nullable = false)
            attribute(name = "unit_price", type = ErmDataType.Decimal(10, 2), nullable = false)
        }

    val payment =
        entity("Payment") {
            id()
            foreignKey(name = "order_id", references = order, nullable = false)
            attribute(name = "amount", type = ErmDataType.Decimal(10, 2), nullable = false)
            attribute(name = "method", type = ErmDataType.Varchar(32))
            attribute(name = "paid_at", type = ErmDataType.Timestamp())
        }

    // Drei Fremdschlüssel auf drei verschiedene Zieltabellen aus einer einzigen Entität.
    val review =
        entity("Review") {
            id()
            foreignKey(name = "customer_id", references = customer, nullable = false)
            foreignKey(name = "product_id", references = product, nullable = false)
            foreignKey(name = "order_id", references = order, nullable = false)
            attribute(name = "rating", type = ErmDataType.Integer(bits = 16), nullable = false)
            attribute(name = "comment", type = ErmDataType.Text)
            check(expression = "rating BETWEEN 1 AND 5", name = "rating_range")
        }

    // Jede oben deklarierte Fremdschlüsselspalte bekommt hier ihre eigene,
    // gerenderte relationship(...)-Kante (die ERM-Layout-Bridge zeichnet
    // ausschließlich model.relationships, nicht automatisch aus foreignKey()).
    relationship(from = customer, to = address, name = "has", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(
        from = category,
        to = category,
        name = "subcategory of",
        sourceRole = "parent",
        targetRole = "child",
        sourceCardinality = Cardinality.ZERO_ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )
    relationship(from = category, to = product, name = "groups", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = customer, to = order, name = "places", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(
        from = address,
        to = order,
        name = "bills",
        targetRole = "billing",
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )
    relationship(
        from = address,
        to = order,
        name = "ships",
        targetRole = "shipping",
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )
    relationship(
        from = order,
        to = orderItem,
        name = "contains",
        kind = RelationshipKind.IDENTIFYING,
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ONE_MANY,
    )
    relationship(from = product, to = orderItem, name = "ordered as", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = order, to = payment, name = "paid by", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = customer, to = review, name = "writes", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = product, to = review, name = "reviewed in", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = order, to = review, name = "reviewed via", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)

    view(
        name = "active_orders",
        query = "SELECT o.id, o.status, o.placed_at, c.email FROM \"order\" o JOIN customer c ON c.id = o.customer_id WHERE o.status != 'cancelled'",
        references = listOf(order, customer),
    )

    diagram(name = "E-Commerce Schema", notation = ErmNotation.MARTIN, showIndexes = true)
}
