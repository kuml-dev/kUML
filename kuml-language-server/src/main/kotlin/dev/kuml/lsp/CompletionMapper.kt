package dev.kuml.lsp

import dev.kuml.langsupport.completion.KumlCompletionItems
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind

/**
 * Maps [KumlCompletionItems.Item] (the editor-agnostic catalogue in
 * `kuml-lang-support`) to lsp4j [CompletionItem]s, converting the plain
 * `insertText` into LSP snippet syntax with sequential tab stops.
 *
 * Documentation is intentionally left unset by [toLsp] and filled lazily by
 * [resolve] — this keeps the initial `textDocument/completion` response small
 * (38 items) and defers the (cheap, but non-zero) markdown formatting to
 * `completionItem/resolve`, matching the `resolveProvider = true` capability
 * advertised in [KumlLanguageServer.initialize].
 */
object CompletionMapper {
    /**
     * Static (context-free) mapping of a catalogue item to an LSP completion item.
     * Documentation is intentionally omitted here — filled lazily in [resolve].
     */
    fun toLsp(item: KumlCompletionItems.Item): CompletionItem =
        CompletionItem(item.name).apply {
            kind = kindFor(item.group)
            detail = item.description
            labelDetails =
                CompletionItemLabelDetails().apply {
                    detail = item.tail
                }
            insertText = toSnippet(item.insertText)
            insertTextFormat = InsertTextFormat.Snippet
        }

    /** Fills documentation on an item returned by [toLsp]; called from completionItem/resolve. */
    fun resolve(unresolved: CompletionItem): CompletionItem {
        val item = KumlCompletionItems.byName(unresolved.label) ?: return unresolved
        unresolved.setDocumentation(
            MarkupContent(
                MarkupKind.MARKDOWN,
                "```kotlin\n${item.name}${item.tail}\n```\n\n${item.description}",
            ),
        )
        return unresolved
    }

    internal fun kindFor(group: KumlCompletionItems.Group): CompletionItemKind =
        when (group) {
            KumlCompletionItems.Group.ENTRY -> CompletionItemKind.Function
            KumlCompletionItems.Group.UML -> CompletionItemKind.Class
            KumlCompletionItems.Group.SYSML2 -> CompletionItemKind.Class
            KumlCompletionItems.Group.C4 -> CompletionItemKind.Class
            KumlCompletionItems.Group.SHARED -> CompletionItemKind.Value
        }

    /**
     * Converts a plain insertText into LSP snippet syntax:
     *  - each empty string arg  `""`          -> `"$N"`
     *  - the empty block body   `{\n    \n}`  -> `{\n    $N\n\}`
     *  - a final `$0` tab stop appended
     *  - literal `$`, `\`, `}` escaped as `\$`, `\\`, `\}` (LSP snippet text grammar)
     *
     * `N` increments across empty slots in textual order (args precede the block
     * body). `internal` so tests can exercise it directly.
     */
    internal fun toSnippet(insertText: String): String {
        val sb = StringBuilder()
        var i = 0
        var tab = 1
        val block = "{\n    \n}"
        while (i < insertText.length) {
            val c = insertText[i]
            when {
                c == '"' && i + 1 < insertText.length && insertText[i + 1] == '"' -> {
                    sb.append("\"$${tab++}\"")
                    i += 2
                }
                insertText.startsWith(block, i) -> {
                    sb.append("{\n    $").append(tab++).append("\n\\}")
                    i += block.length
                }
                c == '$' || c == '\\' || c == '}' -> {
                    sb.append('\\').append(c)
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.append("\$0").toString()
    }
}
