package dev.kuml.jetbrains

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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
    private val LOG = Logger.getInstance(KumlExportAction::class.java)

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
        //
        // NOTE on version-adaptive FileSaverDescriptor construction:
        // IntelliJ Platform 2025.1 (build 251+) introduced a non-deprecated, non-vararg
        // FileSaverDescriptor(String, String, String) constructor and marked the previously
        // sole FileSaverDescriptor(String, String, String...) vararg constructor @Deprecated.
        // Verified via javap against the real ideaIU-2024.3.7.1 and ideaIU-2025.1.7.2
        // app-client.jar class files: 243.x exposes ONLY the vararg ctor (not deprecated
        // there — it's the sole option); 251.x exposes BOTH, with only the vararg one
        // flagged Deprecated.
        //
        // sinceBuild="243" (see build.gradle.kts) means this plugin still runs on real
        // 2024.3.x/2025.0.x installs where the new 3-arg constructor does not exist as a
        // class member AT ALL (different method descriptor, not just "deprecated"). A plain
        // Kotlin `FileSaverDescriptor(title, description, format.extension)` call compiles
        // to whichever overload the COMPILE-TIME SDK happens to expose — against our 251.x
        // compile SDK that silently resolves to the NEW constructor, which would crash with
        // NoSuchMethodError the moment a real 2024.3.x/2025.0.x user clicks Export. DO NOT
        // "simplify" this back to a plain constructor call — the compiler will not warn you,
        // because from ITS point of view it's calling a perfectly valid, non-deprecated ctor.
        //
        // Both constructors are therefore invoked reflectively, mirroring the version-skew-
        // safety idiom already established in KumlScriptDefinitionsSource.kt for the same
        // class of compile-time/runtime platform-version mismatch. Feature-detection
        // (getConstructor success/failure) is used instead of a hardcoded build-number
        // check, so this keeps working correctly even if some future platform build changes
        // exactly which generation carries which overload.
        //
        // Verified side effect: reflective invocation also hides BOTH call sites from the
        // JetBrains Plugin Verifier's static deprecated-API bytecode scan (it flags any
        // direct symbolic reference to a member deprecated in the verification target,
        // regardless of whether a runtime guard makes that instruction unreachable there) —
        // eliminating the "deprecated constructor" finding against every verified target
        // build, not only the sinceBuild floor.
        val descriptor =
            buildFileSaverDescriptor(format) ?: run {
                notifyError(
                    ctx.project,
                    "Interner Fehler: Speicherdialog konnte nicht erstellt werden (siehe idea.log).",
                )
                return
            }
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

    /**
     * Builds a [FileSaverDescriptor] using whichever constructor actually exists on the
     * running platform, resolved reflectively. See the NOTE above [export] for why this
     * cannot be a plain constructor call.
     *
     * Returns `null` if neither constructor can be found and invoked (e.g. a future
     * platform build changes the constructor shape yet again, or `newInstance` throws).
     * The whole probe-and-invoke sequence — including the legacy fallback and both
     * `newInstance` calls — is wrapped in a single `try/catch(Throwable)`, mirroring the
     * reflective-construction idiom in `KumlScriptDefinitionsSource.kt`: fail safe with a
     * logged warning instead of letting a `NoSuchMethodException` /
     * `InvocationTargetException` / `IllegalAccessException` / `InstantiationException`
     * propagate uncaught through the AnAction/line-marker click handlers that call
     * [export].
     */
    private fun buildFileSaverDescriptor(format: KumlExportFormat): FileSaverDescriptor? {
        val title = "Diagramm exportieren als ${format.displayName}"
        val description = "Exportpfad wählen"

        return try {
            // Prefer the non-deprecated 2025.1+ (String, String, String) constructor when
            // it exists on this runtime; fall back to the pre-2025.1
            // (String, String, String...) vararg constructor otherwise. getConstructor
            // throws NoSuchMethodException when the member is absent — used here as the
            // actual runtime feature test rather than sniffing ApplicationInfo's build
            // number, so this stays correct even if a future platform build changes
            // exactly which generation carries the new overload.
            val modernCtor =
                runCatching {
                    FileSaverDescriptor::class.java.getConstructor(
                        String::class.java,
                        String::class.java,
                        String::class.java,
                    )
                }.getOrNull()
            if (modernCtor != null) {
                modernCtor.newInstance(title, description, format.extension) as FileSaverDescriptor
            } else {
                LOG.warn(
                    "kUML export: modern FileSaverDescriptor(String,String,String) ctor not found " +
                        "on this platform build, falling back to legacy vararg ctor",
                )
                val legacyCtor =
                    FileSaverDescriptor::class.java.getConstructor(
                        String::class.java,
                        String::class.java,
                        Array<String>::class.java,
                    )
                legacyCtor.newInstance(title, description, arrayOf(format.extension)) as FileSaverDescriptor
            }
        } catch (t: Throwable) {
            LOG.warn("kUML export: failed to construct FileSaverDescriptor reflectively", t)
            null
        }
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
