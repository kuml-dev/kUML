package dev.kuml.core.dsl.layout

/**
 * Reservierte Metadaten-Schlüssel im Namespace `kuml.layout.*`.
 *
 * Alle Schlüssel, die das kUML-Layout-Subsystem in das `metadata`-Feld von
 * Modell-Elementen schreibt, werden hier als Konstanten definiert.
 * User-Code darf keine Schlüssel mit dem Präfix [NAMESPACE] belegen.
 *
 * Verwendung:
 * ```kotlin
 * val meta = mapOf(LayoutMetadataKeys.GRID_COL to KumlMetaValue.Integer(2))
 * ```
 */
public object LayoutMetadataKeys {
    /** Gemeinsames Präfix aller Layout-Schlüssel. */
    public const val NAMESPACE: String = "kuml.layout"

    /** Gewünschte Spalte im Grid-Layout (0-basiert). */
    public const val GRID_COL: String = "kuml.layout.gridCol"

    /** Gewünschte Zeile im Grid-Layout (0-basiert). */
    public const val GRID_ROW: String = "kuml.layout.gridRow"

    /** Anzahl der belegten Spalten (Standard: 1). */
    public const val GRID_COL_SPAN: String = "kuml.layout.gridColSpan"

    /** Anzahl der belegten Zeilen (Standard: 1). */
    public const val GRID_ROW_SPAN: String = "kuml.layout.gridRowSpan"

    /** Wenn `true`: Engine soll den Knoten nicht verschieben. */
    public const val PINNED: String = "kuml.layout.pinned"

    /** Geordnete Liste relativer Positionierungsconstraints. */
    public const val RELATIVE: String = "kuml.layout.relative"

    /** Sub-Schlüssel in einem `Entries`-Wert: Art des Constraints (z.B. `"above"`). */
    public const val REL_KIND: String = "kind"

    /** Sub-Schlüssel in einem `Entries`-Wert: ID des Referenz-Elements. */
    public const val REL_OTHER: String = "other"
}
