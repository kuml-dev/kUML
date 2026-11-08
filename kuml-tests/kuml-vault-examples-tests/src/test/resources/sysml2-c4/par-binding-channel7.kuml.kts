import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection.In
import dev.kuml.sysml2.dsl.sysml2Model

// Nacharbeit 41: seven bindings through one channel (the old lane counter wrapped after five).
sysml2Model(name = "BindingChannel7") {
    attributeDef(name = "Real")

    val outer = constraintDef(
        name = "Outer7",
        expression = "r = sum(i1..i7)",
        parameters = listOf(
            ConstraintParameter(name = "i1", typeId = "Real", direction = In),
            ConstraintParameter(name = "i2", typeId = "Real", direction = In),
            ConstraintParameter(name = "i3", typeId = "Real", direction = In),
            ConstraintParameter(name = "i4", typeId = "Real", direction = In),
            ConstraintParameter(name = "i5", typeId = "Real", direction = In),
            ConstraintParameter(name = "i6", typeId = "Real", direction = In),
            ConstraintParameter(name = "i7", typeId = "Real", direction = In),
        ),
    )
    val inner = constraintDef(
        name = "Inner7",
        expression = "s = sum(c1..c7)",
        parameters = listOf(
            ConstraintParameter(name = "c1", typeId = "Real", direction = In),
            ConstraintParameter(name = "c2", typeId = "Real", direction = In),
            ConstraintParameter(name = "c3", typeId = "Real", direction = In),
            ConstraintParameter(name = "c4", typeId = "Real", direction = In),
            ConstraintParameter(name = "c5", typeId = "Real", direction = In),
            ConstraintParameter(name = "c6", typeId = "Real", direction = In),
            ConstraintParameter(name = "c7", typeId = "Real", direction = In),
        ),
        parent = outer,
    )

    bind(name = "i1_c1", source = outer.pin("i1"), target = inner.pin("c1"))
    bind(name = "i2_c2", source = outer.pin("i2"), target = inner.pin("c2"))
    bind(name = "i3_c3", source = outer.pin("i3"), target = inner.pin("c3"))
    bind(name = "i4_c4", source = outer.pin("i4"), target = inner.pin("c4"))
    bind(name = "i5_c5", source = outer.pin("i5"), target = inner.pin("c5"))
    bind(name = "i6_c6", source = outer.pin("i6"), target = inner.pin("c6"))
    bind(name = "i7_c7", source = outer.pin("i7"), target = inner.pin("c7"))

    parDiagram(name = "Channel with seven lanes") {
        include(definition = outer)
        include(definition = inner)
    }
}
