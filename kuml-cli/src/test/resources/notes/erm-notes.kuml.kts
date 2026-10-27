import dev.kuml.erm.dsl.ermModel

// Fixture: ERM note.
ermModel(name = "NoteDemo") {
    val customer =
        entity(name = "Customer") {
            id()
        }
    note("annotated", customer, id = "n1")
}
