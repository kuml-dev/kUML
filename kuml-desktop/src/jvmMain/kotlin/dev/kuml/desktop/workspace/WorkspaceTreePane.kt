package dev.kuml.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfType

/**
 * Document tree for a Knowledge-mode workspace (V3.6.4), grouped by top-level
 * folder (`articles/`, `concepts/`, `models/`, `glossary/`, or the workspace
 * root). Clicking a document invokes [onSelect].
 */
@Composable
fun WorkspaceTreePane(
    documents: List<OkfDocument>,
    selected: OkfDocument?,
    onSelect: (OkfDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups: Map<String, List<OkfDocument>> =
        documents
            .groupBy { doc ->
                val slash = doc.relativePath.indexOf('/')
                if (slash < 0) "" else doc.relativePath.substring(0, slash)
            }.toSortedMap(compareBy { if (it.isEmpty()) "" else it }) // root group ("") first

    LazyColumn(modifier = modifier.fillMaxSize()) {
        groups.forEach { (group, docs) ->
            if (group.isNotEmpty()) {
                item(key = "group:$group") {
                    Text(
                        text = group,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            items(docs, key = { it.relativePath }) { doc ->
                DocumentRow(doc = doc, isSelected = doc == selected, onClick = { onSelect(doc) })
            }
        }
    }
}

@Composable
private fun DocumentRow(
    doc: OkfDocument,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(bg)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        TypeBadge(doc.type)
        Text(
            text = doc.file.name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** Small text badge indicating whether a document carries a diagram, or is prose/collection. */
@Composable
private fun RowScope.TypeBadge(type: OkfType?) {
    val (glyph, color) =
        when {
            type == null -> "?" to Color.Gray
            type.requiresKumlBlock -> "◆" to Color(0xFF1565C0)
            else -> "▤" to Color(0xFF757575)
        }
    Text(text = glyph, fontSize = 11.sp, color = color)
}
