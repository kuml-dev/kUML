---
type: ErmDiagram
title: erm-chen
---
# erm-chen

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
    diagram(name = "Fixture", notation = ErmNotation.CHEN)
}
```
