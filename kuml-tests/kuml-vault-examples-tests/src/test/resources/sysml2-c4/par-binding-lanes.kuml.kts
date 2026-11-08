import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection.In
import dev.kuml.sysml2.ConstraintParameterDirection.Out
import dev.kuml.sysml2.dsl.sysml2Model

// Nacharbeit 41: many bindings inside one composite constraint (lanes, corridors, approach columns, shared pins).
sysml2Model(name = "BindingLanes") {
    attributeDef(name = "Real")

    val outer = constraintDef(
        name = "Outer",
        expression = "o = f(i1, i2, i3, i4)",
        parameters = listOf(
            ConstraintParameter(name = "i1", typeId = "Real", direction = In),
            ConstraintParameter(name = "i2", typeId = "Real", direction = In),
            ConstraintParameter(name = "i3", typeId = "Real", direction = In),
            ConstraintParameter(name = "i4", typeId = "Real", direction = In),
            ConstraintParameter(name = "o1", typeId = "Real", direction = Out),
        ),
    )
    val a = constraintDef(
        name = "StageA",
        expression = "a_out = a1 + a2",
        parameters = listOf(
            ConstraintParameter(name = "a1", typeId = "Real", direction = In),
            ConstraintParameter(name = "a2", typeId = "Real", direction = In),
            ConstraintParameter(name = "a_out", typeId = "Real", direction = Out),
        ),
        parent = outer,
    )
    val b = constraintDef(
        name = "StageB",
        expression = "b_out = b1 + b2 + b3 + b4 + b5",
        parameters = listOf(
            ConstraintParameter(name = "b1", typeId = "Real", direction = In),
            ConstraintParameter(name = "b2", typeId = "Real", direction = In),
            ConstraintParameter(name = "b3", typeId = "Real", direction = In),
            ConstraintParameter(name = "b4", typeId = "Real", direction = In),
            ConstraintParameter(name = "b5", typeId = "Real", direction = In),
            ConstraintParameter(name = "b_out", typeId = "Real", direction = Out),
        ),
        parent = outer,
    )

    // Owner to child: three to the same child and side, one to the other child
    bind(name = "i1_b1", source = outer.pin("i1"), target = b.pin("b1"))
    bind(name = "i2_b2", source = outer.pin("i2"), target = b.pin("b2"))
    bind(name = "i3_b3", source = outer.pin("i3"), target = b.pin("b3"))
    bind(name = "i4_a1", source = outer.pin("i4"), target = a.pin("a1"))
    // Siblings: two short routes sharing a pin, one long route back
    bind(name = "a_to_b4", source = a.pin("a_out"), target = b.pin("b4"))
    bind(name = "a_to_b5", source = a.pin("a_out"), target = b.pin("b5"))
    bind(name = "b_to_a2", source = b.pin("b_out"), target = a.pin("a2"))
    // Child to owner
    bind(name = "b_to_o1", source = b.pin("b_out"), target = outer.pin("o1"))

    parDiagram(name = "Binding lanes") {
        include(definition = outer)
        include(definition = a)
        include(definition = b)
    }
}
