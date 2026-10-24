import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "LibrarySystem") {

    // ── Actors ─────────────────────────────────────────────────────────────
    val reader = actorDef(name = "Reader")
    val librarian = actorDef(name = "Librarian")
    val paymentSystem = actorDef(name = "PaymentSystem")

    // ── Use Cases ──────────────────────────────────────────────────────────
    val borrowBook = useCaseDef(name = "BorrowBook")
    val returnBook = useCaseDef(name = "ReturnBook")
    val payLateFee = useCaseDef(name = "PayLateFee")
    val authenticate = useCaseDef(name = "Authenticate")

    // ── Use Case Diagram ───────────────────────────────────────────────────
    ucDiagram(name = "Library — top-level use cases") {
        // Nodes
        include(definition = reader)
        include(definition = librarian)
        include(definition = paymentSystem)
        include(definition = borrowBook)
        include(definition = returnBook)
        include(definition = payLateFee)
        include(definition = authenticate)

        // Associations: actor "participates in" use case
        association(actor = reader, useCase = borrowBook)
        association(actor = reader, useCase = returnBook)
        association(actor = reader, useCase = payLateFee)
        association(actor = librarian, useCase = borrowBook)
        association(actor = paymentSystem, useCase = payLateFee)

        // «include»: target is always executed as part of source
        include(source = borrowBook, target = authenticate)
        include(source = returnBook, target = authenticate)

        // «extend»: target's behaviour is optionally extended by source
        extend(source = payLateFee, target = returnBook)
    }
}
