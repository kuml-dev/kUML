package dev.kuml.layout.bridge.port

import dev.kuml.sysml2.PinSide

/**
 * The port face of the pin square that sits on this side of an action box. Total over all [PinSide] values:
 * [PinSide.Auto] keeps the historic placement (input pins on the left, output pins on the right), so the pin
 * direction decides; an explicit side maps to the face of the same name.
 */
public fun PinSide.toPortFace(isOutput: Boolean): PortFace =
    when (this) {
        PinSide.Auto -> if (isOutput) PortFace.Right else PortFace.Left
        PinSide.Left -> PortFace.Left
        PinSide.Right -> PortFace.Right
        PinSide.Top -> PortFace.Top
        PinSide.Bottom -> PortFace.Bottom
    }
