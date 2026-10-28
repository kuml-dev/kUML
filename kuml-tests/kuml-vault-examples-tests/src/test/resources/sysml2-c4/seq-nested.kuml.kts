import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "OrderPayment") {
    val customer = lifelineDef(name = "Customer")
    val shop = lifelineDef(name = "Shop")
    val payment = lifelineDef(name = "Payment")
    val bank = lifelineDef(name = "Bank")

    foundMessage(label = "newOrder", target = shop, seqNo = 0)
    message(label = "charge(total)", source = shop, target = payment, seqNo = 1, kind = MessageKind.Sync)
    message(label = "authorize", source = payment, target = bank, seqNo = 2, kind = MessageKind.Sync)

    combinedFragment(name = "result", operator = CombinedFragmentOperator.Alt) {
        operand(guard = "approved", start = 3, end = 6) {
            combinedFragment(name = "receipts", operator = CombinedFragmentOperator.Loop) {
                operand(guard = "for each item", start = 5, end = 6)
            }
        }
        operand(guard = "else", start = 7, end = 8)
    }
    message(label = "approved", source = bank, target = payment, seqNo = 3, kind = MessageKind.Reply)
    message(label = "paid", source = payment, target = shop, seqNo = 4, kind = MessageKind.Reply)
    message(label = "receipt()", source = shop, target = customer, seqNo = 5, kind = MessageKind.Sync)
    message(label = "ack", source = customer, target = shop, seqNo = 6, kind = MessageKind.Reply)
    message(label = "declined", source = payment, target = shop, seqNo = 7, kind = MessageKind.Reply)
    lostMessage(label = "notifyLater", source = shop, seqNo = 8)

    interactionUse(name = "audit", refersTo = "AuditTrail", lifelines = listOf(shop, payment), seqNo = 10)
    durationConstraint(lifeline = payment, from = 1, to = 4, expression = "t <= 2 s")

    seqDiagram(name = "Order payment") {
        include(customer)
        include(shop)
        include(payment)
        include(bank)
    }
}
