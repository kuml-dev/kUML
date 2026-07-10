package dev.kuml.web.layout

import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.layout.bridge.LayoutHintWriter
import dev.kuml.uml.UmlNamedElement

/**
 * Applies a single drag-and-drop grid placement to a UML class-diagram script
 * and returns the updated, re-parseable `.kuml.kts` source.
 *
 * Used by `POST /api/layout/hint`. Pure model transform — no layout engine or
 * renderer runs on this path, only [dev.kuml.layout.bridge.LayoutHintWriter]
 * (writes `kuml.layout.*` metadata) and [dev.kuml.uml.dsl.print.UmlModelDslPrinter]
 * (serializes the updated diagram back to DSL text).
 *
 * @param parse Parses a script into a [KumlDiagram]. Wraps
 *   [dev.kuml.core.script.KumlScriptGuard.validate] +
 *   [dev.kuml.core.script.KumlScriptHost.eval] +
 *   [dev.kuml.core.script.DiagramExtractor.extractAny] — injected so tests can
 *   use the real script host without this class depending on Ktor internals.
 * @param print Serializes the updated diagram back to `.kuml.kts` source.
 *   Normally [dev.kuml.uml.dsl.print.UmlModelDslPrinter.print].
 */
internal class LayoutHintService(
    private val parse: (String) -> KumlDiagram,
    private val print: (KumlDiagram) -> String,
) {
    /**
     * Moves [elementId] to [cell] and returns the updated script source.
     *
     * Fails (without mutating anything visible to the caller) when:
     * - [script] does not parse, or does not evaluate to a UML class diagram.
     * - [elementId] does not exist in the diagram.
     * - [elementId] resolves to a relationship/edge, not a placeable node.
     * - [cell] is already occupied by a different element.
     */
    fun applyDrop(
        script: String,
        elementId: String,
        cell: LayoutHintWriter.GridCell,
    ): Result<String> {
        val diagram = runCatching { parse(script) }.getOrElse { return Result.failure(it) }

        if (diagram.type != DiagramType.CLASS) {
            return Result.failure(
                IllegalArgumentException(
                    "Grid hints are only supported for UML class diagrams, got ${diagram.type}",
                ),
            )
        }

        val target =
            diagram.elements.firstOrNull { it.id == elementId }
                ?: return Result.failure(IllegalArgumentException("No element with id '$elementId'"))

        if (target !is UmlNamedElement) {
            return Result.failure(
                IllegalArgumentException("Element '$elementId' is a relationship/edge and cannot be grid-placed"),
            )
        }

        val collision =
            diagram.elements
                .filterIsInstance<UmlNamedElement>()
                .firstOrNull { other -> other.id != elementId && other.gridCellOrNull() == cell }
        if (collision != null) {
            return Result.failure(
                IllegalArgumentException(
                    "Target cell (col=${cell.col}, row=${cell.row}) is already occupied by '${collision.id}'",
                ),
            )
        }

        val updated = LayoutHintWriter.writeGridHints(diagram, mapOf(elementId to cell))
        return runCatching { print(updated) }
    }

    private fun UmlNamedElement.gridCellOrNull(): LayoutHintWriter.GridCell? {
        val col = (metadata[LayoutMetadataKeys.GRID_COL] as? KumlMetaValue.Integer)?.value?.toInt() ?: return null
        val row = (metadata[LayoutMetadataKeys.GRID_ROW] as? KumlMetaValue.Integer)?.value?.toInt() ?: return null
        return LayoutHintWriter.GridCell(col, row)
    }
}
