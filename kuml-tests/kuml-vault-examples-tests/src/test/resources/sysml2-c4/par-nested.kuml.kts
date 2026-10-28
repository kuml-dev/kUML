import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection.In
import dev.kuml.sysml2.ConstraintParameterDirection.Out
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "VehicleDynamicsModel") {
    attributeDef(name = "Mass")
    attributeDef(name = "Force")
    attributeDef(name = "Speed")
    attributeDef(name = "Acceleration")

    val dynamics = constraintDef(
        name = "VehicleDynamics",
        expression = "a = (F_drive - k * v^2) / m",
        parameters = listOf(
            ConstraintParameter(name = "m", typeId = "Mass", direction = In),
            ConstraintParameter(name = "F_drive", typeId = "Force", direction = In),
            ConstraintParameter(name = "v", typeId = "Speed", direction = In),
            ConstraintParameter(name = "a", typeId = "Acceleration", direction = Out),
        ),
    )
    val drag = constraintDef(
        name = "Drag",
        expression = "F_net = F_drive - k * v^2",
        parameters = listOf(
            ConstraintParameter(name = "F_drive", typeId = "Force", direction = In),
            ConstraintParameter(name = "v", typeId = "Speed", direction = In),
            ConstraintParameter(name = "F_net", typeId = "Force", direction = Out),
        ),
        parent = dynamics,
    )
    val newton = constraintDef(
        name = "NewtonsLaw",
        expression = "a = F / m",
        parameters = listOf(
            ConstraintParameter(name = "F", typeId = "Force", direction = In),
            ConstraintParameter(name = "m", typeId = "Mass", direction = In),
            ConstraintParameter(name = "a", typeId = "Acceleration", direction = Out),
        ),
        parent = dynamics,
    )
    val vehicle = partDef(name = "Vehicle") {
        attribute(name = "mass", typeId = "Mass")
        attribute(name = "driveForce", typeId = "Force")
        attribute(name = "speed", typeId = "Speed")
        attribute(name = "acceleration", typeId = "Acceleration")
    }

    // Vehicle attributes to the pins of the outer constraint
    bind(name = "mass_to_m", source = "Vehicle::mass", target = dynamics.pin("m"))
    bind(name = "drive_to_F", source = "Vehicle::driveForce", target = dynamics.pin("F_drive"))
    bind(name = "speed_to_v", source = "Vehicle::speed", target = dynamics.pin("v"))
    bind(name = "a_to_accel", source = dynamics.pin("a"), target = "Vehicle::acceleration")
    // Outer pins to inner pins
    bind(name = "F_to_drag", source = dynamics.pin("F_drive"), target = drag.pin("F_drive"))
    bind(name = "v_to_drag", source = dynamics.pin("v"), target = drag.pin("v"))
    bind(name = "m_to_newton", source = dynamics.pin("m"), target = newton.pin("m"))
    // Between the inner constraints
    bind(name = "net_to_newton", source = drag.pin("F_net"), target = newton.pin("F"))
    // Inner pin back to the outer pin
    bind(name = "newton_to_a", source = newton.pin("a"), target = dynamics.pin("a"))

    parDiagram(name = "Vehicle dynamics") {
        include(definition = dynamics)
        include(definition = drag)
        include(definition = newton)
        include(definition = vehicle)
    }
}
