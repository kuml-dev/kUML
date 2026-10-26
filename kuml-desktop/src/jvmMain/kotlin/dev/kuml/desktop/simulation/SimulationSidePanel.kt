package dev.kuml.desktop.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.i18n.Strings
import java.awt.Cursor

/** Narrowest, default and widest width (dp) of the simulator side panel. */
internal const val SIDE_PANEL_MIN_DP: Float = 280f
internal const val SIDE_PANEL_DEFAULT_DP: Float = 340f
internal const val SIDE_PANEL_MAX_DP: Float = 520f

/** Keeps a dragged panel width inside [SIDE_PANEL_MIN_DP]..[SIDE_PANEL_MAX_DP]; NaN falls back to the default. */
internal fun clampSidePanelWidth(widthDp: Float): Float =
    if (widthDp.isNaN()) SIDE_PANEL_DEFAULT_DP else widthDp.coerceIn(SIDE_PANEL_MIN_DP, SIDE_PANEL_MAX_DP)

/**
 * Right-hand column of a simulation: inspector, variables and trace as three collapsible
 * sections, resizable by dragging its left edge. The width lives only in this composition (not
 * persisted). Plain Compose, never overlaid on the heavyweight preview canvas next to it.
 */
@Composable
internal fun SimulationSidePanel(
    session: SimulationSession,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    var widthDp by remember { mutableStateOf(SIDE_PANEL_DEFAULT_DP) }
    var inspectorOpen by remember { mutableStateOf(true) }
    var variablesOpen by remember { mutableStateOf(true) }
    var traceOpen by remember { mutableStateOf(true) }
    val density = LocalDensity.current.density

    Row(modifier = modifier.width(widthDp.dp).testTag(SimTestTags.SIDE_PANEL)) {
        Box(
            modifier =
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                    .semantics { contentDescription = strings.simSidePanelResize }
                    .pointerInput(density) {
                        detectDragGestures { _, dragAmount ->
                            // Dragging left widens the panel.
                            widthDp = clampSidePanelWidth(widthDp - dragAmount.x / density)
                        }
                    },
        )
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            PanelSection(
                id = "inspector",
                title = strings.simInspectorTitle,
                expanded = inspectorOpen,
                onToggle = { inspectorOpen = !inspectorOpen },
                weight = 0.40f,
                strings = strings,
            ) { SimulationInspectorPane(session = session, strings = strings, modifier = Modifier.fillMaxWidth()) }
            HorizontalDivider()
            PanelSection(
                id = "variables",
                title = strings.simVariablesTitle,
                expanded = variablesOpen,
                onToggle = { variablesOpen = !variablesOpen },
                weight = 0.25f,
                strings = strings,
            ) { SimulationVariablesPane(session = session, strings = strings, modifier = Modifier.fillMaxWidth()) }
            HorizontalDivider()
            PanelSection(
                id = "trace",
                title = strings.simTraceTitle,
                expanded = traceOpen,
                onToggle = { traceOpen = !traceOpen },
                weight = 0.35f,
                strings = strings,
            ) { SimulationTracePane(session = session, strings = strings, showTitle = false, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

/** One collapsible section: a collapsed one is as tall as its header, expanded ones share the height by [weight]. */
@Composable
private fun ColumnScope.PanelSection(
    id: String,
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    weight: Float,
    strings: Strings,
    content: @Composable () -> Unit,
) {
    Column(modifier = if (expanded) Modifier.weight(weight) else Modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(SimTestTags.section(id))
                    .semantics {
                        heading()
                        stateDescription = if (expanded) strings.simSectionExpanded else strings.simSectionCollapsed
                    }.clickable(onClick = onToggle)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(text = if (expanded) "▾ " else "▸ ", fontSize = 12.sp)
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        if (expanded) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}
