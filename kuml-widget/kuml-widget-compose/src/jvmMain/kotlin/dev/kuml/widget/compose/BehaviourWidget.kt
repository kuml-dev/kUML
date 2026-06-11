package dev.kuml.widget.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Root composable for the Executable Behaviour Widget (V2.0.43).
 *
 * Layout:
 * ```
 * ┌─────────────────────────────────────────┐
 * │  Row                                    │
 * │  ┌──────────────────┐  ┌─────────────┐  │
 * │  │  DiagramPanel    │  │ControlPanel │  │
 * │  │  (SVG via Batik) │  │             │  │
 * │  └──────────────────┘  └─────────────┘  │
 * │  TraceScrubberWidget                    │
 * └─────────────────────────────────────────┘
 * ```
 *
 * @param state the [BehaviourWidgetState] driving the widget.
 * @param modifier optional [Modifier].
 */
@Composable
public fun BehaviourWidget(
    state: BehaviourWidgetState,
    modifier: Modifier = Modifier,
) {
    // Compute layout once and cache it — only needs to change if the model changes,
    // which is not supported in the MVP.
    val layoutResult = remember(state.model) { computeLayout(state.model) }
    var lastHighlightIds by remember { mutableStateOf(emptySet<String>()) }
    var lastSvg by remember { mutableStateOf("") }

    val highlightIds = state.currentHighlightIds()
    if (highlightIds != lastHighlightIds || lastSvg.isEmpty()) {
        lastHighlightIds = highlightIds
        lastSvg = renderStateMachineSvg(state.model, layoutResult, highlightIds)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DiagramPanel(
                svgString = lastSvg,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ControlPanel(
                state = state,
                modifier = Modifier.width(240.dp).fillMaxHeight(),
            )
        }
        TraceScrubberWidget(
            state = state,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
