package dev.kuml.jetbrains.markdown

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import dev.kuml.jetbrains.KumlExportAction
import dev.kuml.jetbrains.KumlExportContext
import dev.kuml.jetbrains.KumlExportFormat
import dev.kuml.jetbrains.KumlIcons
import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.KumlPreviewSettings
import dev.kuml.jetbrains.preview.KumlDocPreviewCache
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownCodeFence
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent

/**
 * Provides gutter icons and context actions on ```` ```kuml ```` code fences in Markdown files.
 */
class KumlMarkdownLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is MarkdownCodeFence) return null
        val fenceLanguage = element.fenceLanguage ?: return null
        if (!fenceLanguage.trim().startsWith("kuml", ignoreCase = true)) return null

        val anchor = element.firstChild ?: element
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            KumlIcons.SCRIPT,
            { "kUML Diagram Actions" },
            { event, _ ->
                if (event is MouseEvent) {
                    showActionPopup(event, element)
                }
            },
            GutterIconRenderer.Alignment.RIGHT,
            { "kUML Diagram" },
        )
    }

    private fun showActionPopup(
        event: MouseEvent,
        fence: MarkdownCodeFence,
    ) {
        val fenceLanguage = fence.fenceLanguage.orEmpty()
        val attributes = KumlMarkdownFenceInfo.parseAttributes(fenceLanguage)
        val theme = attributes["theme"]?.takeIf { it in KumlPreviewSettings.THEMES } ?: KumlPreviewSettings.theme()
        val name = attributes["name"] ?: "diagram"
        val scriptText = KumlMarkdownFenceInfo.extractFenceContent(fence)
        val project = fence.project
        val vFile = fence.containingFile.virtualFile

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
}
