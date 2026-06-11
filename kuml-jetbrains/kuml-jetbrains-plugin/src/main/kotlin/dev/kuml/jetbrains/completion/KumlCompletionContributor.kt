package dev.kuml.jetbrains.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElementBuilder

/**
 * Provides kUML DSL completions in `*.kuml.kts` files.
 *
 * Registered in `plugin.xml` as a `completion.contributor` for language="kotlin".
 * The contributor guards internally: if the file name does not end in `.kuml.kts`,
 * no items are injected — so it never interferes with regular Kotlin files.
 *
 * All completion item data lives in [KumlCompletionItems] (pure Kotlin, testable).
 * This class is a thin IntelliJ EP wrapper with no testable logic of its own.
 *
 * V2.0.41
 */
class KumlCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ) {
        if (parameters.completionType != CompletionType.BASIC) return
        val file = parameters.originalFile
        if (!file.name.endsWith(".kuml.kts")) return

        for (item in KumlCompletionItems.ALL) {
            val lookupElement =
                LookupElementBuilder
                    .create(item.name)
                    .withTailText(item.tail, true)
                    .withTypeText(item.description)
                    .withInsertHandler { context, _ -> applyInsertText(context, item) }
                    .bold()

            result.addElement(lookupElement)
        }
    }

    private fun applyInsertText(
        context: InsertionContext,
        item: KumlCompletionItems.Item,
    ) {
        val document = context.document
        val editor = context.editor

        // Replace the typed prefix (from startOffset to tailOffset) with the full insert text.
        val startOffset = context.startOffset
        val tailOffset = context.tailOffset
        document.replaceString(startOffset, tailOffset, item.insertText)

        // Position the caret:
        // - If the insert text contains "{\n    " we place the caret after that indent.
        // - Otherwise we place the caret at end of inserted text.
        val insertedText = item.insertText
        val lambdaIndentPattern = "{\n    "
        val lambdaIndex = insertedText.indexOf(lambdaIndentPattern)
        val newCaretOffset =
            if (lambdaIndex >= 0) {
                startOffset + lambdaIndex + lambdaIndentPattern.length
            } else {
                startOffset + insertedText.length
            }
        editor.caretModel.moveToOffset(newCaretOffset)
    }
}
