package dev.kuml.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import dev.kuml.desktop.editor.EditorActions
import dev.kuml.desktop.editor.SyntaxTextEditor
import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.FrontmatterParser
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfType
import dev.kuml.workspace.ResolvedLink
import org.fife.ui.rsyntaxtextarea.SyntaxConstants

/**
 * The Knowledge Workspace document pane, editable in place (V-next; replaces the read-only
 * `MarkdownDocPane`).
 *
 * The read view renders [buffer] (never re-reads [OkfDocument.file] from disk) — the buffer
 * is loaded once by [WorkspaceState.select] and is the single source of truth for BOTH the
 * rendered prose and the raw editor, so a just-completed save is reflected immediately
 * without a second read path that could observe a stale file (see `WorkspaceState`'s KDoc).
 *
 * @param findings ALL current [OkfFinding]s for the buffer, shown as a persistent banner
 *  above the content area — never an overlay (the content area may host a `SwingPanel`,
 *  which is heavyweight and always paints on top of Compose overlays; see
 *  `WorkspacePreviewPane`'s and `FindBar`'s KDoc for the same rule).
 * @param blockingFindings the subset of [findings] that would refuse a save — rendered in
 *  the banner's blocking (red) section; there is deliberately no "save anyway" button (see
 *  `OkfSaveGate`'s KDoc).
 */
@Composable
fun DocumentEditorPane(
    doc: OkfDocument?,
    buffer: String?,
    editing: Boolean,
    isDirty: Boolean,
    findings: List<OkfFinding>,
    blockingFindings: List<OkfFinding>,
    onToggleEditing: (Boolean) -> Unit,
    onBufferChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    linkHandler: WorkspaceLinkHandler,
    strings: Strings,
    onEditorReady: (EditorActions?) -> Unit,
    modifier: Modifier = Modifier,
    backlinks: List<ResolvedLink> = emptyList(),
    onNavigateBacklink: (OkfDocument) -> Unit = {},
) {
    if (doc == null || buffer == null) return

    // Projection, not a second source of truth (design review): type/title are re-derived
    // from the CURRENT buffer on every recomposition, so the header reflects an in-progress
    // edit (including one made by another control on this same screen) immediately.
    val frontmatter = remember(buffer) { FrontmatterParser.parse(buffer) }

    val uriHandler =
        remember(linkHandler) {
            object : UriHandler {
                override fun openUri(uri: String) = linkHandler.onLink(uri)
            }
        }

    Column(modifier = modifier) {
        DocumentHeaderBar(
            editing = editing,
            isDirty = isDirty,
            rawType = frontmatter.type,
            title = frontmatter.title.orEmpty(),
            onToggleEditing = onToggleEditing,
            onTypeChange = onTypeChange,
            onTitleChange = onTitleChange,
            onSave = onSave,
            strings = strings,
        )
        ValidationBanner(findings = findings, blockingFindings = blockingFindings)
        HorizontalDivider()

        if (editing) {
            SyntaxTextEditor(
                text = buffer,
                onTextChange = onBufferChange,
                syntaxStyle = SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                modifier = Modifier.weight(1f).fillMaxSize(),
                testTag = "kuml-doc-editor",
                onEditorReady = onEditorReady,
                onEscape = { if (!isDirty) onToggleEditing(false) },
                // Bugfix (review finding) — identifies the CURRENT document, so a title/type
                // splice into the SAME buffer (below, via `onTitleChange`/`onTypeChange`) no
                // longer wipes the undo history and resets the caret to 0; only an actual
                // document switch does. See `SyntaxTextEditor`'s `documentKey` KDoc.
                documentKey = doc.relativePath,
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .pointerInput(doc.relativePath) {
                            detectTapGestures(onDoubleTap = { onToggleEditing(true) })
                        },
            ) {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    Markdown(content = buffer, modifier = Modifier.weight(1f).fillMaxSize())
                }
            }
        }

        if (backlinks.isNotEmpty()) {
            HorizontalDivider()
            BacklinksBar(backlinks = backlinks, label = strings.workspaceBacklinksLabel, onNavigate = onNavigateBacklink)
        }
    }
}

/**
 * Read/Edit segmented toggle, `type:`/`title:` projection fields, and the dirty indicator.
 *
 * The `type:` control is a plain dropdown ([DropdownMenu]), not an
 * `ExposedDropdownMenuBox` — verify in the running app (`./gradlew :kuml-desktop:run`) that
 * it isn't visually clipped by the editor's `SwingPanel` while [editing] is `true` (Compose
 * popups are lightweight and heavyweight AWT components always paint on top of them). If it
 * is, fall back to a small `AlertDialog`-based picker (its own top-level window, always on
 * top) instead of chasing `compose.interop.blending`.
 */
@Composable
private fun DocumentHeaderBar(
    editing: Boolean,
    isDirty: Boolean,
    rawType: String?,
    title: String,
    onToggleEditing: (Boolean) -> Unit,
    onTypeChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    strings: Strings,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ReadEditToggle(editing = editing, onToggleEditing = onToggleEditing, strings = strings)
        TypeDropdown(rawType = rawType, onTypeChange = onTypeChange, strings = strings)
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text(strings.docEditTitle, fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (isDirty) {
            Text(
                text = "• ${strings.docEditDirty}",
                color = Color(0xFFCC7A00),
                fontSize = 11.sp,
            )
            TextButton(onClick = onSave) { Text(strings.menuFileSave) }
        }
    }
}

@Composable
private fun ReadEditToggle(
    editing: Boolean,
    onToggleEditing: (Boolean) -> Unit,
    strings: Strings,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SegmentButton(label = strings.docEditRead, selected = !editing, onClick = { onToggleEditing(false) })
        SegmentButton(label = strings.docEditEdit, selected = editing, onClick = { onToggleEditing(true) })
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(text = label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun TypeDropdown(
    rawType: String?,
    onTypeChange: (String) -> Unit,
    strings: Strings,
) {
    var expanded by remember { mutableStateOf(false) }
    val isKnown = OkfType.fromId(rawType) != null
    val label = rawType ?: "—"
    // Bugfix (review finding) — `strings.docEditType` existed as a translated string but was
    // never actually read anywhere, leaving this control the only one in the header bar
    // (unlike the title field's `OutlinedTextField.label`) without a label at all.
    Column {
        Text(
            text = strings.docEditType,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(text = if (isKnown || rawType == null) label else "$label (${strings.docEditCustomType})", fontSize = 12.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                OkfType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.id) },
                        onClick = {
                            onTypeChange(type.id)
                            expanded = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(strings.docEditCustomType) },
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun ValidationBanner(
    findings: List<OkfFinding>,
    blockingFindings: List<OkfFinding>,
) {
    if (findings.isEmpty()) return
    val warnings = findings.filter { it !in blockingFindings }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (blockingFindings.isNotEmpty()) {
            FindingList(
                findings = blockingFindings,
                background = MaterialTheme.colorScheme.errorContainer,
                foreground = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        if (warnings.isNotEmpty()) {
            FindingList(
                findings = warnings,
                background = Color(0xFFFFF3CD),
                foreground = Color(0xFF7A5900),
            )
        }
    }
}

@Composable
private fun FindingList(
    findings: List<OkfFinding>,
    background: Color,
    foreground: Color,
) {
    Column(modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 10.dp, vertical = 4.dp)) {
        findings.forEach { finding ->
            val text =
                buildString {
                    append(finding.code)
                    append(" — ")
                    append(finding.message)
                    finding.suggestion?.let { append(" ($it)") }
                }
            Text(
                text = text,
                color = foreground,
                fontSize = 11.sp,
            )
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
