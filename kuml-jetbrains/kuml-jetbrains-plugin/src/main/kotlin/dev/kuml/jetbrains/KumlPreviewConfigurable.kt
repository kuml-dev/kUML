package dev.kuml.jetbrains

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
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
        @Suppress("DEPRECATION")
        tf.addBrowseFolderListener(
            "kuml-CLI auswählen",
            "Pfad zur kuml-CLI-Executable (leer lassen für automatische Erkennung)",
            null,
            FileChooserDescriptorFactory.createSingleLocalFileDescriptor(),
        )
        tf.text = KumlPreviewSettings.cliPath().orEmpty()
        field = tf

        return FormBuilder
            .createFormBuilder()
            .addLabeledComponent(
                JLabel(
                    "<html>Pfad zur <code>kuml</code>-CLI (leer = automatische Erkennung über " +
                        "PATH / lokalen Gradle-Build):</html>",
                ),
                tf,
            ).addComponentFillVertically(JPanel(), 0)
            .panel
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
