package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
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
) {
    val scope = rememberCoroutineScope()
    val linkHandler =
        remember(state) {
            DefaultWorkspaceLinkHandler(
                workspace = state.workspace,
                currentDoc = { state.selected },
                onNavigate = { doc -> scope.launch { state.select(doc, themeName, strings) } },
            )
        }

    Row(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        WorkspaceTreePane(
            documents = state.documents,
            selected = state.selected,
            onSelect = { doc -> scope.launch { state.select(doc, themeName, strings) } },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
        MarkdownDocPane(
            doc = state.selected,
            linkHandler = linkHandler,
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
