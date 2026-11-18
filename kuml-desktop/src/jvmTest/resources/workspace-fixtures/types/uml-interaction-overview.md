---
type: UmlInteractionOverview
title: uml-interaction-overview
---
# uml-interaction-overview

```kuml
interactionOverviewDiagram(name = "Order Process Overview") {
    val start    = initial()
    val login    = interactionRef(name = "Login")
    val search   = interactionRef(name = "Search Catalog")
    val checkout = interactionRef(name = "Checkout")
    val notify   = interactionRef(name = "Ship Notification")
    val end      = final()

    edge(from = start,    to = login)
    edge(from = login,    to = search)
    edge(from = search,   to = checkout)
    edge(from = checkout, to = notify)
    edge(from = notify,   to = end)
}
```
