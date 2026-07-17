package dev.kuml.renderer.kuiver.uml

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.uml.UmlActor

/**
 * Renders a [UmlActor] as a stick-figure (head circle + body/arms/legs) with the
 * actor name centred below.
 *
 * @param element the UML actor to render
 * @param theme active visual theme
 */
@Composable
internal fun UmlActorNode(
    element: UmlActor,
    theme: KumlTheme,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp),
    ) {
        StickFigure(
            modifier = Modifier.size(40.dp, 56.dp),
            theme = theme,
        )
        Text(
            text = element.name,
            style = theme.typography.body,
            color = theme.colors.foreground,
        )
    }
}

@Composable
internal fun StickFigure(
    modifier: Modifier,
    theme: KumlTheme,
) {
    val color = theme.colors.foreground
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 1.5f
        val stroke = Stroke(width = strokeWidth)

        // Head
        val headRadius = w * 0.18f
        val headCenterX = w / 2f
        val headCenterY = headRadius + 2f
        drawCircle(
            color = color,
            radius = headRadius,
            center = Offset(headCenterX, headCenterY),
            style = stroke,
        )

        // Body
        val bodyTop = headCenterY + headRadius
        val bodyBottom = h * 0.62f
        drawLine(color, Offset(w / 2f, bodyTop), Offset(w / 2f, bodyBottom), strokeWidth)

        // Arms
        val armY = h * 0.42f
        drawLine(color, Offset(w * 0.1f, armY), Offset(w * 0.9f, armY), strokeWidth)

        // Left leg
        drawLine(color, Offset(w / 2f, bodyBottom), Offset(w * 0.15f, h - 2f), strokeWidth)
        // Right leg
        drawLine(color, Offset(w / 2f, bodyBottom), Offset(w * 0.85f, h - 2f), strokeWidth)
    }
}
