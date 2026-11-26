package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * Where the label of a port square stands relative to its connector (PAL Welle B): `Before` = above the connector line
 * (east/west ports) or left of the square (north/south ports), `After` = below it or right of it. `Auto` leaves the
 * choice to the label planner. A hint that cannot be honoured falls back to `Auto` and is reported as `KUML-IBD-W001`. A label that still touches a part, pin, title, other label or
 * line after the bounded repair search (up to 12 px from the pin) is reported as `KUML-IBD-W002`; the drawing does not change.
 * There is no inside position: the label always stays outside the owner box.
 */
@Serializable
enum class PortLabelAt {
    Auto,
    Before,
    After,
}
