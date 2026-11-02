import dev.kuml.c4.dsl.c4Model

c4Model(name = "Shop") {
    val user = person(name = "User")
    val shop = softwareSystem(name = "Shop")
    val pay = softwareSystem(name = "Payment") { external = true }
    relationship(source = user, target = shop) { description = "uses" }
    relationship(source = shop, target = pay) { description = "pays via" }
    note("A plain note without a symbol.", shop, kind = NoteKind.NOTE)
    note("Warning: totals are not rounded here.", shop, kind = NoteKind.WARNING)
    note("Error: the order id must never be null.", shop, kind = NoteKind.ERROR)
    note("Decision: orders are immutable once paid, <b>always</b> & forever.", shop, kind = NoteKind.DECISION)
    note("Todo: add the discount rules.", shop, kind = NoteKind.TODO)
    note("Between two systems.", user, pay, id = "twoNote", kind = NoteKind.INFO)
    systemContextDiagram(name = "Context") {
        include(user, shop, pay)
        note("Free-standing note in the diagram", id = "freeNote", kind = NoteKind.QUESTION)
    }
}
