import dev.kuml.sysml2.dsl.sysml2Model

// Fixture: a comment whose anchor id does not exist (KUML-NOTE-W001), plus a transition note in a BDD (KUML-NOTE-W002).
sysml2Model(name = "AnchorDemo") {
    val a = stateDef(name = "A")
    val b = stateDef(name = "B")
    val t = transition(name = "t", source = a, target = b)
    val blk = partDef(name = "Blk")
    bdd(name = "Overview") {
        include(definition = blk)
        comment(text = "SECRET-NOTE-BODY", "Gibtsnicht", id = "ghost")
        comment(text = "SECRET-NOTE-BODY-2", firstAnchor = t, id = "ontransition")
    }
}
