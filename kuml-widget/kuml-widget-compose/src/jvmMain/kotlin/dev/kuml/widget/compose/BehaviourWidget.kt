package dev.kuml.widget.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    // Layout and SVG are re-derived whenever the model changes (e.g. a guard
    // edit via ControlPanel/OclGuardEditor swaps in a new state.model) as well
    // as when the highlighted vertices change.
    val layoutResult = remember(state.model) { computeLayout(state.model) }
    val highlightIds = state.currentHighlightIds()
    val svg =
        remember(state.model, highlightIds, layoutResult) {
            renderStateMachineSvg(state.model, layoutResult, highlightIds)
        }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DiagramPanel(
                svgString = svg,
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
