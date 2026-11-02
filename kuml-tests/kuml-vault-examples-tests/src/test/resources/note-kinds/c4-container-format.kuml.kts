import dev.kuml.c4.dsl.c4Model

c4Model(name = "Shop") {
    val user = person(name = "User")
    val shop = softwareSystem(name = "Shop")
    val pay = softwareSystem(name = "Payment") { external = true }
    relationship(source = user, target = shop) { description = "uses" }
    relationship(source = shop, target = pay) { description = "pays via" }
    note("A plain control note.", shop, kind = NoteKind.NOTE)
    note("**Decision:** orders are *immutable* once paid, see [**the ADR**](https://kuml.dev/adr).", shop, kind = NoteKind.DECISION)
    note("Unsafe: [a](data:text/html,x) and [b](vbscript:x) stay literal.", user, pay, id = "twoNote", kind = NoteKind.INFO)
    systemContextDiagram(name = "Context") {
        include(user, shop, pay)
        note("Question: should refunds be `separate`?\n- yes\n- no", id = "freeNote", kind = NoteKind.QUESTION)
    }
}
