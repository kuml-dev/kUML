@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Library System — SysML 2 Use Case Diagram example (V2.0.7 MVP).
 *
 * Illustriert die V2.0.7-Oberfläche end-to-end:
 *  - Actor-Definitions (`Reader`, `Librarian`, `PaymentSystem`)
 *  - UseCase-Definitions (`BorrowBook`, `ReturnBook`, `PayLateFee`,
 *    `Authenticate`)
 *  - Actor-zu-UseCase-Assoziationen (`association(...)`)
 *  - `«include»`-Beziehungen zwischen UseCases (`include(uc, uc)`)
 *  - `«extend»`-Beziehungen zwischen UseCases (`extend(uc, uc)`)
 *
 * Domain: ein klassisches Bibliotheks-System mit zwei menschlichen Akteuren
 * (Leser + Bibliothekar) und einem externen System (Zahlungsdienst). Der
 * Borrow-Flow inkludiert immer `Authenticate`; `PayLateFee` extendet
 * optional `ReturnBook`, wenn beim Rückgabevorgang noch Gebühren offen sind.
 *
 * Out of V2.0.7 scope (siehe Wave-Plan):
 *  - System-Boundary-Frame um die UseCases
 *  - `«include»`/`«extend»`-Stereotyp-Labels (V2.x SVG/TikZ-Polish)
 *  - Actor-Spezialisierungs-Pfeile, UseCase-Generalisierung
 */
sysml2Model("LibrarySystem") {

    // ── Actors ─────────────────────────────────────────────────────────────
    val reader = actorDef("Reader")
    val librarian = actorDef("Librarian")
    val paymentSystem = actorDef("PaymentSystem")

    // ── Use Cases ──────────────────────────────────────────────────────────
    val borrowBook = useCaseDef("BorrowBook")
    val returnBook = useCaseDef("ReturnBook")
    val payLateFee = useCaseDef("PayLateFee")
    val authenticate = useCaseDef("Authenticate")

    // ── Use Case Diagram ───────────────────────────────────────────────────
    ucDiagram("Library — top-level use cases") {
        // Nodes
        include(reader)
        include(librarian)
        include(paymentSystem)
        include(borrowBook)
        include(returnBook)
        include(payLateFee)
        include(authenticate)

        // Associations: actor "participates in" use case
        association(reader, borrowBook)
        association(reader, returnBook)
        association(reader, payLateFee)
        association(librarian, borrowBook)
        association(paymentSystem, payLateFee)

        // «include»: target is always executed as part of source
        include(borrowBook, authenticate)
        include(returnBook, authenticate)

        // «extend»: target's behaviour is optionally extended by source
        extend(payLateFee, returnBook)
    }
}
