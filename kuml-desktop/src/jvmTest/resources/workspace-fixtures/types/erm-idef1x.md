---
type: ErmDiagram
title: erm-idef1x
---
# erm-idef1x

```kuml
ermModel(name = "Fixture") {
    val customer =
        entity(name = "Customer") {
            id()
            attribute(name = "email", type = ErmDataType.Varchar(255), unique = true, nullable = false)
        }
    val order =
        entity(name = "Order") {
            id()
            foreignKey(name = "customer_id", references = customer, nullable = false)
            attribute(name = "total", type = ErmDataType.Decimal(precision = 10, scale = 2))
        }
    relationship(from = customer, to = order, sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    note(text = "Fixture note: index the email.", customer, placement = NotePlacement.RIGHT)
    diagram(name = "Fixture", notation = ErmNotation.IDEF1X)
}
```
