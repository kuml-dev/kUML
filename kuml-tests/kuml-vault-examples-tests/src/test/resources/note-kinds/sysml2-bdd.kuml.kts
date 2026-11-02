sysml2Model("Note kinds") {
    val vehicle = partDef("Vehicle")
    val engine = partDef("Engine")
    bdd("Note kinds") {
        include(vehicle)
        include(engine)
        comment("A plain note without a symbol.", vehicle, kind = NoteKind.NOTE)
        comment("Information about the order lifecycle.", vehicle, kind = NoteKind.INFO)
        comment("Hint: call validate() before save().", vehicle, kind = NoteKind.HINT)
        comment("Warning: totals are not rounded here.", vehicle, kind = NoteKind.WARNING)
        comment("Error: the order id must never be null.", vehicle, kind = NoteKind.ERROR)
        comment("Important: payment runs before shipping.", vehicle, kind = NoteKind.IMPORTANT)
        comment("Success: all invariants hold for this class.", vehicle, kind = NoteKind.SUCCESS)
        comment("Question: should refunds be a separate class?", vehicle, kind = NoteKind.QUESTION)
        comment("Todo: add the discount rules, <b>soon</b> & carefully.", vehicle, kind = NoteKind.TODO)
        comment("Decision: orders are immutable once paid.", vehicle, kind = NoteKind.DECISION)
        comment("Assumption: one currency per order.", vehicle, kind = NoteKind.ASSUMPTION)
        comment("Risk: a large order may exceed the gateway limit.", vehicle, kind = NoteKind.RISK)
        comment("Example: Order(id=7, total=19.90).", vehicle, kind = NoteKind.EXAMPLE)
        comment("Reference: see the billing guide chapter 4.", vehicle, kind = NoteKind.REFERENCE)
        comment("Deprecated: use Invoice instead of Bill.", vehicle, kind = NoteKind.DEPRECATED)
    }
}
