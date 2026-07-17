package dev.kuml.renderer.kuiver.uml

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.uml.UmlUseCase

/**
 * Renders a [UmlUseCase] as an ellipse with the use-case name centred inside.
 *
 * The ellipse is drawn as a `drawBehind` modifier so the text can drive the size.
 *
 * @param element the UML use case to render
 * @param theme active visual theme
 */
@Composable
internal fun UmlUseCaseNode(
    element: UmlUseCase,
    theme: KumlTheme,
) {
    val borderColor = theme.colors.border
    val strokeWidth = theme.borders.regular.value

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .defaultMinSize(minWidth = 90.dp, minHeight = 48.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .drawBehind {
                    drawOval(
                        color = borderColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height),
                        style = Stroke(width = strokeWidth),
                    )
                },
    ) {
        Text(
            text = element.name,
            style = theme.typography.body,
            color = theme.colors.foreground,
        )
    }
}
