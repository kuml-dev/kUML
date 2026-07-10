package dev.kuml.widget.compose

/**
 * Stable test tags for the OCL guard editor + transitions section, shared by
 * the composables and their Compose UI tests (single source of truth).
 */
internal object EditorTestTags {
    const val GUARD_INPUT = "guardEditor.input"
    const val GUARD_ERROR = "guardEditor.error"
    const val GUARD_SAVE = "guardEditor.save"
    const val GUARD_CANCEL = "guardEditor.cancel"
    const val TRANSITIONS_SECTION = "controlPanel.transitions"
    const val CONFIRM_DIALOG_TITLE = "guardEditor.confirmTitle"
    const val CONFIRM_APPLY = "guardEditor.confirmApply"
    const val CONFIRM_CANCEL = "guardEditor.confirmCancel"

    fun transitionRow(id: String): String = "controlPanel.transition.$id"
}
