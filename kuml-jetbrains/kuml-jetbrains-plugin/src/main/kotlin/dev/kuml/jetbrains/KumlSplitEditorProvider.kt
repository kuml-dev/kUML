package dev.kuml.jetbrains

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile

/**
 * Split-editor provider for `.kuml.kts` files (V2.0.28b).
 *
 * Registers a `TextEditorWithPreview` that shows:
 *  - Left pane: the standard Kotlin script editor (syntax highlighting,
 *    completion, error squiggles from [KumlAnnotator])
 *  - Right pane: a [KumlPreviewPanel] that live-renders the diagram as SVG
 *    with a 300 ms debounce on every document edit.
 *
 * The provider is registered in `plugin.xml` via the `com.intellij.fileEditorProvider`
 * extension point with [EDITOR_TYPE_ID] = `"kuml-split-editor"`.
 *
 * ## Disabling the split view
 *
 * Set the system property `kuml.preview.disabled` to any value to suppress
 * both the SVG rendering and the split layout. When disabled, [accept] returns
 * `false` so the platform uses the standard Kotlin editor instead.
 */
class KumlSplitEditorProvider :
    FileEditorProvider,
    DumbAware {
    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    /**
     * Accept `.kuml.kts` files unless the split preview is explicitly disabled
     * via the `kuml.preview.disabled` system property.
     */
    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = isKumlFile(file.name) && !isDisabled()

    override fun createEditor(
        project: Project,
        file: VirtualFile,
    ): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val previewPanel = KumlPreviewPanel()

        // Wire document listener → debounced render
        val document = textEditor.editor.document
        document.addDocumentListener(
            object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                    previewPanel.scheduleUpdate(
                        scriptText = document.text,
                        scriptName = file.path,
                    )
                }
            },
        )

        // Trigger initial render for the current content
        previewPanel.scheduleUpdate(
            scriptText = document.text,
            scriptName = file.path,
        )

        return KumlSplitEditorWrapper(textEditor, previewPanel)
    }

    // HIDE_DEFAULT_EDITOR statt PLACE_BEFORE_DEFAULT_EDITOR: unser Split-Editor
    // enthält bereits einen vollwertigen Text-Editor (linke Pane). Ohne diese
    // Policy bietet IntelliJ zusätzlich den Default-"Text"-Editor an → zwei
    // Editor-Tabs unten ("Text" / "TextEditorWithPreview"). Mit HIDE_DEFAULT_EDITOR
    // gibt es nur noch unseren Editor → keine unteren Tabs; der Layout-Umschalter
    // (Editor / Split / Preview) bleibt oben rechts erhalten.
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID: String = "kuml-split-editor"
        const val DISABLE_PROPERTY: String = KumlPreviewPanel.DISABLE_SYSTEM_PROPERTY

        /**
         * Pure function: returns `true` if [fileName] is a kUML script.
         * Extracted for unit testability without an IntelliJ runtime.
         */
        fun isKumlFile(fileName: String): Boolean = fileName.endsWith(".kuml.kts")

        /**
         * Returns `true` if the split preview is disabled via system property.
         */
        fun isDisabled(): Boolean = System.getProperty(DISABLE_PROPERTY) != null
    }
}

/**
 * A thin [TextEditorWithPreview] subclass that pairs the text editor with
 * [KumlPreviewPanel] and ensures [KumlPreviewPanel.dispose] is called on close.
 */
private class KumlSplitEditorWrapper(
    textEditor: TextEditor,
    private val previewPanel: KumlPreviewPanel,
) : TextEditorWithPreview(
        textEditor,
        KumlPreviewFileEditor(previewPanel),
    ) {
    override fun dispose() {
        previewPanel.dispose()
        super.dispose()
    }
}

/**
 * Minimal [FileEditor] adapter that wraps [KumlPreviewPanel] for the
 * [TextEditorWithPreview] right-pane slot.
 *
 * Only the methods required by the IntelliJ API contract are implemented —
 * the preview panel is read-only and has no persistent state.
 */
private class KumlPreviewFileEditor(
    private val panel: KumlPreviewPanel,
) : UserDataHolderBase(),
    com.intellij.openapi.fileEditor.FileEditor {
    override fun getComponent() = panel

    override fun getPreferredFocusedComponent() = panel

    override fun getName() = "kUML Preview"

    override fun getState(level: com.intellij.openapi.fileEditor.FileEditorStateLevel) =
        com.intellij.openapi.fileEditor.FileEditorState.INSTANCE

    override fun setState(state: com.intellij.openapi.fileEditor.FileEditorState) = Unit

    override fun isModified() = false

    override fun isValid() = true

    override fun addPropertyChangeListener(listener: java.beans.PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: java.beans.PropertyChangeListener) = Unit

    override fun dispose() = panel.dispose()

    override fun getFile(): com.intellij.openapi.vfs.VirtualFile? = null
}
