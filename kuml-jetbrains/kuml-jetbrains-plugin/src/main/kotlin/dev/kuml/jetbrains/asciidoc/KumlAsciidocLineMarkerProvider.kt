package dev.kuml.jetbrains.asciidoc

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import dev.kuml.jetbrains.KumlExportAction
import dev.kuml.jetbrains.KumlExportContext
import dev.kuml.jetbrains.KumlExportFormat
import dev.kuml.jetbrains.KumlIcons
import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.KumlPreviewSettings
import dev.kuml.jetbrains.preview.KumlDocPreviewCache
import org.asciidoc.intellij.psi.AsciiDocBlockMacro
import org.asciidoc.intellij.psi.AsciiDocListing
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path

/**
 * Gutter icons and context actions on kUML listings and `kuml::` block macros in AsciiDoc files.
 */
class KumlAsciidocLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return when (element) {
            is AsciiDocListing -> {
                val fenceLanguage = element.fenceLanguage ?: return null
                if (!isKumlFenceLanguage(fenceLanguage)) return null
                markerFor(element) { event -> showListingPopup(event, element) }
            }
            is AsciiDocBlockMacro -> {
                val macroName = element.macroName ?: return null
                if (!macroName.equals("kuml", ignoreCase = true)) return null
                markerFor(element) { event -> showMacroPopup(event, element) }
            }
            else -> null
        }
    }

    private fun markerFor(
        element: PsiElement,
        onClick: (MouseEvent) -> Unit,
    ): LineMarkerInfo<*> {
        val anchor = element.firstChild ?: element
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            KumlIcons.SCRIPT,
            { "kUML Diagram Actions" },
            { event, _ ->
                if (event is MouseEvent) {
                    onClick(event)
                }
            },
            GutterIconRenderer.Alignment.RIGHT,
            { "kUML Diagram" },
        )
    }

    private fun showListingPopup(
        event: MouseEvent,
        listing: AsciiDocListing,
    ) {
        val scriptText = extractListingContent(listing)
        val attributes = extractListingAttributes(listing)
        val theme = attributes["theme"]?.takeIf { it in KumlPreviewSettings.THEMES } ?: KumlPreviewSettings.theme()
        val name = attributes["name"] ?: "diagram"
        showPopup(event, listing, scriptText, theme, name)
    }

    private fun showMacroPopup(
        event: MouseEvent,
        macro: AsciiDocBlockMacro,
    ) {
        val targetPath = extractMacroTarget(macro)
        val attributes = extractMacroAttributes(macro)
        val theme = attributes["theme"]?.takeIf { it in KumlPreviewSettings.THEMES } ?: KumlPreviewSettings.theme()
        val name = attributes["name"] ?: targetPath.ifBlank { "diagram" }
        val scriptText = readMacroSource(macro, targetPath)
        showPopup(event, macro, scriptText, theme, name)
    }

    private fun showPopup(
        event: MouseEvent,
        element: PsiElement,
        scriptText: String,
        theme: String,
        name: String,
    ) {
        val project = element.project
        val vFile = element.containingFile?.virtualFile

        val group =
            DefaultActionGroup().apply {
                add(
                    object : AnAction("Export as SVG...") {
                        override fun actionPerformed(e: AnActionEvent) {
                            if (vFile != null) {
                                val ctx = KumlExportContext(project, vFile) { scriptText }
                                KumlExportAction.export(ctx, KumlExportFormat.SVG, theme)
                            }
                        }
                    },
                )
                add(
                    object : AnAction("Export as PNG...") {
                        override fun actionPerformed(e: AnActionEvent) {
                            if (vFile != null) {
                                val ctx = KumlExportContext(project, vFile) { scriptText }
                                KumlExportAction.export(ctx, KumlExportFormat.PNG, theme)
                            }
                        }
                    },
                )
                add(
                    object : AnAction("Export as TeX...") {
                        override fun actionPerformed(e: AnActionEvent) {
                            if (vFile != null) {
                                val ctx = KumlExportContext(project, vFile) { scriptText }
                                KumlExportAction.export(ctx, KumlExportFormat.TEX, theme)
                            }
                        }
                    },
                )
                addSeparator()
                add(
                    object : AnAction("Copy Rendered SVG") {
                        override fun actionPerformed(e: AnActionEvent) {
                            val outcome = KumlDocPreviewCache.getOrRender(scriptText, theme, name)
                            if (outcome is KumlPreviewRenderer.Outcome.Svg) {
                                CopyPasteManager.getInstance().setContents(StringSelection(outcome.svg))
                            }
                        }
                    },
                )
                add(
                    object : AnAction("Copy Diagram Source") {
                        override fun actionPerformed(e: AnActionEvent) {
                            CopyPasteManager.getInstance().setContents(StringSelection(scriptText))
                        }
                    },
                )
            }

        val dataContext = DataManager.getInstance().getDataContext(event.component)
        val popup =
            JBPopupFactory.getInstance().createActionGroupPopup(
                "kUML Diagram Actions ($name)",
                group,
                dataContext,
                JBPopupFactory.ActionSelectionAid.MNEMONICS,
                true,
            )
        popup.show(
            com.intellij.ui.awt
                .RelativePoint(event),
        )
    }

    companion object {
        /**
         * AsciiDoc plugin fence languages look like `source-kuml` (LanguageGuesser splits on `-`).
         * Also accept bare `kuml` / `kuml …` for robustness.
         */
        fun isKumlFenceLanguage(fenceLanguage: String): Boolean {
            val trimmed = fenceLanguage.trim()
            if (trimmed.isEmpty()) return false
            val langPart =
                when {
                    trimmed.contains('-') -> trimmed.substringAfterLast('-')
                    else -> trimmed
                }
            return langPart.equals("kuml", ignoreCase = true) ||
                langPart.startsWith("kuml", ignoreCase = true)
        }

        fun extractListingContent(listing: AsciiDocListing): String {
            return try {
                val range = listing.contentTextRange
                if (range == null || range.isEmpty) {
                    // Fallback: strip opening/closing fence lines from full text.
                    val lines = listing.text.lines()
                    if (lines.size <= 2) return ""
                    return lines.subList(1, lines.size - 1).joinToString("\n")
                }
                val full = listing.text
                val start = (range.startOffset - listing.textRange.startOffset).coerceAtLeast(0)
                val end = (range.endOffset - listing.textRange.startOffset).coerceAtMost(full.length)
                if (start >= end) "" else full.substring(start, end).trimEnd('\n', '\r')
            } catch (_: Throwable) {
                ""
            }
        }

        private fun extractListingAttributes(listing: AsciiDocListing): Map<String, String> {
            // Attributes live on the preceding `[source,kuml,…]` header which is part of
            // the listing element's text in most AsciiDoc PSI trees — parse from text.
            val headerLine =
                listing.text
                    .lineSequence()
                    .firstOrNull()
                    .orEmpty()
            val m = Regex("""\[source\s*,\s*kuml(?:\s*,\s*([^\]]*))?\s*]""").find(headerLine)
            return KumlAsciidocBlockParser.parseAttributes(m?.groupValues?.getOrNull(1).orEmpty())
        }

        private fun extractMacroTarget(macro: AsciiDocBlockMacro): String {
            // Prefer resolved body (path without brackets); fall back to regex on text.
            return try {
                val body = macro.resolvedBody
                if (!body.isNullOrBlank()) return body.trim()
                val m = Regex("""kuml::([^\s\[\]]+)""").find(macro.text)
                m?.groupValues?.getOrNull(1).orEmpty()
            } catch (_: Throwable) {
                val m = Regex("""kuml::([^\s\[\]]+)""").find(macro.text)
                m?.groupValues?.getOrNull(1).orEmpty()
            }
        }

        private fun extractMacroAttributes(macro: AsciiDocBlockMacro): Map<String, String> {
            val m = Regex("""kuml::[^\s\[\]]+\[([^\]]*)]""").find(macro.text)
            return KumlAsciidocBlockParser.parseAttributes(m?.groupValues?.getOrNull(1).orEmpty())
        }

        private fun readMacroSource(
            macro: AsciiDocBlockMacro,
            targetPath: String,
        ): String {
            if (targetPath.isBlank()) return ""
            val vFile = macro.containingFile?.virtualFile ?: return ""
            val parent = vFile.parent ?: return ""
            val adocParent: Path =
                try {
                    parent.toNioPath()
                } catch (_: Throwable) {
                    return ""
                }
            val projectBase =
                try {
                    macro.project.basePath?.let { Path.of(it) }
                } catch (_: Throwable) {
                    null
                }
            return when (val guard = KumlAsciidocPathGuard.resolve(targetPath, adocParent, projectBase)) {
                is KumlAsciidocPathGuard.Result.Rejected -> ""
                is KumlAsciidocPathGuard.Result.Ok -> {
                    try {
                        if (Files.isRegularFile(guard.resolvedPath) && Files.isReadable(guard.resolvedPath)) {
                            Files.readString(guard.resolvedPath)
                        } else {
                            // Also try VFS — but strictly on the guard-APPROVED location
                            // (guard.resolvedPath), never by re-deriving a path from the
                            // raw, unvalidated `targetPath` string. Re-parsing targetPath
                            // here would resolve it through an entirely separate mechanism
                            // that bypasses KumlAsciidocPathGuard altogether.
                            val vf = LocalFileSystem.getInstance().findFileByNioFile(guard.resolvedPath)
                            vf?.contentsToByteArray()?.toString(Charsets.UTF_8).orEmpty()
                        }
                    } catch (_: Throwable) {
                        ""
                    }
                }
            }
        }
    }
}
