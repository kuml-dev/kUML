@file:Suppress("unused")

import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.dsl.sysml2Model

// V2.0.20b — Newton's Second Law PAR fixture for validate --strict smoke test.
sysml2Model("NewtonModel") {
    attributeDef("Mass")
    attributeDef("Acceleration")
    attributeDef("Force")

    val newton =
        constraintDef(
            name = "NewtonsLaw",
            expression = "F = m * a",
            parameters =
                listOf(
                    ConstraintParameter("F", "Force", ConstraintParameterDirection.Out),
                    ConstraintParameter("m", "Mass", ConstraintParameterDirection.In),
                    ConstraintParameter("a", "Acceleration", ConstraintParameterDirection.In),
                ),
        )

    val vehicle =
        partDef("Vehicle") {
            attribute("mass", "Mass")
            attribute("acceleration", "Acceleration")
            attribute("force", "Force")
        }

    bind("F_to_force", "NewtonsLaw::F", "Vehicle::force")
    bind("m_to_mass", "NewtonsLaw::m", "Vehicle::mass")
    bind("a_to_acceleration", "NewtonsLaw::a", "Vehicle::acceleration")

    parDiagram("Newton — F = m·a applied to Vehicle") {
        include(newton)
        include(vehicle)
    }
}
