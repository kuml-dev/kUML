package dev.kuml.desktop.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.ResolvedLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only rendered Markdown of the selected document's full source (V3.6.4),
 * via the `multiplatform-markdown-renderer-m3` library.
 *
 * Link clicks are routed through [linkHandler] instead of the platform default
 * `UriHandler`: internal relative links navigate the workspace tree, external
 * links (http/https/mailto) open the system browser, everything else is refused
 * (see [DefaultWorkspaceLinkHandler]). Integrated via [LocalUriHandler] override —
 * the library has no dedicated link-listener parameter on `Markdown(...)`, but it
 * reads `LocalUriHandler` internally for all link/image click handling.
 *
 * [backlinks] surfaces the reverse direction (ADR-0011 FT-6, [dev.kuml.workspace.WorkspaceGraphIndex]):
 * documents that link *to* [doc], which forward-only link clicks can never reveal
 * on their own. Rendered as a footer strip below the prose, not a separate pane —
 * it's a secondary, occasional relationship, not something that should compete
 * with the document body for primary screen space.
 */
@Composable
fun MarkdownDocPane(
    doc: OkfDocument?,
    linkHandler: WorkspaceLinkHandler,
    backlinks: List<ResolvedLink> = emptyList(),
    onNavigateBacklink: (OkfDocument) -> Unit = {},
    strings: Strings? = null,
    modifier: Modifier = Modifier,
) {
    if (doc == null) return

    var source by remember(doc.file) { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.file) {
        source =
            withContext(Dispatchers.IO) {
                runCatching { doc.file.readText(Charsets.UTF_8) }.getOrDefault("")
            }
    }
    val text = source ?: return

    val uriHandler =
        remember(linkHandler) {
            object : UriHandler {
                override fun openUri(uri: String) = linkHandler.onLink(uri)
            }
        }

    Column(modifier = modifier) {
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            Markdown(
                content = text,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }
        if (backlinks.isNotEmpty() && strings != null) {
            HorizontalDivider()
            BacklinksBar(backlinks = backlinks, label = strings.workspaceBacklinksLabel, onNavigate = onNavigateBacklink)
        }
    }
}

@Composable
private fun BacklinksBar(
    backlinks: List<ResolvedLink>,
    label: String,
    onNavigate: (OkfDocument) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text = label, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
        backlinks.distinctBy { it.from.relativePath }.forEach { link ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier =
                    Modifier
                        .padding(end = 6.dp)
                        .clickable { onNavigate(link.from) },
            ) {
                Text(
                    text = link.from.file.name,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}
