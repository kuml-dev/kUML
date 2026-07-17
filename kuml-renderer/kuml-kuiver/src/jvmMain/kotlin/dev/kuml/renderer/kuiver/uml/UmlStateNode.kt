package dev.kuml.renderer.kuiver.uml

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.uml.UmlState

/**
 * Renders a [UmlState] as a rounded rectangle with the state name centred.
 *
 * The corner radius is fixed at `12.dp` per the V1 spec, overriding the theme
 * default of `4.dp`. Composite states (those with non-empty [UmlState.substates])
 * are rendered identically in V1 — nested substates are out of scope for this ticket.
 *
 * @param element the UML state to render
 * @param theme active visual theme
 */
@Composable
internal fun UmlStateNode(
    element: UmlState,
    theme: KumlTheme,
) {
    val stateCornerRadius = 12.dp // V1 spec override: always 12 dp for states

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .defaultMinSize(minWidth = 80.dp, minHeight = 40.dp)
                .border(
                    width = theme.borders.regular,
                    color = theme.colors.border,
                    shape = RoundedCornerShape(stateCornerRadius),
                ).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = element.name,
            style = theme.typography.body,
            color = theme.colors.foreground,
        )
    }
}
