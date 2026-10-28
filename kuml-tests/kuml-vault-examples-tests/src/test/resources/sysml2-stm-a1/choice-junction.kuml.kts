sysml2Model("Checkout") {
    val start = stateDef("Start", isInitial = true)
    val cart = stateDef("Cart")
    val decide = choice("Amount")
    val manual = stateDef("ManualReview")
    val auto = stateDef("AutoApproval")
    val merge = junction("Merge")
    val paid = stateDef("Paid", isFinal = true)

    transition("init", start, cart)
    transition("submit", cart, decide, trigger = "submit")
    transition("large", decide, manual, guard = "amount > 1000")
    transition("small", decide, auto)
    transition("manualOk", manual, merge, trigger = "approve")
    transition("autoOk", auto, merge)
    transition("pay", merge, paid, effect = "charge()")

    stmDiagram("Checkout - choice and junction") {
        include(start)
        include(cart)
        include(decide)
        include(manual)
        include(auto)
        include(merge)
        include(paid)
    }
}
