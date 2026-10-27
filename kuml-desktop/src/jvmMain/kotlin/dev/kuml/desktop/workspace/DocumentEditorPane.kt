package dev.kuml.desktop.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
                // The Markdown renderer lays its blocks out in a plain Column that does not scroll
                // by itself, so anything past the viewport was simply cut off. Scroll state is
                // keyed by document so a newly selected document starts at the top.
                val scrollState = rememberScrollState()
                LaunchedEffect(doc.relativePath) { scrollState.scrollTo(0) }
                Row(modifier = Modifier.weight(1f).fillMaxSize()) {
                    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                        Markdown(
                            content = buffer,
                            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scrollState),
                        )
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
                        modifier = Modifier.fillMaxHeight().padding(horizontal = 2.dp),
                    )
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
 * Document header: row 1 holds the `type:`/`title:` projection fields, which are shared by both
 * views; row 2 holds the Read/Edit [TabRow] (two views of the same document, so tabs rather than
 * push buttons) plus the dirty indicator and Save, directly above the content the tabs switch. Both fields are outlined text fields with an in-border label, so they share
 * one height and centre on the same line — the former label-above dropdown was taller than the
 * title field and pushed the controls out of vertical alignment.
 *
 * The `type:` control is a plain dropdown ([DropdownMenu]), not an
 * `ExposedDropdownMenuBox` — verify in the running app (`./gradlew :kuml-desktop:run`) that
 * it isn't visually clipped by the editor's `SwingPanel` while [editing] is `true` (Compose
 * popups are lightweight and heavyweight AWT components always paint on top of them). If it
 * is, fall back to a picker in its own window (see `WindowAlertDialog`) instead of chasing
 * `compose.interop.blending`.
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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TypeDropdown(rawType = rawType, onTypeChange = onTypeChange, strings = strings, modifier = Modifier.width(200.dp))
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text(strings.docEditTitle, fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReadEditTabs(editing = editing, onToggleEditing = onToggleEditing, strings = strings)
            Spacer(modifier = Modifier.weight(1f))
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
}

@Composable
private fun ReadEditTabs(
    editing: Boolean,
    onToggleEditing: (Boolean) -> Unit,
    strings: Strings,
) {
    TabRow(
        selectedTabIndex = if (editing) 1 else 0,
        containerColor = Color.Transparent,
        modifier = Modifier.width(200.dp),
    ) {
        Tab(
            selected = !editing,
            onClick = { onToggleEditing(false) },
            text = { Text(text = strings.docEditRead, fontSize = 13.sp) },
        )
        Tab(
            selected = editing,
            onClick = { onToggleEditing(true) },
            text = { Text(text = strings.docEditEdit, fontSize = 13.sp) },
        )
    }
}

@Composable
private fun TypeDropdown(
    rawType: String?,
    onTypeChange: (String) -> Unit,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val isKnown = OkfType.fromId(rawType) != null
    val label = rawType ?: "—"
    val shown = if (isKnown || rawType == null) label else "$label (${strings.docEditCustomType})"
    Box(modifier = modifier) {
        // Read-only outlined field = same height/shape as the title field; a transparent overlay
        // makes the whole field open the menu (a disabled field would swallow the click itself).
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(strings.docEditType, fontSize = 11.sp) },
            trailingIcon = { DropdownArrow() },
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
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

/** Small down-pointing triangle, drawn: the "▾" glyph is missing from the default UI font. */
@Composable
private fun DropdownArrow() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(10.dp, 6.dp)) {
        val path =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
                close()
            }
        drawPath(path, color)
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
