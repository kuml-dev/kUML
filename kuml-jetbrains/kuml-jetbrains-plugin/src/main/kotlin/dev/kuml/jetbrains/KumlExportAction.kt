package dev.kuml.jetbrains

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Export context injected into [KumlPreviewPanel] by [KumlSplitEditorProvider].
 *
 * Holds the IntelliJ [project], the [sourceFile] being edited, and a
 * [currentText] supplier that returns the live editor content at export time.
 * The panel itself stays IntelliJ-free; all platform calls happen here.
 */
data class KumlExportContext(
    val project: Project,
    val sourceFile: VirtualFile,
    val currentText: () -> String,
)

/**
 * Orchestrates kUML diagram export (SVG / PNG / TeX) from the preview panel.
 *
 * All IntelliJ platform calls (save, file chooser, progress task, notifications)
 * live in this object so [KumlPreviewPanel] can remain headless-instantiable.
 *
 * ## Export flow
 * 1. Auto-save all open documents on the EDT.
 * 2. Derive a default output path from the source file name and chosen format.
 * 3. Show a platform save-file dialog for the user to confirm / change the path.
 * 4. Run the kuml CLI off-EDT inside a cancellable [Task.Backgroundable].
 * 5. Show a success balloon with a "Reveal in Finder/Explorer" action, or an
 *    error balloon carrying (truncated) CLI stderr on failure.
 */
internal object KumlExportAction {
    private const val NOTIFICATION_GROUP = "kUML Export"
    private const val MAX_STDERR_CHARS = 2000

    /**
     * Trigger an export of the kUML diagram described by [ctx] in [format]
     * with the given [theme].
     *
     * Must be called on the EDT (the file-save and dialog calls require it).
     */
    fun export(
        ctx: KumlExportContext,
        format: KumlExportFormat,
        theme: String,
    ) {
        // 1. Auto-save so the CLI reads current file content.
        FileDocumentManager.getInstance().saveAllDocuments()

        val scriptText = ctx.currentText()

        // 2. Derive default output path.
        val srcPath = ctx.sourceFile.path
        val srcDir = File(srcPath).parentFile
        val baseName =
            ctx.sourceFile.name
                .removeSuffix(".kuml.kts")
                .removeSuffix(".kts")
        val defaultName = "$baseName.${format.extension}"

        // 3. Show save dialog.
        val descriptor =
            FileSaverDescriptor(
                "Diagramm exportieren als ${format.displayName}",
                "Exportpfad wählen",
                format.extension,
            )
        val baseVDir = LocalFileSystem.getInstance().findFileByIoFile(srcDir)
        val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, ctx.project)
        val wrapper = saveDialog.save(baseVDir, defaultName) ?: return // user cancelled

        val rawFile = wrapper.file
        val outputFile =
            if (rawFile.name.endsWith(".${format.extension}")) {
                rawFile
            } else {
                File(rawFile.parentFile, "${rawFile.name}.${format.extension}")
            }

        // 4. Run CLI in background.
        val hintDir = File(srcPath).absoluteFile.parentFile

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                ctx.project,
                "Exportiere kUML-Diagramm…",
                true,
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "kuml render → ${outputFile.name}"

                    val binary = KumlCliLocator.resolve(hintDir)
                    if (binary == null) {
                        notifyError(
                            ctx.project,
                            "kUML-CLI nicht gefunden. Bitte in Settings → Tools → kUML Preview konfigurieren.",
                        )
                        return
                    }

                    val result =
                        KumlCliRenderer.exportToFile(
                            binary,
                            scriptText,
                            ctx.sourceFile.name,
                            outputFile,
                            format,
                            theme,
                        )

                    ApplicationManager.getApplication().invokeLater {
                        result.fold(
                            onSuccess = { notifySuccess(ctx.project, outputFile) },
                            onFailure = { e -> notifyError(ctx.project, e.message ?: "(unbekannter Fehler)") },
                        )
                    }
                }
            },
        )
    }

    private fun notifySuccess(
        project: Project,
        file: File,
    ) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        val notification =
            group.createNotification(
                "kUML Export",
                "Exportiert nach: ${file.absolutePath}",
                NotificationType.INFORMATION,
            )
        if (RevealFileAction.isSupported()) {
            notification.addAction(
                com.intellij.notification.NotificationAction.createSimple("Im Finder anzeigen") {
                    RevealFileAction.openFile(file)
                },
            )
        }
        notification.notify(project)
    }

    private fun notifyError(
        project: Project,
        message: String,
    ) {
        val truncated =
            if (message.length > MAX_STDERR_CHARS) {
                message.take(MAX_STDERR_CHARS) + "\n…(abgeschnitten)"
            } else {
                message
            }
        // Escape HTML to prevent CLI stderr content from being interpreted as HTML
        // in the IDE balloon (same escaping used in KumlPreviewPanel.showMessage()).
        val escaped =
            truncated
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        val errNotification =
            group.createNotification(
                "kUML Export fehlgeschlagen",
                escaped,
                NotificationType.ERROR,
            )
        errNotification.notify(project)
    }
}
