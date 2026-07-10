package dev.kuml.lsp

import dev.kuml.langsupport.diagnostics.KumlDiagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Converts a 1-based [KumlDiagnostic] location (the TSV/Kotlin-scripting
 * convention) into a 0-based, half-open LSP [Range], clamped to the document's
 * actual bounds and widened to a non-empty span when the CLI supplied no end
 * location (start == end), so the editor always renders a visible squiggle.
 */
object RangeMapping {
    fun toLspRange(
        d: KumlDiagnostic,
        docText: String,
    ): Range {
        val lines = docText.split("\n")
        val lastLine = lines.lastIndex

        fun lineLen(line: Int): Int = lines[line].trimEnd('\r').length

        val sLine = (d.startLine - 1).coerceIn(0, lastLine)
        val sChar = (d.startCol - 1).coerceIn(0, lineLen(sLine))

        var eLine = (d.endLine - 1).coerceIn(0, lastLine)
        var eChar = (d.endCol - 1).coerceIn(0, lineLen(eLine))

        // Non-empty guarantee: if the CLI emitted no end location (parser default
        // is end == start) or the clamped end otherwise collapsed onto/behind the
        // start, extend the end on the start line to the next token boundary —
        // mirrors KumlAnnotator.apply's word-extension so JetBrains and LSP
        // squiggles cover the same span.
        if (eLine < sLine || (eLine == sLine && eChar <= sChar)) {
            val line = lines[sLine].trimEnd('\r')
            val boundary =
                (sChar until line.length)
                    .firstOrNull { line[it].isWhitespace() || line[it] == '(' || line[it] == ')' }
                    ?: line.length
            eLine = sLine
            eChar = maxOf(boundary, sChar + 1).coerceAtMost(line.length)
        }

        return Range(Position(sLine, sChar), Position(eLine, eChar))
    }
}
