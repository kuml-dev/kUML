package dev.kuml.desktop.simulation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.tooltipBelow
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SimulationTracePane(
    session: SimulationSession,
    strings: Strings,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    val trace = session.widgetState.trace
    val tracePosition = session.widgetState.tracePosition
    val listState = rememberLazyListState()

    LaunchedEffect(trace.size) {
        if (trace.isNotEmpty()) listState.animateScrollToItem(trace.lastIndex)
    }

    Column(modifier = modifier) {
        if (showTitle) {
            Text(
                text = strings.simTraceTitle,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.padding(8.dp),
            )
        }
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
                val row: @Composable () -> Unit = {
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
                val payload = (entry as? TraceEntry.EventReceived)?.payload?.takeIf { it.isNotEmpty() }
                if (payload == null) {
                    row()
                } else {
                    // The tooltip anchors to the full-width row and sits below it, inside this
                    // Compose-only column (nothing heavyweight underneath).
                    TooltipArea(
                        tooltip = {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.inverseSurface,
                                modifier = Modifier.padding(4.dp).widthIn(max = 320.dp),
                            ) {
                                Text(
                                    text = payload.toString().take(PAYLOAD_TOOLTIP_MAX_CHARS),
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        },
                        tooltipPlacement = tooltipBelow(),
                    ) { row() }
                }
            }
        }
    }
}

/** Longest payload text (characters) a trace tooltip shows. */
internal const val PAYLOAD_TOOLTIP_MAX_CHARS: Int = 2048

/** Longest payload excerpt (characters) shown inline in a trace row. */
internal const val PAYLOAD_INLINE_MAX_CHARS: Int = 80

/** Localization-free structural summary of one [TraceEntry] — good enough for a compact trace row. */
internal fun traceEntryLabel(entry: TraceEntry): String =
    when (entry) {
        is TraceEntry.EventReceived -> {
            val payload = entry.payload
            if (payload.isEmpty()) {
                "→ ${entry.eventName}"
            } else {
                val text = payload.toString()
                "→ ${entry.eventName} " + if (text.length <= PAYLOAD_INLINE_MAX_CHARS) text else text.take(PAYLOAD_INLINE_MAX_CHARS) + "…"
            }
        }
        is TraceEntry.StateEntered -> "enter ${entry.vertexId}"
        is TraceEntry.StateExited -> "exit ${entry.vertexId}"
        is TraceEntry.TransitionFired -> "${entry.fromVertexId} → ${entry.toVertexId}"
        is TraceEntry.GuardEvaluated -> "guard(${entry.transitionId}) = ${entry.result}"
        is TraceEntry.GuardWarning -> "⚠ guard(${entry.transitionId}): ${entry.message}"
        is TraceEntry.ActionInvoked -> "${entry.phase} / ${entry.action}"
        is TraceEntry.ActionError -> "⚠ error: ${entry.message}"
        is TraceEntry.Stayed -> "stayed: ${entry.reason}"
        is TraceEntry.Terminated -> "terminated at ${entry.finalVertexId}"
        is TraceEntry.TimerFired -> "timer ${entry.transitionId}"
        is TraceEntry.ClockStarted -> "clock ${entry.startTime}"
        is TraceEntry.GuardNotEvaluable -> "⚠ prose guard (${entry.transitionId}): not evaluable"
        is TraceEntry.JoinWaiting -> "join ${entry.joinId} waits for ${entry.missing.joinToString()}"
        is TraceEntry.Preempted -> "${entry.transitionId} pre-empted by ${entry.byTransitionId}"
        is TraceEntry.NotFired -> "not fired: ${entry.reason}"
        else -> entry::class.simpleName ?: "?"
    }
