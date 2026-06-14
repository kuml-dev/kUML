package dev.kuml.desktop.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import dev.kuml.desktop.AppState
import dev.kuml.desktop.render.DesktopRenderController
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

@Composable
fun EditorPane(
    state: AppState,
    controller: DesktopRenderController,
    modifier: Modifier = Modifier,
) {
    val textArea = remember {
        RSyntaxTextArea().apply {
            syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_KOTLIN
            antiAliasingEnabled = true
            isCodeFoldingEnabled = true
            tabSize = 4
            text = state.script
        }
    }

    DisposableEffect(textArea) {
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onChanged()
            override fun removeUpdate(e: DocumentEvent) = onChanged()
            override fun changedUpdate(e: DocumentEvent) = onChanged()
            private fun onChanged() {
                val newScript = textArea.text
                state.script = newScript
                controller.scheduleRender(newScript)
            }
        }
        textArea.document.addDocumentListener(listener)
        onDispose { textArea.document.removeDocumentListener(listener) }
    }

    SwingPanel(
        factory = { RTextScrollPane(textArea) },
        modifier = modifier,
    )
}
