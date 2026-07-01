package dev.kuml.core.dsl.layout

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.KumlMetaValue

/**
 * Sammler für Layout-Hints eines einzelnen Modell-Elements.
 *
 * Wird vom umgebenden Element-Builder (z.B. `ClassBuilder`) gehalten und beim
 * `build()`-Aufruf per [toMetadata] in das `metadata`-Feld der Datenklasse
 * materialisiert. Alle Schlüssel befinden sich im reservierten Namespace
 * [LayoutMetadataKeys.NAMESPACE] (`kuml.layout.*`).
 *
 * Typische Verwendung über die [LayoutHintsScope.layout]-Extension:
 * ```kotlin
 * classOf("Order") {
 *     layout {
 *         col = 2
 *         row = 1
 *         colSpan = 2
 *         pinned = true
 *         rightOf("Customer")
 *     }
 * }
 * ```
 *
 * **Hinweis zu HasId-Overloads:** Die Metamodell-Datenklassen (UmlClass, C4Container, …)
 * implementieren [HasId] aus ADR-0001-Gründen nicht direkt. Verwende daher den
 * String-Overload (`rightOf("id")`) oder übergib eine SAM-Instanz
 * (`rightOf(HasId { element.id })`). Zusätzlich stehen Convenience-Extensions
 * für die bekannten Modell-Typen bereit (z.B. `rightOf(umlClass)`).
 */
@KumlDsl
public class LayoutHintsBuilder internal constructor() {
    /** Gewünschte Spalte im Grid-Layout (0-basiert), oder `null` für automatisch. */
    public var col: Int? = null

    /** Gewünschte Zeile im Grid-Layout (0-basiert), oder `null` für automatisch. */
    public var row: Int? = null

    /** Anzahl der belegten Spalten (mindestens 1, Standard: 1). */
    public var colSpan: Int = 1

    /** Anzahl der belegten Zeilen (mindestens 1, Standard: 1). */
    public var rowSpan: Int = 1

    /** Wenn `true`: Engine soll diesen Knoten nicht verschieben. */
    public var pinned: Boolean = false

    private val relative = mutableListOf<RelativeConstraintDsl>()

    // ── Relative Constraints — HasId-Overloads ────────────────────────────────

    /** Dieser Knoten soll oberhalb von [other] platziert werden. */
    public fun above(other: HasId): Unit = addRelative(RelativeConstraintDsl.Above(other.id))

    /** Dieser Knoten soll unterhalb von [other] platziert werden. */
    public fun below(other: HasId): Unit = addRelative(RelativeConstraintDsl.Below(other.id))

    /** Dieser Knoten soll links von [other] platziert werden. */
    public fun leftOf(other: HasId): Unit = addRelative(RelativeConstraintDsl.LeftOf(other.id))

    /** Dieser Knoten soll rechts von [other] platziert werden. */
    public fun rightOf(other: HasId): Unit = addRelative(RelativeConstraintDsl.RightOf(other.id))

    /** Dieser Knoten soll in derselben Zeile wie [other] platziert werden. */
    public fun sameRowAs(other: HasId): Unit = addRelative(RelativeConstraintDsl.SameRowAs(other.id))

    /** Dieser Knoten soll in derselben Spalte wie [other] platziert werden. */
    public fun sameColAs(other: HasId): Unit = addRelative(RelativeConstraintDsl.SameColAs(other.id))

    // ── Relative Constraints — String-Overloads ───────────────────────────────

    /** Dieser Knoten soll oberhalb von [otherId] platziert werden. */
    public fun above(otherId: String): Unit = addRelative(RelativeConstraintDsl.Above(otherId))

    /** Dieser Knoten soll unterhalb von [otherId] platziert werden. */
    public fun below(otherId: String): Unit = addRelative(RelativeConstraintDsl.Below(otherId))

    /** Dieser Knoten soll links von [otherId] platziert werden. */
    public fun leftOf(otherId: String): Unit = addRelative(RelativeConstraintDsl.LeftOf(otherId))

    /** Dieser Knoten soll rechts von [otherId] platziert werden. */
    public fun rightOf(otherId: String): Unit = addRelative(RelativeConstraintDsl.RightOf(otherId))

    /** Dieser Knoten soll in derselben Zeile wie [otherId] platziert werden. */
    public fun sameRowAs(otherId: String): Unit = addRelative(RelativeConstraintDsl.SameRowAs(otherId))

    /** Dieser Knoten soll in derselben Spalte wie [otherId] platziert werden. */
    public fun sameColAs(otherId: String): Unit = addRelative(RelativeConstraintDsl.SameColAs(otherId))

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Gibt `true` zurück, wenn keine Hints von ihrem Default-Wert abweichen.
     *
     * Builder ohne `layout {}`-Block haben immer leere Metadaten — dieses
     * Prädikat erlaubt die frühzeitige Erkennung ohne [toMetadata] aufzurufen.
     */
    public fun isEmpty(): Boolean = col == null && row == null && colSpan == 1 && rowSpan == 1 && !pinned && relative.isEmpty()

    // ── Materialisierung ──────────────────────────────────────────────────────

    /**
     * Materialisiert die gesetzten Hints in eine `metadata`-kompatible Map.
     *
     * Nur Felder, die von ihrem Default-Wert abweichen, erzeugen Einträge.
     * Der Namespace aller Schlüssel ist [LayoutMetadataKeys.NAMESPACE] (`kuml.layout.*`).
     *
     * Diese Funktion ist idempotent und seiteneffektfrei — mehrfache Aufrufe
     * liefern dasselbe Ergebnis.
     *
     * @return Map mit Layout-Metadaten; leer wenn [isEmpty] `true` ist.
     */
    public fun toMetadata(): Map<String, KumlMetaValue> =
        buildMap {
            col?.let { put(LayoutMetadataKeys.GRID_COL, KumlMetaValue.Integer(it.toLong())) }
            row?.let { put(LayoutMetadataKeys.GRID_ROW, KumlMetaValue.Integer(it.toLong())) }
            if (colSpan != 1) put(LayoutMetadataKeys.GRID_COL_SPAN, KumlMetaValue.Integer(colSpan.toLong()))
            if (rowSpan != 1) put(LayoutMetadataKeys.GRID_ROW_SPAN, KumlMetaValue.Integer(rowSpan.toLong()))
            if (pinned) put(LayoutMetadataKeys.PINNED, KumlMetaValue.Flag(true))
            if (relative.isNotEmpty()) {
                put(LayoutMetadataKeys.RELATIVE, KumlMetaValue.Items(relative.map { it.toMeta() }))
            }
        }

    // ── Intern ────────────────────────────────────────────────────────────────

    private fun addRelative(c: RelativeConstraintDsl) {
        relative += c
    }
}
