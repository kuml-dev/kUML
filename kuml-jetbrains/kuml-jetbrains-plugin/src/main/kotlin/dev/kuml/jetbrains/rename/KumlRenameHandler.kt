package dev.kuml.jetbrains.rename

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler
import dev.kuml.langsupport.rename.KumlRenameExtractor

/**
 * Provides Shift+F6 rename refactoring for DSL element names in `*.kuml.kts` files.
 *
 * Registered in `plugin.xml` as a `renameHandler` (no `language` attribute — required
 * so the handler is triggered for all files and can self-filter on `.kuml.kts`).
 *
 * The rename algorithm:
 * 1. Finds the word under the caret via [findNameAtCaret].
 * 2. Shows an input dialog for the new name.
 * 3. Delegates occurrence search to [KumlRenameExtractor.findRenameCandidates] (pure Kotlin).
 * 4. Applies replacements back-to-front inside a [WriteCommandAction] so that earlier
 *    offsets are not invalidated by insertions at later positions.
 *
 * This class contains only IntelliJ glue — all testable logic lives in [KumlRenameExtractor].
 *
 * V2.0.41
 */
class KumlRenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return false
        if (!file.name.endsWith(".kuml.kts")) return false
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        return findNameAtCaret(editor, file) != null
    }

    override fun isRenaming(dataContext: DataContext): Boolean = isAvailableOnDataContext(dataContext)

    override fun invoke(
        project: Project,
        editor: Editor,
        file: PsiFile,
        dataContext: DataContext,
    ) {
        val name = findNameAtCaret(editor, file) ?: return
        val text = file.text ?: return

        val newName =
            Messages.showInputDialog(
                project,
                "Rename '$name' to:",
                "Rename DSL Element",
                Messages.getQuestionIcon(),
                name,
                object : com.intellij.openapi.ui.InputValidator {
                    override fun checkInput(inputString: String) = inputString.isNotBlank()

                    override fun canClose(inputString: String) = inputString.isNotBlank()
                },
            ) ?: return

        if (newName == name) return

        val candidates = KumlRenameExtractor.findRenameCandidates(text, name)
        if (candidates.isEmpty()) return

        val document = editor.document
        WriteCommandAction.runWriteCommandAction(project, "Rename DSL Element", null, {
            // Replace back-to-front to keep earlier offsets valid
            for (candidate in candidates.sortedByDescending { it.offset }) {
                document.replaceString(candidate.offset, candidate.endOffset, newName)
            }
        })
    }

    override fun invoke(
        project: Project,
        elements: Array<out PsiElement>,
        dataContext: DataContext,
    ) {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return
        invoke(project, editor, file, dataContext)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the identifier word under the caret if it starts with a letter,
     * otherwise `null`.
     *
     * The word boundary is determined by [Character.isJavaIdentifierPart] so that
     * kUML DSL identifiers (Kotlin-valid) are correctly included.
     */
    private fun findNameAtCaret(
        editor: Editor,
        file: PsiFile,
    ): String? {
        val text = file.text ?: return null
        val offset = editor.caretModel.offset.coerceIn(0, text.length)
        if (offset >= text.length) return null

        // Expand left
        var start = offset
        while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
        // Expand right
        var end = offset
        while (end < text.length && Character.isJavaIdentifierPart(text[end])) end++

        if (start >= end) return null
        val word = text.substring(start, end)
        // Must start with a letter (not a digit or $)
        return if (word.first().isLetter()) word else null
    }
}
