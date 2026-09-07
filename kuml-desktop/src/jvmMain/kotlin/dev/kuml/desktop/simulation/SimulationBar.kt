package dev.kuml.desktop.simulation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.AppState
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.IconTooltipButton
import dev.kuml.desktop.ui.KumlIcons
import dev.kuml.desktop.ui.tooltipBelow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * V3.x — Live-Simulation von Zustandsautomaten im Editor.
 *
 * Compose `Row`/`Column` docked ABOVE [dev.kuml.desktop.preview.PreviewPane]'s `SwingPanel` —
 * never an overlay on top of it (heavyweight AWT, see `PreviewPane`'s own KDoc). Shown only
 * while [AppState.simulation] is non-null.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SimulationBar(
    state: AppState,
    session: SimulationSession,
    strings: Strings,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabledEvents = session.enabledEvents()
    val stale = isStale(sessionSource = session.modelSource, currentScript = state.script)
    val terminated = session.isTerminated

    // Auto-advance loop (Spez. E19) — fixed 500 ms tick, no speed control, no wall clock beyond
    // the delay itself: the NEXT tick only fires once the previous send() has updated
    // session.lastResult, so this is purely event-driven, never overlapping steps.
    //
    // Review fix — `stale` is re-derived from `state.script`/`session.modelSource` INSIDE the
    // loop on every tick, not captured from the `stale` val above: this coroutine's body is
    // only entered once per (session, autoAdvancing) pair, so a `val` closed over at that point
    // would keep whatever staleness the script had when auto-advance was armed, never seeing a
    // later edit. Without this, an already-running auto-advance would keep firing events against
    // a model the "veraltet" banner (below) is telling the user is no longer current (Spez. D18).
    LaunchedEffect(session, session.autoAdvancing) {
        while (session.autoAdvancing && isActive) {
            delay(AUTO_ADVANCE_INTERVAL_MS)
            if (!session.autoAdvancing) break
            val decision =
                autoAdvanceDecision(
                    enabledEvents = session.enabledEvents(),
                    lastResult = session.lastResult,
                    terminated = session.isTerminated,
                    stale = isStale(sessionSource = session.modelSource, currentScript = state.script),
                )
            when (decision) {
                is AutoAdvanceDecision.Continue -> session.send(eventName = decision.eventName)
                is AutoAdvanceDecision.Stop -> session.reportAutoStop(decision.reason)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (stale) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = strings.simStaleModel,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            IconTooltipButton(
                icon = KumlIcons.SimStep,
                description = strings.simStep,
                enabled = !stale && !terminated && enabledEvents.size == 1,
                onClick = { enabledEvents.singleOrNull()?.let { session.send(eventName = it) } },
                tooltipPlacement = tooltipBelow(),
            )
            if (session.autoAdvancing) {
                IconTooltipButton(
                    icon = KumlIcons.SimPause,
                    description = strings.simPause,
                    onClick = { session.autoAdvancing = false },
                    tooltipPlacement = tooltipBelow(),
                )
            } else {
                IconTooltipButton(
                    icon = KumlIcons.SimAutoAdvance,
                    description = strings.simAutoAdvance,
                    enabled = !stale && !terminated,
                    onClick = { session.autoAdvancing = true },
                    tooltipPlacement = tooltipBelow(),
                )
            }
            IconTooltipButton(
                icon = KumlIcons.SimReset,
                description = strings.simReset,
                onClick = { session.reset() },
                tooltipPlacement = tooltipBelow(),
            )
            VerticalDivider(modifier = Modifier.padding(horizontal = 2.dp))
            // Takes all remaining width so the close button below naturally lands at the row's
            // trailing edge without a second fillMaxWidth-in-a-Row footgun.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            ) {
                session.eventNames.forEach { name ->
                    val enabled = !stale && !terminated && name in enabledEvents
                    EventButton(
                        name = name,
                        enabled = enabled,
                        disabledHint = strings.simEventNotEnabled,
                        onClick = { session.send(eventName = name) },
                    )
                }
                OutlinedButton(onClick = { expanded = !expanded }, enabled = !stale) {
                    Text(text = strings.simMoreEvents, fontSize = 12.sp)
                }
            }
            IconTooltipButton(
                icon = KumlIcons.Close,
                description = strings.simClose,
                onClick = {
                    session.close()
                    state.simulation = null
                },
                tooltipPlacement = tooltipBelow(),
            )
        }
        if (expanded) {
            // Review fix — also gated on `!terminated`, matching the Step button and every event
            // button above. Previously this was the one send affordance a finished simulation
            // left reachable: sending any freeform event name against a terminated instance
            // still reaches `StateMachineRuntime.step`, which logs a `TraceEntry.Stayed("state
            // machine terminated")` and flips the status line from "Automat beendet" to a
            // confusing "Kein Übergang: state machine terminated" on every click.
            FreeformEventRow(session = session, strings = strings, enabled = !stale && !terminated)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventButton(
    name: String,
    enabled: Boolean,
    disabledHint: String,
    onClick: () -> Unit,
) {
    TooltipArea(
        tooltip = {
            if (!enabled) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.padding(4.dp),
                ) {
                    Text(
                        text = disabledHint,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        },
        tooltipPlacement = tooltipBelow(),
    ) {
        if (enabled) {
            Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text(text = name, fontSize = 12.sp)
            }
        } else {
            OutlinedButton(onClick = {}, enabled = false, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text(text = name, fontSize = 12.sp)
            }
        }
    }
}

/** "Weitere…" expandable freetext event sender — mirrors `ControlPanel`'s existing behaviour. */
@Composable
private fun FreeformEventRow(
    session: SimulationSession,
    strings: Strings,
    enabled: Boolean,
) {
    var eventName by remember { mutableStateOf("") }
    var payloadJson by remember { mutableStateOf("{}") }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = eventName,
            onValueChange = { eventName = it },
            label = { Text("Event") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = payloadJson,
            onValueChange = { payloadJson = it },
            label = { Text("Payload JSON") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Button(
            enabled = enabled && eventName.isNotBlank(),
            onClick = {
                if (eventName.isNotBlank()) {
                    session.send(eventName = eventName, payloadJson = payloadJson)
                    eventName = ""
                }
            },
        ) {
            Text(strings.aiSend)
        }
    }
}
