classDiagram(name = "Note kinds") {
    val order = classOf(name = "Order") {
        attribute(name = "id", type = "String")
    }
    val line = classOf(name = "OrderLine") {
        attribute(name = "qty", type = "Int")
    }
    association(source = order, target = line)
    comment(text = "A plain note without a symbol.", order, kind = NoteKind.NOTE)
    comment(text = "Information about the order lifecycle.", order, kind = NoteKind.INFO)
    comment(text = "Hint: call validate() before save().", order, kind = NoteKind.HINT)
    comment(text = "Warning: totals are not rounded here.", order, kind = NoteKind.WARNING)
    comment(text = "Error: the order id must never be null.", order, kind = NoteKind.ERROR)
    comment(text = "Important: payment runs before shipping.", order, kind = NoteKind.IMPORTANT)
    comment(text = "Success: all invariants hold for this class.", order, kind = NoteKind.SUCCESS)
    comment(text = "Question: should refunds be a separate class?", order, kind = NoteKind.QUESTION)
    comment(text = "Todo: add the discount rules, <b>soon</b> & carefully.", order, kind = NoteKind.TODO)
    comment(text = "Decision: orders are immutable once paid.", order, kind = NoteKind.DECISION)
    comment(text = "Assumption: one currency per order.", order, kind = NoteKind.ASSUMPTION)
    comment(text = "Risk: a large order may exceed the gateway limit.", order, kind = NoteKind.RISK)
    comment(text = "Example: Order(id=7, total=19.90).", order, kind = NoteKind.EXAMPLE)
    comment(text = "Reference: see the billing guide chapter 4.", order, kind = NoteKind.REFERENCE)
    comment(text = "Deprecated: use Invoice instead of Bill.", order, kind = NoteKind.DEPRECATED)
}
