import dev.kuml.c4.dsl.c4Model

// Fixture: C4 notes (P-COM-04) — one anchored to a system, one anchored to two elements, one free-standing in the diagram.
c4Model(name = "NoteDemo") {
    val user = person(name = "User")
    val shop = softwareSystem(name = "Shop")
    val pay = softwareSystem(name = "Payment") { external = true }
    relationship(source = user, target = shop) { description = "uses" }
    relationship(source = shop, target = pay) { description = "pays via" }
    note("ANNAHME A1: Test", shop)
    note("Between two", user, pay, id = "twoNote")
    systemContextDiagram(name = "Context") {
        include(user, shop, pay)
        note("Free-standing note in the diagram", id = "freeNote")
    }
}
