package dev.kuml.desktop.simulation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.IconTooltipButton
import dev.kuml.desktop.ui.KumlIcons
import dev.kuml.desktop.ui.tooltipStart
import dev.kuml.desktop.workspace.WindowAlertDialog
import dev.kuml.widget.compose.GuardEditAction
import dev.kuml.widget.compose.GuardEditorField
import dev.kuml.widget.compose.GuardEditorLabels
import dev.kuml.widget.compose.PatchOutcome
import dev.kuml.widget.compose.PendingGuardEdit
import dev.kuml.widget.compose.resolveGuardEditAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Quiet period (ms) after the last change before guards are evaluated again. */
internal const val INSPECTOR_DEBOUNCE_MS: Long = 150L

private val AMBER = Color(0xFFB26A00)
private val GREEN = Color(0xFF228822)
private val RED = Color(0xFFCC0000)

/** What the UI does after the user saved a guard, derived purely from the edit gate (testable without a window). */
internal sealed interface GuardSaveStep {
    /** Apply right away. */
    data object ApplyNow : GuardSaveStep

    /** Ask for confirmation first (protected transition). */
    data object AskConfirmation : GuardSaveStep

    /** The policy forbids the edit; do nothing. */
    data object Ignore : GuardSaveStep
}

internal fun guardSaveStep(action: GuardEditAction): GuardSaveStep =
    when (action) {
        GuardEditAction.Apply -> GuardSaveStep.ApplyNow
        GuardEditAction.Confirm -> GuardSaveStep.AskConfirmation
        GuardEditAction.Denied -> GuardSaveStep.Ignore
    }

/**
 * Inspector section: the transitions leaving the active states with their guard results, the
 * events that are NOT enabled and why, and temporary guard editing.
 *
 * Guards are evaluated off the UI thread against a frozen copy (never the running instance),
 * through the session's sandboxed evaluator. Edits are session-only; nothing is written to the
 * script.
 */
