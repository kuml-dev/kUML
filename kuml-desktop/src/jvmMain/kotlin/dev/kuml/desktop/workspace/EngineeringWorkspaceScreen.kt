package dev.kuml.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.AppState
import dev.kuml.desktop.editor.EditorPane
import dev.kuml.desktop.io.FileMenu
import dev.kuml.desktop.preview.PreviewPane
import dev.kuml.desktop.render.DesktopRenderController
import java.io.File

/**
 * Engineering-mode workspace layout (V3.6.4): a tree of `*.kuml.kts` files next
 * to the existing single-file [EditorPane]/[PreviewPane] pair. Selecting a script
 * loads it into [state] via [AppState.loadFrom] — exactly the normal File → Open
 * flow — so the existing [DesktopRenderController]/debounce/render pipeline picks
 * it up unchanged.
 */
@Composable
fun EngineeringWorkspaceScreen(
    state: AppState,
    controller: DesktopRenderController,
    scriptFiles: List<File>,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        EngineeringFileTreePane(
            files = scriptFiles,
            selected = state.currentFile,
            onSelect = { file ->
                state.loadFrom(file, FileMenu.readScript(file))
                state.isDirty = false
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
        EditorPane(
            state = state,
            controller = controller,
            modifier = Modifier.weight(2f).fillMaxHeight(),
        )
        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
        PreviewPane(
            state = state,
            modifier = Modifier.weight(2f).fillMaxHeight(),
        )
    }
}

@Composable
private fun EngineeringFileTreePane(
    files: List<File>,
    selected: File?,
    onSelect: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(files, key = { it.absolutePath }) { file ->
            val bg = if (file == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            Text(
                text = file.name,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .clickable { onSelect(file) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
