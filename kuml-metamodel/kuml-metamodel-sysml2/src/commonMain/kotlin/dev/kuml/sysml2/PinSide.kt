package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * Side of an action box on which an [ActionPin] is drawn (wave C2).
 *
 * [Auto] keeps the historic placement (input pins on the left, output pins on the right) and
 * leaves the layout size of the action untouched. An explicit side additionally enlarges the box
 * so the pins on that side do not crowd each other.
 */
@Serializable
public enum class PinSide {
    /** Input pins on the left edge, output pins on the right edge (historic behaviour). */
    Auto,
    Left,
    Right,
    Top,
    Bottom,
}
