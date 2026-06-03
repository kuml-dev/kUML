// order-checkout.kuml.kts — UML 2.x activity diagram (V1.1).
// Order-checkout flow with a decision branch.

activityDiagram(name = "Order checkout") {
    val start = initialNode()
    val validate = action(name = "Validate cart")
    val ok = decision(name = "valid?")
    val charge = action(name = "Charge card")
    val notify = action(name = "Send confirmation")
    val reject = action(name = "Show error")
    val end = finalNode()

    edge(from = start, to = validate)
    edge(from = validate, to = ok)
    edge(from = ok, to = charge, guard = "valid")
    edge(from = ok, to = reject, guard = "invalid")
    edge(from = charge, to = notify)
    edge(from = notify, to = end)
    edge(from = reject, to = end)
}
