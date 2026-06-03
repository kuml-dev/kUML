// order-overview.kuml.kts — UML 2.x interaction-overview diagram (V1.1).
// High-level control-flow of the checkout process, with each step referenced
// as a separate sequence/communication diagram.

interactionOverviewDiagram(name = "Order overview") {
    val init = initial()
    val login = interactionRef(name = "Login")
    val browse = interactionRef(name = "BrowseCatalog")
    val checkout = interactionRef(name = "Checkout")
    val confirmation = interactionRef(name = "Confirmation")
    val end = final()

    edge(from = init, to = login)
    edge(from = login, to = browse)
    edge(from = browse, to = checkout)
    edge(from = checkout, to = confirmation)
    edge(from = confirmation, to = end)
}
