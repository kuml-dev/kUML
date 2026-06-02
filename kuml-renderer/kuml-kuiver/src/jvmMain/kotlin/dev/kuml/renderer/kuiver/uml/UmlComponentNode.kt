package dev.kuml.renderer.kuiver.uml

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.uml.UmlComponent

/**
 * Renders a [UmlComponent] as a rectangle with the component glyph
 * (two small stacked rectangles) in the top-right corner and the name centred.
 *
 * The glyph follows the UML 2.x notation for components.
 *
 * @param element the UML component to render
 * @param theme active visual theme
 */
@Composable
internal fun UmlComponentNode(element: UmlComponent, theme: KumlTheme) {
    Box(
        modifier = Modifier
            .border(theme.borders.regular, theme.colors.border)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = element.name,
            style = theme.typography.title,
            color = theme.colors.foreground,
            modifier = Modifier.align(Alignment.Center).padding(end = 20.dp),
        )
        // Component glyph — top-right
        ComponentGlyph(
            modifier = Modifier.align(Alignment.TopEnd).size(20.dp, 14.dp),
            theme = theme,
        )
    }
}

@Composable
private fun ComponentGlyph(modifier: Modifier, theme: KumlTheme) {
    val color = theme.colors.foreground
    val strokeWidth = theme.borders.thin.value
    Canvas(modifier = modifier) {
        val rectW = size.width * 0.55f
        val rectH = size.height * 0.38f
        val protrude = size.width * 0.2f
        val stroke = Stroke(width = strokeWidth)
        // Top small rectangle
        drawRect(
            color = color,
            topLeft = Offset(-protrude / 2f, size.height * 0.1f),
            size = Size(rectW, rectH),
            style = stroke,
        )
        // Bottom small rectangle
        drawRect(
            color = color,
            topLeft = Offset(-protrude / 2f, size.height * 0.55f),
            size = Size(rectW, rectH),
            style = stroke,
        )
    }
}
