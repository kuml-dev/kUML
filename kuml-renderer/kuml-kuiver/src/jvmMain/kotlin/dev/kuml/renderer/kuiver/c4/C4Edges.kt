package dev.kuml.renderer.kuiver.c4

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import dev.kuml.c4.model.C4Relationship
import dev.kuml.renderer.theme.KumlTheme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders a [C4Relationship] as a solid line with an open arrowhead.
 *
 * The label is built from [C4Relationship.label] with an optional
 * `[<technology>]` suffix when [C4Relationship.technology] is present.
 *
 * @param relationship the C4 relationship to render
 * @param source absolute source position from Kuiver
 * @param target absolute target position from Kuiver
 * @param theme active visual theme
 */
@Composable
internal fun C4RelationshipEdge(
    relationship: C4Relationship,
    source: Offset,
    target: Offset,
    theme: KumlTheme,
) {
    val strokeWidth = theme.borders.regular.value
    val arrowColor = theme.colors.edge

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Solid line
        drawLine(arrowColor, source, target, strokeWidth)

        // Open arrowhead
        val angle = atan2(target.y - source.y, target.x - source.x)
        val arrowLen = 12f
        val arrowAngle = 0.4f
        val p1 =
            Offset(
                target.x - arrowLen * cos(angle - arrowAngle),
                target.y - arrowLen * sin(angle - arrowAngle),
            )
        val p2 =
            Offset(
                target.x - arrowLen * cos(angle + arrowAngle),
                target.y - arrowLen * sin(angle + arrowAngle),
            )
        drawLine(arrowColor, p1, target, strokeWidth)
        drawLine(arrowColor, p2, target, strokeWidth)
    }

    // Label — description with optional technology suffix
    val labelText =
        buildString {
            append(relationship.label)
            relationship.technology?.let { append(" [$it]") }
        }
    Text(
        text = labelText,
        style = theme.typography.small,
        color = theme.colors.muted,
    )
}
