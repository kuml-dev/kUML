package dev.kuml.jetbrains

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Settings page (Settings → Tools → kUML Preview) for configuring the path to
 * the external `kuml` CLI used by the live preview.
 *
 * Leaving the field empty enables auto-detection ([KumlCliLocator]): PATH,
 * common install locations, and a local Gradle build discovered by walking up
 * from the edited file.
 */
internal class KumlPreviewConfigurable : Configurable {
    private var field: TextFieldWithBrowseButton? = null

    override fun getDisplayName(): String = "kUML Preview"

    override fun createComponent(): JComponent {
        val tf = TextFieldWithBrowseButton()
        tf.text = KumlPreviewSettings.cliPath().orEmpty()
        field = tf

        val panel = JPanel(BorderLayout(8, 8))
        panel.add(
            JLabel(
                "<html>Pfad zur <code>kuml</code>-CLI (leer = automatische Erkennung über PATH / " +
                    "lokalen Gradle-Build):</html>",
            ),
            BorderLayout.NORTH,
        )
        panel.add(tf, BorderLayout.CENTER)
        return panel
    }

    override fun isModified(): Boolean = (field?.text?.trim() ?: "") != (KumlPreviewSettings.cliPath() ?: "")

    override fun apply() {
        KumlPreviewSettings.setCliPath(field?.text?.trim())
    }

    override fun reset() {
        field?.text = KumlPreviewSettings.cliPath().orEmpty()
    }

    override fun disposeUIResources() {
        field = null
    }
}
