package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings
import kotlinx.coroutines.launch

/**
 * Three-column Knowledge-mode workspace layout (V3.6.4):
 * document tree | rendered Markdown | live SVG preview.
 */
@Composable
fun KnowledgeWorkspaceScreen(
    state: WorkspaceState,
    themeName: String,
    strings: Strings,
    modifier: Modifier = Modifier,
    // V3.7.4 (design review P6/P9) — this screen previously never reacted to a theme or
    // watermark change at all: it only re-renders when `state.select(...)` is called
    // explicitly from a tree/backlink click. Changing View ▸ Theme or View ▸ Wasserzeichen
    // while a Knowledge document was open silently did nothing until the user clicked
    // something in the tree again.
    showWatermark: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val linkHandler =
        remember(state) {
            DefaultWorkspaceLinkHandler(
                workspace = state.workspace,
                currentDoc = { state.selected },
                onNavigate = { doc ->
                    scope.launch { state.select(doc = doc, themeName = themeName, strings = strings, watermark = showWatermark) }
                },
            )
        }

    // Re-renders the CURRENTLY selected document (if any) whenever the theme or watermark
    // setting changes -- see the KDoc above. No-op when nothing is selected yet.
    LaunchedEffect(themeName, showWatermark) {
        state.selected?.let { state.select(doc = it, themeName = themeName, strings = strings, watermark = showWatermark) }
    }

    Row(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        WorkspaceTreePane(
            documents = state.documents,
            selected = state.selected,
            onSelect = { doc ->
                scope.launch { state.select(doc = doc, themeName = themeName, strings = strings, watermark = showWatermark) }
            },
            strings = strings,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
        MarkdownDocPane(
            doc = state.selected,
            linkHandler = linkHandler,
            backlinks = state.selected?.let { state.graphIndex.backlinks(it) }.orEmpty(),
            onNavigateBacklink = { doc ->
                scope.launch { state.select(doc = doc, themeName = themeName, strings = strings, watermark = showWatermark) }
            },
            strings = strings,
            modifier = Modifier.weight(2f).fillMaxHeight(),
        )
        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
        WorkspacePreviewPane(
            docSvg = state.docSvg,
            docError = state.docError,
            isRendering = state.isRendering,
            hasSelection = state.selected != null,
            strings = strings,
            modifier = Modifier.weight(2f).fillMaxHeight(),
        )
    }
}
