import dev.kuml.sysml2.dsl.sysml2Model

// Fixture: SysML 2 comments, one of them longer than the display limit (NOTE_TRUNCATED).
sysml2Model(name = "NoteDemo") {
    val vehicle = partDef(name = "Vehicle")
    bdd(name = "Overview") {
        include(definition = vehicle)
        comment(text = "short note", "Vehicle")
        comment(text = "long ".repeat(600), "Vehicle", id = "longNote")
    }
}
