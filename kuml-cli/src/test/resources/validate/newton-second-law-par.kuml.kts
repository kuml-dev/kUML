@file:Suppress("unused")

import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.dsl.sysml2Model

// V2.0.20b — Newton's Second Law PAR fixture for validate --strict smoke test.
sysml2Model(name = "NewtonModel") {
    attributeDef(name = "Mass")
    attributeDef(name = "Acceleration")
    attributeDef(name = "Force")

    val newton =
        constraintDef(
            name = "NewtonsLaw",
            expression = "F = m * a",
            parameters =
                listOf(
                    ConstraintParameter(name = "F", typeId = "Force", direction = ConstraintParameterDirection.Out),
                    ConstraintParameter(name = "m", typeId = "Mass", direction = ConstraintParameterDirection.In),
                    ConstraintParameter(name = "a", typeId = "Acceleration", direction = ConstraintParameterDirection.In),
                ),
        )

    val vehicle =
        partDef(name = "Vehicle") {
            attribute(name = "mass", typeId = "Mass")
            attribute(name = "acceleration", typeId = "Acceleration")
            attribute(name = "force", typeId = "Force")
        }

    bind(name = "F_to_force", source = "NewtonsLaw::F", target = "Vehicle::force")
    bind(name = "m_to_mass", source = "NewtonsLaw::m", target = "Vehicle::mass")
    bind(name = "a_to_acceleration", source = "NewtonsLaw::a", target = "Vehicle::acceleration")

    parDiagram(name = "Newton — F = m·a applied to Vehicle") {
        include(newton)
        include(vehicle)
    }
}
