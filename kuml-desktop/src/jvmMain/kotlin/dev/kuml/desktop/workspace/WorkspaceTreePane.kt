package dev.kuml.desktop.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfType

/**
 * The tree's live, per-selection override of a document's TYPE BADGE (bugfix, review
 * finding). A bare `OkfType?` cannot distinguish two different `null` meanings: "no live
 * buffer to project from, fall back to the document's last-saved type" versus "the live
 * buffer HAS a `type:` value, and it doesn't match any known [OkfType]" — both collapsed onto
 * `null`, so [WorkspaceTreePane] fell back to [OkfDocument.type] (the stale, last-SAVED
 * badge) in the second case too. Concretely: a document saved with `type: Concept`, then
 * edited in the raw editor to `type: Quatsch` — the editor's own header correctly shows
 * "Quatsch (Custom)" and the OKF-W-002 banner fires, but the tree kept showing the Concept
 * badge, which is exactly what this pane's own KDoc says it must never do. [Override]'s
 * payload is itself nullable — `Override(null)` means "the buffer's `type:` is unrecognised",
 * which [WorkspaceTreePane] renders as the `?` badge, same as an [OkfDocument] with no type at
 * all.
 */
sealed interface PendingType {
    /** Nothing selected, or no buffer loaded yet for it — display each row's saved type. */
    data object NoOverride : PendingType

    /**
     * The selected row's badge should reflect the live buffer's `type:` instead of the
     * document's last-saved one. [type] is `null` when the buffer's `type:` value doesn't
     * resolve to any known [OkfType] (including when there is no `type:` field at all).
     */
    data class Override(
        val type: OkfType?,
    ) : PendingType
}

/**
 * Pure decision for which [OkfType] a tree row's badge should display (bugfix, review
 * finding — extracted out of the inline `if` in [WorkspaceTreePane]'s `items` block,
 * analogous to [dev.kuml.desktop.io.unsavedSaveTargetFor], so the three-way [PendingType]
 * decision is unit-testable without a Compose test harness — `kuml-desktop` deliberately has
 * none, see [dev.kuml.desktop.editor.SyntaxTextEditor]'s KDoc). Only the SELECTED row's badge
 * is ever overridden; every other row, and the selected row itself when [pending] is
 * [PendingType.NoOverride], falls back to [docType] — the document's last-saved type.
 *
 * Kept covering [PendingType.Override]'s `null` payload explicitly (rather than an
 * `Override::type ?: docType` shortcut) is the whole point of the [PendingType] type — see
 * its KDoc for the bug an `Override(null) == fall back to docType` collapse would silently
 * reintroduce (an unrecognised live `type:` value showing the stale saved badge instead of
 * `?`).
 */
internal fun badgeTypeFor(
    docType: OkfType?,
    isSelected: Boolean,
    pending: PendingType,
): OkfType? =
    if (isSelected && pending is PendingType.Override) {
        pending.type
    } else {
        docType
    }

/**
 * Document tree for a Knowledge-mode workspace (V3.6.4), grouped by top-level
 * folder (`articles/`, `concepts/`, `models/`, `glossary/`, or the workspace
 * root). Clicking a document invokes [onSelect].
 *
 * @param dirtyPath [OkfDocument.relativePath] of the document with unsaved changes, or
 *  `null` — marked with a trailing `•` (V-next, editable-workspace welle). A text glyph,
 *  not only a color change (a11y — see [TypeBadge]'s KDoc for the same rule).
 * @param pendingTypeForSelected When [PendingType.Override], overrides the TYPE BADGE
 *  glyph/color for the currently `selected` row only, projected live from the in-memory edit
 *  buffer's frontmatter — so the badge reacts the instant the `type:` dropdown (or the raw
 *  `type:` line) changes, not only after a save re-parses the file (Susan Kare's point in the
 *  design review: the tree must never show a badge that contradicts what the editor is
 *  currently showing). See [PendingType]'s KDoc for why this can't just be a plain `OkfType?`.
 */
@Composable
fun WorkspaceTreePane(
    documents: List<OkfDocument>,
    selected: OkfDocument?,
    onSelect: (OkfDocument) -> Unit,
    strings: Strings,
    modifier: Modifier = Modifier,
    dirtyPath: String? = null,
    pendingTypeForSelected: PendingType = PendingType.NoOverride,
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
                // Compared by `relativePath`, not data-class equality (bugfix, review
                // finding): `WorkspaceState.applySaved` only re-points `selected` at a freshly
                // reparsed instance when its own `token == selectionToken` guard passes — if a
                // concurrent `select()` call's token bump lands INSIDE an in-flight `save()`,
                // that guard fails and `selected` keeps referencing the pre-save instance, which
                // is now data-class-UNEQUAL to its own (edited) entry in `documents` (differing
                // frontmatter/blocks/links). `doc == selected` would then be false for every row
                // — no highlight, and `badgeTypeFor`'s `isSelected` gate below loses the live
                // type badge too — until the next click. `relativePath` is the document set's
                // stable primary key (invariant per this file's/[WorkspaceState]'s KDoc), so
                // comparing on it survives any such instance-identity drift.
                val isSelected = doc.relativePath == selected?.relativePath
                DocumentRow(
                    doc = doc,
                    isSelected = isSelected,
                    isDirty = doc.relativePath == dirtyPath,
                    displayType = badgeTypeFor(docType = doc.type, isSelected = isSelected, pending = pendingTypeForSelected),
                    onClick = { onSelect(doc) },
                    strings = strings,
                )
            }
        }
    }
}

@Composable
private fun DocumentRow(
    doc: OkfDocument,
    isSelected: Boolean,
    isDirty: Boolean,
    displayType: OkfType?,
    onClick: () -> Unit,
    strings: Strings,
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
        TypeBadge(type = displayType, strings = strings)
        Text(
            text = if (isDirty) "${doc.file.name} •" else doc.file.name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * Small text badge indicating whether a document carries a diagram, or is
 * prose/collection. The glyph alone (◆/▤/?) isn't self-explanatory and, per
 * the design-team review, must not rely on color alone to carry meaning
 * (a11y) — [TooltipArea] surfaces the same distinction as text on hover.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.TypeBadge(
    type: OkfType?,
    strings: Strings,
) {
    val (glyph, color, description) =
        when {
            type == null -> Triple("?", Color.Gray, strings.workspaceBadgeUnknown)
            type.requiresKumlBlock -> Triple("◆", Color(0xFF1565C0), strings.workspaceBadgeDiagram)
            else -> Triple("▤", Color(0xFF757575), strings.workspaceBadgeProse)
        }
    TooltipArea(tooltip = {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier.padding(4.dp),
        ) {
            Text(
                text = description,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }) {
        Text(text = glyph, fontSize = 11.sp, color = color)
    }
}
