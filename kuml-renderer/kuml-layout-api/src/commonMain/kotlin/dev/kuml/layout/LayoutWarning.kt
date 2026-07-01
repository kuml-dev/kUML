package dev.kuml.layout

import kotlinx.serialization.Serializable

/**
 * Nicht-fatale Warnung einer Layout-Engine, geliefert in [LayoutResult.warnings].
 *
 * Engines emittieren Warnungen bei nicht erfüllbaren Hints (z.B. `hint.ignored.gridCol`),
 * Zeitüberschreitungen (`time.budget.exceeded`) oder unvermeidbaren Kantenschnitten
 * (`edge.crossing.unavoidable`).
 *
 * @property code Maschinenlesbarer Warncode, z.B. `"hint.ignored.gridCol"`.
 *   Der Code ist ein frei gewählter String — kein Enum — für maximale Erweiterbarkeit.
 * @property message Menschenlesbare Beschreibung der Warnung.
 * @property affectedEdges Liste der betroffenen Kanten (leer wenn nicht anwendbar).
 * @property affectedNodes Liste der betroffenen Knoten (leer wenn nicht anwendbar).
 */
@Serializable
public data class LayoutWarning(
    val code: String,
    val message: String,
    val affectedEdges: List<EdgeId> = emptyList(),
    val affectedNodes: List<NodeId> = emptyList(),
)
