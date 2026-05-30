// checkout.kuml.kts — Beispiel für ein UML-Use-Case-Diagramm in kUML
// Zeigt: Actors, Use Cases, Subject, Include, Extend, Actor-Generalisation

useCaseDiagram("Online Shop — Checkout") {

    // Akteure
    val customer    = actor("Customer")
    val vipCustomer = actor("VIP Customer")
    val paymentSvc  = actor("Payment Service") {
        stereotypes += "external"
    }

    // Use Cases
    val placeOrder     = useCase("Place Order")
    val validateCart   = useCase("Validate Cart")
    val applyDiscount  = useCase("Apply Discount")
    val processPayment = useCase("Process Payment")

    // Systemgrenze
    subject("Online Shop", placeOrder, validateCart, applyDiscount, processPayment)

    // Akteur ↔ Use Case
    association(source = customer,    target = placeOrder)
    association(source = paymentSvc,  target = processPayment)

    // VIP ist eine Spezialisierung von Customer
    generalization(specific = vipCustomer, general = customer)

    // Include / Extend
    include(base = placeOrder, addition = validateCart)
    include(base = placeOrder, addition = processPayment)
    extend(base  = placeOrder, extension = applyDiscount, at = "PaymentChosen")
}
