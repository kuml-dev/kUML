package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * Direction of an [ActionPin] — input (consumed) or output (produced) — V2.0.16.
 *
 * Drives object-flow connection semantics: an [ObjectFlowUsage] target endpoint
 * normally lands on an Input pin; the source endpoint normally leaves an
 * Output pin. The V2.0.16 MVP keeps pin direction purely as a rendering hint —
 * the SVG renderer places Input pins on the left edge of the action box and
 * Output pins on the right edge. Endpoint-anchoring of `ObjectFlowUsage`s on
 * specific pins is V2.x polish (today the edge still terminates at the
 * Action node centre via the existing `EdgeRendererDispatcher`-fallback path).
 */
@Serializable
public enum class PinDirection {
    /** Pin consumes a token / value (left edge of the action box by default). */
    Input,

    /** Pin produces a token / value (right edge of the action box by default). */
    Output,
}
