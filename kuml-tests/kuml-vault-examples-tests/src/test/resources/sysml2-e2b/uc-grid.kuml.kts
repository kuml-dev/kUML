// Wave 2E-b fixture: five actors, thirteen use cases that only appear inside the subject frame, four columns,
// an extend with condition and extension point.
sysml2Model("Shop") {
    val customer = actorDef("Customer")
    val guest = actorDef("Guest")
    val clerk = actorDef("Clerk")
    val admin = actorDef("Administrator")
    val payment = actorDef("Payment Provider")

    val search = useCaseDef("Search Catalogue")
    val browse = useCaseDef("Browse Category")
    val cart = useCaseDef("Fill Cart")
    val checkout = useCaseDef("Check Out")
    val pay = useCaseDef("Pay Order")
    val voucher = useCaseDef("Redeem Voucher")
    val ship = useCaseDef("Ship Order")
    val track = useCaseDef("Track Parcel")
    val ret = useCaseDef("Return Item")
    val refund = useCaseDef("Refund Payment")
    val stock = useCaseDef("Maintain Stock")
    val prices = useCaseDef("Set Prices")
    val report = useCaseDef("Export Report")

    ucDiagram("Web shop") {
        include(customer)
        include(guest)
        include(clerk)
        include(admin)
        include(payment)
        subject("Shop") {
            include(search)
            include(browse)
            include(cart)
            include(checkout)
            include(pay)
            include(voucher)
            include(ship)
            include(track)
            include(ret)
            include(refund)
            include(stock)
            include(prices)
            include(report)
        }
        association(guest, search)
        association(guest, browse)
        association(customer, cart)
        association(customer, checkout)
        association(customer, track)
        association(clerk, ship)
        association(clerk, stock)
        association(admin, prices)
        association(admin, report)
        association(payment, pay)
        association(payment, refund)
        include(checkout, pay)
        extend(voucher, pay, condition = "customer owns a voucher", extensionPoint = "discount")
        extend(refund, ret, condition = "item was paid")
        layout {
            wrapColumns = 4
        }
    }
}
