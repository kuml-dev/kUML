---
type: UmlClassDiagram
title: Domain Classes
tags: [shop, uml, class-diagram]
---

# Domain Classes

The core classes of the shop domain: `Customer`, `Order`, and `OrderItem`.

```kuml
classDiagram(name = "Shop Domain Classes") {
    showOperations = false

    val customer = classOf(name = "Customer") {
        attribute(name = "id", type = "UUID")
        attribute(name = "email", type = "String")
    }

    val order = classOf(name = "Order") {
        attribute(name = "id", type = "UUID")
        attribute(name = "status", type = "String")
    }

    val orderItem = classOf(name = "OrderItem") {
        attribute(name = "quantity", type = "Int")
        attribute(name = "unitPrice", type = "BigDecimal")
    }

    association(source = customer, target = order) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..*"); role = "orders" }
    }

    association(source = order, target = orderItem) {
        aggregation = AggregationKind.COMPOSITE
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "1..*"); role = "items" }
    }
}
```

Back to [Overview](../articles/01-ueberblick.md).
