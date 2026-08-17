package dev.kuml.jetbrains.asciidoc

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import org.asciidoc.intellij.editor.AsciiDocHtmlPanel
import org.asciidoc.intellij.editor.AsciiDocHtmlPanelProvider
import org.asciidoc.intellij.editor.jcef.AsciiDocJCEFHtmlPanelProvider
import java.nio.file.Path

/**
 * AsciiDoc HTML panel provider that wraps the stock JCEF panel and rewrites
 * preview HTML to render kUML diagrams inline.
 *
 * Registered with `order="first"` in `kuml-asciidoc-support.xml` so it is
 * preferred when the AsciiDoc plugin is present.
 */
class KumlAsciidocHtmlPanelProvider : AsciiDocHtmlPanelProvider() {
    private val jcefDelegate = AsciiDocJCEFHtmlPanelProvider()

    override fun createHtmlPanel(
        document: Document,
        imagesPath: Path,
        forceRefresh: Runnable,
    ): AsciiDocHtmlPanel {
        val inner = jcefDelegate.createHtmlPanel(document, imagesPath, forceRefresh)
        val project = resolveProject(document)
        val watcher =
            if (project != null) {
                KumlAsciidocReferencedFileWatcher(
                    project = project,
                    parentDisposable = inner,
                    onReferencedFileChanged = {
                        // Trigger a full re-render of the preview when a macro target changes.
                        try {
                            forceRefresh.run()
                        } catch (_: Throwable) {
                            try {
                                inner.render()
                            } catch (_: Throwable) {
                                // ignore — preview may already be disposed
                            }
                        }
                    },
                )
            } else {
                null
            }
        return KumlAsciidocHtmlPanel(
            delegate = inner,
            document = document,
            imagesDir = imagesPath,
            project = project,
            fileWatcher = watcher,
        )
    }

    override fun isAvailable(): AvailabilityInfo = jcefDelegate.isAvailable()

    override fun getProviderInfo(): ProviderInfo =
        ProviderInfo(
            "kUML AsciiDoc Preview (JCEF)",
            KumlAsciidocHtmlPanelProvider::class.java.name,
        )

    private fun resolveProject(document: Document): Project? {
        return try {
            val vFile = FileDocumentManager.getInstance().getFile(document) ?: return null
            ProjectLocator.getInstance().guessProjectForFile(vFile)
        } catch (_: Throwable) {
            null
        }
    }
}
