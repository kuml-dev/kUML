sysml2Model("Library") {
    val user = actorDef("User")
    val reader = actorDef("Reader")
    val librarian = actorDef("Librarian")
    val borrow = useCaseDef("Borrow Book", description = "A reader borrows a book for up to four weeks. The system checks the account and records the loan.")
    val auth = useCaseDef("Authenticate")
    val fee = useCaseDef("Pay Late Fee")
    val manage = useCaseDef("Manage Catalogue", description = "Add, change or remove titles in the catalogue of the library system.")
    val export = useCaseDef("Export Report")
    ucDiagram("Library system") {
        include(user)
        include(reader)
        include(librarian)
        include(borrow)
        include(auth)
        include(fee)
        include(manage)
        include(export)
        generalize(reader, user)
        generalize(librarian, user)
        association(reader, borrow)
        association(librarian, manage)
        association(librarian, export)
        include(borrow, auth)
        extend(fee, borrow)
        subject("Lending") {
            include(borrow)
            include(auth)
            include(fee)
        }
        subject("Administration") {
            include(manage)
            include(export)
        }
    }
}
