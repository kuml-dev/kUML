package dev.kuml.widget.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.runtime.TraceEntry

/**
 * Trace scrubber composable.
 *
 * Shows a [Slider] to scrub through the trace timeline and a [LazyColumn]
 * listing each [TraceEntry] with its type and relevant fields.
 *
 * @param state the [BehaviourWidgetState] to scrub.
 * @param modifier optional [Modifier].
 */
@Composable
internal fun TraceScrubberWidget(
    state: BehaviourWidgetState,
    modifier: Modifier = Modifier,
) {
    val traceSize = state.trace.size
    val position = state.tracePosition

    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            "Trace  ($position / $traceSize)",
            fontSize = 13.sp,
        )

        if (traceSize > 0) {
            Slider(
                value = position.toFloat(),
                onValueChange = { state.scrubTo(it.toInt()) },
                valueRange = 0f..traceSize.toFloat(),
                steps = if (traceSize > 1) traceSize - 1 else 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            items(state.trace) { entry ->
                val (label, color) = traceEntryLabel(entry)
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = color,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

private fun traceEntryLabel(entry: TraceEntry): Pair<String, Color> =
    when (entry) {
        is TraceEntry.StateEntered ->
            "[${entry.seqNo}] Entered: ${entry.vertexId}" to Color(0xFF1B7F4C)
        is TraceEntry.StateExited ->
            "[${entry.seqNo}] Exited: ${entry.vertexId}" to Color(0xFFB04020)
        is TraceEntry.TransitionFired ->
            "[${entry.seqNo}] Transition: ${entry.fromVertexId} → ${entry.toVertexId}" to Color.DarkGray
        is TraceEntry.EventReceived ->
            "[${entry.seqNo}] Event: ${entry.eventName}" to Color.Blue
        is TraceEntry.GuardEvaluated ->
            "[${entry.seqNo}] Guard(${entry.guard}): ${entry.result}" to Color.Gray
        is TraceEntry.ActionInvoked ->
            "[${entry.seqNo}] Action(${entry.phase}): ${entry.action}" to Color.Gray
        is TraceEntry.Stayed ->
            "[${entry.seqNo}] Stayed: ${entry.reason}" to Color.Gray
        is TraceEntry.Terminated ->
            "[${entry.seqNo}] Terminated at: ${entry.finalVertexId}" to Color.Magenta
        else ->
            "[${entry.seqNo}] ${entry::class.simpleName}" to Color.Gray
    }