@Composable
internal fun SimulationInspectorPane(
    session: SimulationSession,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    val widgetState = session.widgetState
    val model = widgetState.model
    val active = session.activeVertexIds()
    val variables = session.variablesAtPosition()
    val payloadDraft = session.payloadDraft
    val scrubbing = widgetState.isScrubbing
    val rows = remember(model, active) { inspectorRows(model = model, initialModel = widgetState.initialModel, activeVertexIds = active) }
    val names = remember(model) { flattenVertices(model).associate { it.id to it.name.ifBlank { it.id } } }

    var evaluations by remember { mutableStateOf<Map<String, GuardEvaluation>>(emptyMap()) }
    var pending by remember { mutableStateOf(true) }
    LaunchedEffect(model, active, variables, payloadDraft, rows) {
        pending = true
        delay(INSPECTOR_DEBOUNCE_MS)
        val payload = session.draftPayload()
        evaluations =
            withContext(Dispatchers.Default) {
                evaluateInspectorGuards(
                    evaluator = session.inspectorEvaluator,
                    model = model,
                    rows = rows,
                    activeVertexIds = active,
                    variables = variables,
                    payload = payload,
                )
            }
        pending = false
    }
    val current: Map<String, GuardEvaluation> = if (pending) emptyMap() else evaluations

    var editingId by remember { mutableStateOf<String?>(null) }
    var pendingConfirm by remember { mutableStateOf<PendingGuardEdit?>(null) }
    var rejection by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scrubbing) {
        if (scrubbing) {
            editingId = null
            pendingConfirm = null
        }
    }

    fun apply(
        transitionId: String,
        newOcl: String,
        confirmed: Boolean,
    ) {
        when (val outcome = session.applyGuard(transitionId = transitionId, newOcl = newOcl, confirmed = confirmed)) {
            PatchOutcome.Applied -> {
                editingId = null
                rejection = null
            }
            PatchOutcome.NeedsConfirmation -> pendingConfirm = PendingGuardEdit(transitionId = transitionId, newOcl = newOcl)
            is PatchOutcome.Rejected -> rejection = outcome.message
        }
    }

    val whyNot =
        remember(model, active, current, variables, payloadDraft, session.isTerminated) {
            if (session.isTerminated || active.isEmpty()) {
                emptyList()
            } else {
                whyNotExplanations(
                    model = model,
                    activeVertexIds = active,
                    eventNames = session.eventNames,
                    evaluations = current,
                    variables = variables,
                    payload = session.draftPayload(),
                )
            }
        }

    Column(modifier = modifier.testTag(SimTestTags.INSPECTOR)) {
        val editedCount = session.editedGuardCount
        if (editedCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().testTag(SimTestTags.TEMP_BAND),
            ) {
                Text(
                    text = strings.simTemporaryBand.format(editedCount),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                item { Text(text = strings.simNoTransitionsHere, fontSize = 11.sp, modifier = Modifier.padding(8.dp)) }
            }
            val grouped = rows.groupBy { it.ownerVertexId }
            for ((owner, ownerRows) in grouped) {
                item(key = "header-$owner") {
                    val name = names[owner] ?: owner
                    Text(
                        text = if (ownerRows.first().inheritedFrom != null) strings.simInheritedFrom.format(name) else name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp),
                    )
                }
                items(ownerRows, key = { it.transition.id }) { row ->
                    InspectorRowView(
                        row = row,
                        names = names,
                        evaluation = current[row.transition.id],
                        selected = session.selectedTransitionId == row.transition.id,
                        editing = editingId == row.transition.id,
                        scrubbing = scrubbing,
                        rejection = rejection.takeIf { editingId == row.transition.id },
                        strings = strings,
                        onSelect = {
                            session.selectedTransitionId =
                                if (session.selectedTransitionId == row.transition.id) null else row.transition.id
                        },
                        onEdit = {
                            rejection = null
                            editingId = row.transition.id
                        },
                        onCancelEdit = {
                            editingId = null
                            rejection = null
                        },
                        onSave = { newOcl ->
                            val action = resolveGuardEditAction(policy = widgetState.editPolicy, transition = row.transition)
                            when (guardSaveStep(action)) {
                                GuardSaveStep.ApplyNow -> apply(transitionId = row.transition.id, newOcl = newOcl, confirmed = false)
                                GuardSaveStep.AskConfirmation ->
                                    pendingConfirm = PendingGuardEdit(transitionId = row.transition.id, newOcl = newOcl)
                                GuardSaveStep.Ignore -> Unit
                            }
                        },
                        onRevert = { session.revertGuard(row.transition.id) },
                    )
                }
            }
            if (whyNot.isNotEmpty()) {
                item(key = "not-enabled") { NotEnabledSection(whyNot = whyNot, names = names, strings = strings) }
            }
            item(key = "footnote") {
                val note =
                    when {
                        scrubbing -> strings.simScrubbingFootnote
                        payloadDraft.isNotBlank() -> strings.simDraftPayloadFootnote
                        else -> null
                    }
                if (note != null) {
                    Text(
                        text = note,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }

    pendingConfirm?.let { toConfirm ->
        ProtectedGuardConfirmDialog(
            transitionLabel = toConfirm.transitionId,
            strings = strings,
            onConfirm = {
                apply(transitionId = toConfirm.transitionId, newOcl = toConfirm.newOcl, confirmed = true)
                pendingConfirm = null
            },
            onCancel = { pendingConfirm = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InspectorRowView(
    row: InspectorRow,
    names: Map<String, String>,
    evaluation: GuardEvaluation?,
    selected: Boolean,
    editing: Boolean,
    scrubbing: Boolean,
    rejection: String?,
    strings: Strings,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: (String) -> Unit,
    onRevert: () -> Unit,
) {
    val t = row.transition
    val hasGuard = !t.guard.isNullOrBlank()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .testTag(SimTestTags.inspectorRow(t.id))
                .clickable(onClick = onSelect)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (statusText, statusColor) = guardStatus(evaluation = evaluation, hasGuard = hasGuard, strings = strings)
            Text(text = statusText, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconTooltipButton(
                icon = KumlIcons.EditGuard,
                description = if (scrubbing) strings.simEditGuardReturnLive else strings.simEditGuard,
                enabled = !scrubbing,
                onClick = onEdit,
                modifier = Modifier.testTag(SimTestTags.editGuard(t.id)),
                tooltipPlacement = tooltipStart(),
            )
            if (row.edited) {
                IconTooltipButton(
                    icon = KumlIcons.RevertGuard,
                    description = strings.simRevertGuard,
                    enabled = !scrubbing,
                    onClick = onRevert,
                    modifier = Modifier.testTag(SimTestTags.revertGuard(t.id)),
                    tooltipPlacement = tooltipStart(),
                )
            }
        }
        Text(
            text =
                "${names[t.sourceId] ?: t.sourceId} → ${names[t.targetId] ?: t.targetId}  " +
                    (row.triggerName ?: strings.simCompletionTrigger),
            fontSize = 11.sp,
        )
        Text(
            text = t.guard?.takeIf { it.isNotBlank() } ?: strings.simNoGuard,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.edited) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = AMBER.copy(alpha = 0.18f)) {
                    Text(
                        text = strings.simGuardEdited,
                        fontSize = 10.sp,
                        color = AMBER,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                Text(
                    text = strings.simOriginalGuard.format(row.originalGuard ?: strings.simNoGuard),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (editing) {
            GuardEditorField(
                initial = t.guard.orEmpty(),
                onSave = onSave,
                onCancel = onCancelEdit,
                labels =
                    GuardEditorLabels(
                        field = strings.simGuardField,
                        save = strings.simApply,
                        cancel = strings.simCancel,
                        tooLong = strings.simGuardTooLong,
                    ),
            )
            if (rejection != null) {
                Text(
                    text = rejection,
                    fontSize = 11.sp,
                    color = RED,
                    modifier = Modifier.padding(horizontal = 8.dp).testTag(SimTestTags.GUARD_EDIT_ERROR),
                )
            }
        }
    }
    HorizontalDivider()
}

/** Status of one guard as glyph plus text (never colour alone). */
internal fun guardStatus(
    evaluation: GuardEvaluation?,
    hasGuard: Boolean,
    strings: Strings,
): Pair<String, Color> =
    when {
        !hasGuard -> "– ${strings.simNoGuard}" to Color.Gray
        else ->
            when (evaluation) {
                GuardEvaluation.True -> "✓ ${strings.simGuardTrue}" to GREEN
                GuardEvaluation.False -> "✗ ${strings.simGuardFalse}" to Color.Gray
                is GuardEvaluation.Error -> "⚠ ${strings.simGuardError.format(evaluation.message)}" to RED
                GuardEvaluation.Pending, null -> "… ${strings.simEvaluating}" to Color.Gray
            }
    }

@Composable
private fun NotEnabledSection(
    whyNot: List<WhyNot>,
    names: Map<String, String>,
    strings: Strings,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp).testTag(SimTestTags.NOT_ENABLED)) {
        Text(text = strings.simNotEnabledTitle, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        for (item in whyNot) {
            Text(text = item.eventName, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            when (item) {
                is WhyNot.NoTransition ->
                    Text(
                        text = strings.simWhyNoTransition.format(item.stateIds.joinToString(", ") { names[it] ?: it }),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                is WhyNot.GuardError ->
                    Text(text = strings.simWhyGuardError.format(item.message), fontSize = 10.sp, color = RED)
                is WhyNot.GuardFalse -> {
                    Text(text = strings.simWhyGuardFalse, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (candidate in item.candidates) {
                        Text(text = candidate.expression, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        for (read in candidate.reads) {
                            val prefix = if (read.ref.root == VarRoot.VARS) "vars" else "event"
                            val value = read.value?.let { formatVariableValue(value = it) } ?: strings.simNotSet
                            Text(
                                text = "  $prefix.${read.ref.name} = $value",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Confirmation for editing a protected transition, in its own window so the heavyweight preview cannot hide it. */
@Composable
internal fun ProtectedGuardConfirmDialog(
    transitionLabel: String,
    strings: Strings,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    WindowAlertDialog(
        title = strings.simProtectedConfirmTitle,
        onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = onConfirm) { Text(strings.simConfirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(strings.simCancel) } },
    ) {
        Text(text = strings.simProtectedConfirmBody.format(transitionLabel))
    }
}
