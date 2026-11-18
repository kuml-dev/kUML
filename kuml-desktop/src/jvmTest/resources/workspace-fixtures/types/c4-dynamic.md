---
type: C4DynamicDiagram
title: c4-dynamic
---
# c4-dynamic

```kuml
c4Model(name = "Checkout — Dynamic") {
    val customer = person(name = "Customer")
    val web      = softwareSystem(name = "WebApp")
    val api      = softwareSystem(name = "API Server")
    val db       = softwareSystem(name = "OrderDB")

    dynamicDiagram(name = "Checkout Flow", description = "Bestellung abschicken") {
        interaction(description = "Submit order",   from = customer, to = web,
                    technology = "HTTPS")
        interaction(description = "POST /orders",   from = web,      to = api,
                    technology = "HTTPS/JSON")
        interaction(description = "INSERT order",   from = api,      to = db,
                    technology = "JDBC")
        response   (description = "ok",             from = db,       to = api)
        response   (description = "201 Created",    from = api,      to = web)
        response   (description = "Confirmation",   from = web,      to = customer)
    }
}
```
