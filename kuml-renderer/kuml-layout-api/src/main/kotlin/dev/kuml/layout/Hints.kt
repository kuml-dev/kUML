package dev.kuml.layout

import kotlinx.serialization.Serializable

/**
 * Globale Steuerparameter für einen Layout-Lauf.
 *
 * Wird zusammen mit einem [LayoutGraph] an [KumlLayoutEngine.layout] übergeben.
 * Engines sollen alle Felder so gut wie möglich berücksichtigen; nicht unterstützte
 * Hints werden schweigend ignoriert und als [LayoutWarning] gemeldet.
 *
 * @property deterministicSeed Seed für reproduzierbare Ergebnisse; null deaktiviert Determinismus.
 * @property timeBudgetMillis Weiches Zeitlimit in Millisekunden; bei Überschreitung: Teilergebnis + Warning.
 * @property defaultEdgeStyle Standard-Routing-Stil für alle Kanten ohne expliziten Hint.
 * @property direction Haupt-Flussrichtung des Graphen.
 * @property spacing Abstands-Parameter für Knoten, Kanten und Gruppen.
 * @property engineOptions Engine-spezifische Escape-Hatch-Parameter als String-Map.
 */
@Serializable
public data class LayoutHints(
    val deterministicSeed: Long? = 0L,
    val timeBudgetMillis: Long = 1_500,
    val defaultEdgeStyle: EdgeRouteStyle = EdgeRouteStyle.OrthogonalRounded,
    val direction: LayoutDirection = LayoutDirection.TopToBottom,
    val spacing: Spacing = Spacing.DEFAULT,
    val engineOptions: Map<String, String> = emptyMap(),
) {
    public companion object {
        /** Standard-Hints mit sinnvollen Defaults für die meisten Diagrammtypen. */
        public val DEFAULT: LayoutHints = LayoutHints()
    }
}

/**
 * Layout-Hints für einen einzelnen Knoten.
 *
 * Alle Felder sind optional; Engines, die Grid-Layouts nicht unterstützen,
 * ignorieren Grid-Felder und emittieren eine entsprechende [LayoutWarning].
 *
 * @property gridCol Gewünschte Spalte im Grid-Layout (0-basiert), oder null für automatisch.
 * @property gridRow Gewünschte Zeile im Grid-Layout (0-basiert), oder null für automatisch.
 * @property gridColSpan Anzahl der belegten Spalten (mindestens 1).
 * @property gridRowSpan Anzahl der belegten Zeilen (mindestens 1).
 * @property pinned Wenn true: Knoten soll nicht verschoben werden.
 * @property relative Liste relativer Positionierungsconstraints gegenüber anderen Knoten.
 */
@Serializable
public data class NodeHints(
    val gridCol: Int? = null,
    val gridRow: Int? = null,
    val gridColSpan: Int = 1,
    val gridRowSpan: Int = 1,
    val pinned: Boolean = false,
    val relative: List<RelativeConstraint> = emptyList(),
) {
    public companion object {
        /** NodeHints ohne jegliche Einschränkungen — vollständig automatisches Layout. */
        public val NONE: NodeHints = NodeHints()
    }
}

/**
 * Layout-Hints für eine einzelne Kante.
 *
 * Aktuell ohne Pflichtfelder; erweiterbar für zukünftige edge-spezifische Constraints.
 */
@Serializable
public data class EdgeHints(
    val routeStyle: EdgeRouteStyle? = null,
) {
    public companion object {
        /** EdgeHints ohne Einschränkungen — Engine wählt den Routing-Stil. */
        public val NONE: EdgeHints = EdgeHints()
    }
}

/**
 * Versiegelte Hierarchie relativer Positionierungsconstraints zwischen Knoten.
 *
 * Wird in [NodeHints.relative] verwendet. Engines mit [LayoutCapabilities.respectsRelativeConstraints]
 * berücksichtigen diese; andere ignorieren sie und emittieren eine [LayoutWarning].
 */
@Serializable
public sealed interface RelativeConstraint {
    /** Dieser Knoten soll oberhalb von [other] platziert werden. */
    @Serializable
    public data class Above(val other: NodeId) : RelativeConstraint

    /** Dieser Knoten soll unterhalb von [other] platziert werden. */
    @Serializable
    public data class Below(val other: NodeId) : RelativeConstraint

    /** Dieser Knoten soll links von [other] platziert werden. */
    @Serializable
    public data class LeftOf(val other: NodeId) : RelativeConstraint

    /** Dieser Knoten soll rechts von [other] platziert werden. */
    @Serializable
    public data class RightOf(val other: NodeId) : RelativeConstraint

    /** Dieser Knoten soll in derselben Zeile wie [other] platziert werden. */
    @Serializable
    public data class SameRowAs(val other: NodeId) : RelativeConstraint

    /** Dieser Knoten soll in derselben Spalte wie [other] platziert werden. */
    @Serializable
    public data class SameColAs(val other: NodeId) : RelativeConstraint
}
