package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.fillMaxSize
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
import com.mikepenz.markdown.m3.Markdown
import dev.kuml.workspace.OkfDocument
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
 */
@Composable
fun MarkdownDocPane(
    doc: OkfDocument?,
    linkHandler: WorkspaceLinkHandler,
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

    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Markdown(
            content = text,
            modifier = modifier.fillMaxSize(),
        )
    }
}
