package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * Where the label of a port square stands relative to its connector (PAL Welle B): `Before` = above the connector line
 * (east/west ports) or left of the square (north/south ports), `After` = below it or right of it. `Auto` leaves the
 * choice to the label planner. A hint that cannot be honoured falls back to `Auto` and is reported as `KUML-IBD-W001`.
 * There is no inside position: the label always stays outside the owner box.
 */
@Serializable
enum class PortLabelAt {
    Auto,
    Before,
    After,
}
