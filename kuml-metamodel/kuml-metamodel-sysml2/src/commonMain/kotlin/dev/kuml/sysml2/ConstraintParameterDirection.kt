package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * Direction of a constraint parameter — in (consumed input), out (produced
 * output), or inout (both). Drives whether a binding-connector points
 * "into" or "out of" the parameter pin.
 *
 * V2.0.12 entry: closes the SysML 2 diagram-type series with the Parametric
 * Diagram (PAR). The direction discriminates the role of each parameter on a
 * [ConstraintDefinition]'s edge:
 *  - [In] — value flows INTO the constraint (consumed input). Renders with
 *    the `«in»` stereotype prefix on the parameter list.
 *  - [Out] — value flows OUT of the constraint (produced output). Renders
 *    with the `«out»` stereotype prefix.
 *  - [Inout] — value flows in both directions (the default). Renders with
 *    the `«inout»` stereotype prefix.
 *
 * The future parametric solver wave will use this discriminator to drive the
 * direction of value propagation across [BindingConnectorUsage]s — V2.0.12
 * only captures the structural projection.
 */
@Serializable
enum class ConstraintParameterDirection {
    In,
    Out,
    Inout,
}
