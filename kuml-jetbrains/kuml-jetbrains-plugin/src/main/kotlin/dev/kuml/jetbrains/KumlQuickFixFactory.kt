package dev.kuml.jetbrains

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.kuml.langsupport.diagnostics.KumlDiagnostic

/**
 * Maps [KumlDiagnostic] messages to [IntentionAction] quick fixes.
 *
 * Pattern matching on message strings is intentionally simple — the Kotlin
 * script compiler messages are stable enough for this purpose.
 */
object KumlQuickFixFactory {
    fun quickFixFor(diag: KumlDiagnostic): IntentionAction? =
        when {
            diag.message.contains("No value passed for parameter") ->
                AddMissingParameterFix(diag.message)
            diag.message.contains("No parameter with name") ->
                RemoveUnknownParameterFix(diag.message)
            diag.message.contains("Unresolved reference") ->
                RenameToKnownFunctionFix(diag.message)
            diag.severity == KumlDiagnostic.Severity.WARNING ->
                SuppressWarningFix(diag.message)
            else -> null
        }
}

/** Quick fix: inserts a skeleton value for a missing required parameter. */
private class AddMissingParameterFix(
    private val diagnosticMessage: String,
) : IntentionAction {
    override fun getText() = "Add missing required parameter"

    override fun getFamilyName() = "kUML Quick Fixes"

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) = file?.name?.endsWith(".kuml.kts") == true

    override fun startInWriteAction() = true

    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) {
        // Extract parameter name from message e.g. "No value passed for parameter 'name'"
        val paramName = Regex("parameter '(\\w+)'").find(diagnosticMessage)?.groupValues?.get(1) ?: "param"
        editor?.document?.let { doc ->
            val offset = editor.caretModel.offset
            // Find the opening paren before the caret and insert paramName = TODO after it
            val insertText = "$paramName = TODO(\"fill in $paramName\")"
            doc.insertString(offset, insertText)
            editor.caretModel.moveToOffset(offset + insertText.length)
        }
    }
}

/** Quick fix: removes an unknown named parameter from the call. */
private class RemoveUnknownParameterFix(
    private val diagnosticMessage: String,
) : IntentionAction {
    override fun getText() = "Remove unknown parameter"

    override fun getFamilyName() = "kUML Quick Fixes"

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) = file?.name?.endsWith(".kuml.kts") == true

    override fun startInWriteAction() = true

    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) {
        // Best-effort: select the unknown parameter name at the caret position and delete it
        editor?.selectionModel?.let { sel ->
            if (!sel.hasSelection()) {
                val doc = editor.document.charsSequence
                val offset = editor.caretModel.offset
                val start = (offset downTo 0).firstOrNull { doc[it] == '\n' || doc[it] == '(' }?.let { it + 1 } ?: 0
                val end = (offset until doc.length).firstOrNull { doc[it] == ',' || doc[it] == ')' } ?: offset
                sel.setSelection(start, end)
            }
            editor.document.deleteString(sel.selectionStart, sel.selectionEnd)
        }
    }
}

/** Quick fix: offers to rename an unresolved reference to a known DSL alternative. */
private class RenameToKnownFunctionFix(
    private val diagnosticMessage: String,
) : IntentionAction {
    // Map common typos to the correct DSL function names
    private val knownAliases =
        mapOf(
            "classOf" to listOf("class_", "clazz", "umlClass"),
            "interfaceOf" to listOf("interface_", "iface"),
            "enumOf" to listOf("enum_"),
            "association" to listOf("assoc", "relate"),
            "generalization" to listOf("extends", "inherits"),
        )

    private fun suggestion(): String? {
        val ref = Regex("Unresolved reference: (\\w+)").find(diagnosticMessage)?.groupValues?.get(1) ?: return null
        return knownAliases.entries.firstOrNull { (_, aliases) -> ref in aliases }?.key
    }

    override fun getText() = suggestion()?.let { "Rename to '$it'" } ?: "Rename to known DSL function"

    override fun getFamilyName() = "kUML Quick Fixes"

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) = file?.name?.endsWith(".kuml.kts") == true && suggestion() != null

    override fun startInWriteAction() = true

    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) {
        val replacement = suggestion() ?: return
        val ref = Regex("Unresolved reference: (\\w+)").find(diagnosticMessage)?.groupValues?.get(1) ?: return
        val doc = editor?.document ?: return
        val text = doc.charsSequence
        val offset = editor.caretModel.offset
        // Find the word at the caret
        val start = (offset downTo 0).firstOrNull { !text[it].isLetterOrDigit() && text[it] != '_' }?.let { it + 1 } ?: 0
        val end = (offset until text.length).firstOrNull { !text[it].isLetterOrDigit() && text[it] != '_' } ?: offset
        if (text.substring(start, end) == ref) {
            doc.replaceString(start, end, replacement)
        }
    }
}

/** Quick fix: adds a @Suppress annotation for a warning. */
private class SuppressWarningFix(
    private val diagnosticMessage: String,
) : IntentionAction {
    override fun getText() = "Suppress this warning"

    override fun getFamilyName() = "kUML Quick Fixes"

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) = file?.name?.endsWith(".kuml.kts") == true

    override fun startInWriteAction() = true

    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) {
        val doc = editor?.document ?: return
        val offset = editor.caretModel.offset
        val lineStart = doc.charsSequence.lastIndexOf('\n', offset - 1) + 1
        doc.insertString(lineStart, "@Suppress(\"kuml.warning\") // $diagnosticMessage\n")
    }
}
