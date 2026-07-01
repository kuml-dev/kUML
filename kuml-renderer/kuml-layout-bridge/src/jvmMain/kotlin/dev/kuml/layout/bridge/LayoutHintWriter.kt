package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlNamedElement

/**
 * Konvertiert Grid-Platzierungsergebnisse zurück in DSL-Metadaten, damit
 * Drag-and-Drop-Editoren Positionierungsänderungen in die `.kuml.kts`-Quelle
 * round-trippen können.
 *
 * Verwendung: [writeGridHints] mit dem aktuellen Diagramm und einer Map
 * `Element-ID → Grid-Zelle (col, row)` aufrufen. Liefert eine Kopie des
 * Diagramms mit `kuml.layout.gridCol` und `kuml.layout.gridRow` auf jedem
 * betroffenen Element.
 *
 * Die geschriebenen Schlüssel sind identisch mit denen, die [HintsReader]
 * auswertet — so ist der Round-Trip durch `HintsReader.read()` nachweisbar.
 */
public object LayoutHintWriter {
    /**
     * Gewünschte Grid-Position für ein Element.
     *
     * @property col Spalte (0-basiert).
     * @property row Zeile (0-basiert).
     */
    public data class GridCell(
        val col: Int,
        val row: Int,
    )

    /**
     * Schreibt Grid-Positionierungs-Hints in die Metadaten der betroffenen
     * Diagramm-Elemente und liefert eine aktualisierte Kopie des Diagramms.
     *
     * Elemente, deren ID nicht in [placements] auftaucht, bleiben unverändert.
     * Die Schreiboperation ist idempotent: mehrfaches Aufrufen mit denselben
     * Placements ergibt dasselbe Resultat.
     *
     * @param diagram Das Quell-Diagramm (unveränderter Input).
     * @param placements Map von Element-ID auf gewünschte [GridCell].
     * @return Kopie des Diagramms mit aktualisierten Element-Metadaten.
     */
    public fun writeGridHints(
        diagram: KumlDiagram,
        placements: Map<String, GridCell>,
    ): KumlDiagram {
        if (placements.isEmpty()) return diagram
        val updatedElements =
            diagram.elements.map { element ->
                val cell = placements[element.id] ?: return@map element
                element.withGridHints(cell)
            }
        return diagram.copy(elements = updatedElements)
    }

    /**
     * Erzeugt eine Kopie von [element] mit gesetzten `gridCol`/`gridRow`-Metadaten.
     *
     * Funktioniert für alle [UmlNamedElement]-Untertypen, die als `data class`
     * mit einem `metadata`-Parameter implementiert sind. Für alle anderen
     * Element-Typen (Relationships ohne eigenes Layout, unbekannte Subtypen)
     * wird das Element unverändert zurückgegeben.
     *
     * Die Kopie erfolgt über Reflection auf der `copy()`-Methode der `data class`,
     * so dass kein erschöpfendes `when` über alle konkreten Subtypen nötig ist.
     */
    private fun KumlElement.withGridHints(cell: GridCell): KumlElement {
        if (this !is UmlNamedElement) return this
        val newMeta =
            metadata +
                mapOf(
                    BridgeLayoutKeys.GRID_COL to KumlMetaValue.Integer(cell.col.toLong()),
                    BridgeLayoutKeys.GRID_ROW to KumlMetaValue.Integer(cell.row.toLong()),
                )
        return copyWithMetadata(newMeta) ?: this
    }

    /**
     * Ruft `copy(metadata = newMeta)` auf einem `data class`-Objekt via Reflection auf.
     *
     * Gibt `null` zurück wenn kein `copy`-Overload mit einem `metadata`-Parameter
     * gefunden wird — der Aufrufer fällt dann auf das Originalobjekt zurück.
     */
    private fun UmlNamedElement.copyWithMetadata(newMeta: Map<String, KumlMetaValue>): KumlElement? {
        return try {
            val copyFn =
                this::class.members.firstOrNull { m ->
                    m.name == "copy" &&
                        m.parameters.any { p -> p.name == "metadata" }
                } ?: return null
            val instanceParam = copyFn.parameters.first()
            val metaParam =
                copyFn.parameters.firstOrNull { it.name == "metadata" } ?: return null
            @Suppress("UNCHECKED_CAST")
            copyFn.callBy(mapOf(instanceParam to this, metaParam to newMeta)) as? KumlElement
        } catch (_: Exception) {
            null
        }
    }
}
