package dev.kuml.desktop.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.runtime.TraceEntry

/**
 * V3.x — Live-Simulation von Zustandsautomaten im Editor.
 *
 * Fixed-width trace column to the right of [dev.kuml.desktop.preview.PreviewPane], newest entry
 * at the bottom (auto-scrolls there as the trace grows), clicking a row scrubs
 * [SimulationSession] to that point.
 *
 * Not shared with [dev.kuml.widget.compose.TraceScrubberWidget] — that widget is `internal` to a
 * different module and hard-codes its labels in English; this pane needs the localized
 * [Strings] this app already carries for everything else.
 */
@Composable
internal fun SimulationTracePane(
    session: SimulationSession,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    val trace = session.widgetState.trace
    val tracePosition = session.widgetState.tracePosition
    val listState = rememberLazyListState()

    LaunchedEffect(trace.size) {
        if (trace.isNotEmpty()) listState.animateScrollToItem(trace.lastIndex)
    }

    Column(modifier = modifier) {
        Text(
            text = strings.simTraceTitle,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.padding(8.dp),
        )
        if (session.widgetState.isScrubbing) {
            Text(
                text = strings.simScrubbing.format(tracePosition, trace.size),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(trace) { index, entry ->
                val isLive = index < tracePosition
                val isWarning = entry is TraceEntry.GuardWarning || entry is TraceEntry.ActionError
                Text(
                    text = traceEntryLabel(entry),
                    fontSize = 11.sp,
                    color =
                        when {
                            isWarning -> MaterialTheme.colorScheme.error
                            isLive -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { session.scrubTo(index + 1) }
                            .background(
                                if (index + 1 == tracePosition) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                            ).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** Localization-free structural summary of one [TraceEntry] — good enough for a compact trace row. */
private fun traceEntryLabel(entry: TraceEntry): String =
    when (entry) {
        is TraceEntry.EventReceived -> "→ ${entry.eventName}"
        is TraceEntry.StateEntered -> "enter ${entry.vertexId}"
        is TraceEntry.StateExited -> "exit ${entry.vertexId}"
        is TraceEntry.TransitionFired -> "${entry.fromVertexId} → ${entry.toVertexId}"
        is TraceEntry.GuardEvaluated -> "guard(${entry.transitionId}) = ${entry.result}"
        is TraceEntry.GuardWarning -> "⚠ guard(${entry.transitionId}): ${entry.message}"
        is TraceEntry.ActionInvoked -> "${entry.phase} / ${entry.action}"
        is TraceEntry.ActionError -> "⚠ error: ${entry.message}"
        is TraceEntry.Stayed -> "stayed: ${entry.reason}"
        is TraceEntry.Terminated -> "terminated at ${entry.finalVertexId}"
        else -> entry::class.simpleName ?: "?"
    }
