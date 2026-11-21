package dev.kuml.lsp

import com.google.gson.JsonObject
import dev.kuml.workspace.OkfAliasFix
import dev.kuml.workspace.OkfType
import dev.kuml.workspace.OkfValidator
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * `OKF-I-001` for markdown buffers: an informational diagnostic on an alias `type:` value and a
 * quick fix that replaces it with the canonical id (Nacharbeit 125).
 *
 * Pure and stateless so it can be tested without a server. The edit is derived from the same
 * locator as [OkfAliasFix.canonicalizeType], and it is re-validated against the CURRENT document
 * text before it is offered, because the client may send diagnostics that are stale by then.
 */
internal object OkfAliasQuickFix {
    const val CODE = "OKF-I-001"
    const val TITLE = "Replace alias with canonical type"
    const val SOURCE = "kuml-okf"

    /** The diagnostic for [text], or `null` when it has no alias `type:` value to rewrite. */
    fun diagnostic(
        text: String,
        uri: String,
    ): Diagnostic? {
        val span = OkfAliasFix.typeValueSpan(text) ?: return null
        if (!OkfType.isAlias(span.value)) return null
        val canonical = OkfType.canonicalIdFor(span.value) ?: return null
        if (OkfAliasFix.canonicalizeType(text) == null) return null
        val finding = OkfValidator.aliasFinding(file = uri, rawType = span.value, typeLine = span.line + 1) ?: return null
        val range = Range(Position(span.line, span.startColumn), Position(span.line, span.endColumn))
        return Diagnostic(range, finding.message).apply {
            severity = DiagnosticSeverity.Information
            source = SOURCE
            setCode(Either.forLeft<String, Int>(CODE))
            data =
                JsonObject().apply {
                    addProperty("alias", span.value)
                    addProperty("canonicalId", canonical)
                }
        }
    }

    /** Quick fixes for the `OKF-I-001` diagnostics in [context]; everything stale or foreign is skipped. */
    fun actions(
        uri: String,
        text: String,
        context: List<Diagnostic>,
    ): List<CodeAction> {
        val span = OkfAliasFix.typeValueSpan(text) ?: return emptyList()
        if (!OkfType.isAlias(span.value)) return emptyList()
        val canonical = OkfType.canonicalIdFor(span.value) ?: return emptyList()
        if (OkfAliasFix.canonicalizeType(text) == null) return emptyList()

        return context.mapNotNull { d ->
            if (d.code?.left != CODE) return@mapNotNull null
            val r = d.range
            if (r.start.line != span.line || r.end.line != span.line) return@mapNotNull null
            if (r.start.character != span.startColumn || r.end.character != span.endColumn) return@mapNotNull null
            if (!dataMatches(data = d.data, alias = span.value, canonical = canonical)) return@mapNotNull null
            // Stale-document guard with LSP line semantics (\n, \r\n and lone \r all break lines).
            if (substring(text = text, range = r) != span.value) return@mapNotNull null

            val edit = WorkspaceEdit(mapOf(uri to listOf(TextEdit(r, canonical))))
            CodeAction(TITLE).apply {
                kind = CodeActionKind.QuickFix
                diagnostics = listOf(d)
                isPreferred = true
                this.edit = edit
            }
        }
    }

    /** Missing data is tolerated; present data must agree with the recomputed values. */
    private fun dataMatches(
        data: Any?,
        alias: String,
        canonical: String,
    ): Boolean {
        fun field(name: String): String? =
            when (data) {
                is JsonObject -> data.get(name)?.takeIf { it.isJsonPrimitive }?.asString
                is Map<*, *> -> data[name]?.toString()
                else -> null
            }
        val a = field("alias")
        val c = field("canonicalId")
        return (a == null || a == alias) && (c == null || c == canonical)
    }

    /** The text covered by a single-line [range] using LSP line breaks, or `null` when out of bounds. */
    private fun substring(
        text: String,
        range: Range,
    ): String? {
        val start = offsetOf(text = text, pos = range.start) ?: return null
        val end = offsetOf(text = text, pos = range.end) ?: return null
        return if (start <= end) text.substring(start, end) else null
    }

    private fun offsetOf(
        text: String,
        pos: Position,
    ): Int? {
        var line = 0
        var i = 0
        while (line < pos.line) {
            if (i >= text.length) return null
            val c = text[i]
            i++
            if (c == '\r') {
                if (i < text.length && text[i] == '\n') i++
                line++
            } else if (c == '\n') {
                line++
            }
        }
        var end = i
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        val off = i + pos.character
        return if (pos.character >= 0 && off <= end) off else null
    }
}
