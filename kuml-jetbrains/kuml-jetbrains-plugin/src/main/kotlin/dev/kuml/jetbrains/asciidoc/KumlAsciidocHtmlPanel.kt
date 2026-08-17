package dev.kuml.jetbrains.asciidoc

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.asciidoc.intellij.editor.AsciiDocHtmlPanel
import java.nio.file.Path
import java.util.function.Consumer
import javax.swing.JComponent

/**
 * Decorator around a stock [AsciiDocHtmlPanel] that rewrites the preview HTML
 * to inline rendered kUML diagrams before the HTML reaches JCEF.
 */
internal class KumlAsciidocHtmlPanel(
    private val delegate: AsciiDocHtmlPanel,
    private val document: Document,
    private val imagesDir: Path,
    private val project: Project?,
    private val fileWatcher: KumlAsciidocReferencedFileWatcher?,
) : AsciiDocHtmlPanel {
    override fun getComponent(): JComponent = delegate.component

    override fun setHtml(
        html: String,
        attributes: MutableMap<String, String>,
    ) {
        val adocSource = document.text
        val baseDir = resolveBaseDir()
        val projectBase = resolveProjectBaseDir()
        val rewritten =
            KumlAsciidocHtmlRewriter.rewrite(
                html = html,
                adocSource = adocSource,
                baseDir = baseDir,
                projectBaseDir = projectBase,
                onResolvedMacroPaths = { paths -> fileWatcher?.updateWatchedPaths(paths) },
            )
        delegate.setHtml(rewritten, attributes)
    }

    override fun render() {
        delegate.render()
    }

    override fun scrollToLine(
        line: Int,
        lineCount: Int,
    ) {
        delegate.scrollToLine(line, lineCount)
    }

    override fun getEditor(): Editor? = delegate.editor

    override fun setEditor(editor: Editor?) {
        delegate.editor = editor
    }

    override fun printToPdf(
        path: String,
        callback: Consumer<Boolean>?,
    ) {
        delegate.printToPdf(path, callback)
    }

    override fun isPrintingSupported(): Boolean = delegate.isPrintingSupported

    override fun getPreferredFocusedComponent(): JComponent? = delegate.preferredFocusedComponent

    override fun dispose() {
        fileWatcher?.dispose()
        delegate.dispose()
    }

    private fun resolveBaseDir(): Path {
        // Prefer the directory of the document's virtual file; fall back to imagesDir parent.
        val vFile = resolveVirtualFile()
        val parent = vFile?.parent
        if (parent != null && parent.isValid) {
            return parent.toNioPath()
        }
        return imagesDir.toAbsolutePath().normalize().let { dir ->
            // imagesDir is often `<adocParent>/.asciidoctor/images` or similar — use its parent when present.
            val name = dir.fileName?.toString().orEmpty()
            if (name.equals("images", ignoreCase = true) || dir.toString().contains(".asciidoctor")) {
                dir.parent ?: dir
            } else {
                dir
            }
        }
    }

    private fun resolveProjectBaseDir(): Path? {
        val p = project ?: return null
        return try {
            p.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveVirtualFile(): VirtualFile? =
        try {
            com.intellij.openapi.fileEditor.FileDocumentManager
                .getInstance()
                .getFile(document)
        } catch (_: Throwable) {
            null
        }
}
