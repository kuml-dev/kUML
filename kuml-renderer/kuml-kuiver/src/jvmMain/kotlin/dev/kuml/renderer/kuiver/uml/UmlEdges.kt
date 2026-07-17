package dev.kuml.renderer.kuiver.uml

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// ── UML Edge Composables ──────────────────────────────────────────────────────

/**
 * UML Association — solid line with an open arrowhead.
 *
 * Optional [UmlAssociation.name] is rendered as a label near the midpoint.
 */
@Composable
internal fun AssociationEdge(
    relationship: UmlAssociation,
    source: Offset,
    target: Offset,
    theme: KumlTheme,
) {
    EdgeCanvas(source, target) {
        drawSolidLine(source, target, theme.colors.edge, theme.borders.regular.value)
        drawOpenArrow(source, target, theme.colors.edge, theme.borders.regular.value)
    }
    relationship.name?.let { label ->
        Text(text = label, style = theme.typography.small, color = theme.colors.muted)
    }
}

/**
 * UML Generalization (inheritance) — solid line with a hollow triangle arrowhead.
 */
@Composable
internal fun GeneralizationEdge(
    relationship: UmlGeneralization,
    source: Offset,
    target: Offset,
    theme: KumlTheme,
) {
    EdgeCanvas(source, target) {
        drawSolidLine(source, target, theme.colors.edge, theme.borders.regular.value)
        drawHollowTriangle(source, target, theme.colors.edge, theme.borders.regular.value)
    }
}

/**
 * UML InterfaceRealization — dashed line with a hollow triangle arrowhead.
 */
@Composable
internal fun InterfaceRealizationEdge(
    relationship: UmlInterfaceRealization,
    source: Offset,
    target: Offset,
    theme: KumlTheme,
) {
    EdgeCanvas(source, target) {
        drawDashedLine(source, target, theme.colors.edgeMuted, theme.borders.regular.value)
        drawHollowTriangle(source, target, theme.colors.edgeMuted, theme.borders.regular.value)
    }
}

/**
 * UML Dependency — dashed line with an open arrowhead.
 *
 * Optional [UmlDependency.name] rendered as label.
 */
@Composable
internal fun DependencyEdge(
    relationship: UmlDependency,
    source: Offset,
    target: Offset,
    theme: KumlTheme,
) {
    EdgeCanvas(source, target) {
        drawDashedLine(source, target, theme.colors.edgeMuted, theme.borders.regular.value)
        drawOpenArrow(source, target, theme.colors.edgeMuted, theme.borders.regular.value)
    }
    relationship.name?.let { label ->
        Text(text = label, style = theme.typography.small, color = theme.colors.muted)
    }
}

/**
 * UML Connector — solid line, no arrowhead.
 *
 * Optional [UmlConnector.name] rendered as label.
 */
@Composable
internal fun ConnectorEdge(
    relationship: UmlConnector,
    source: Offset,
    target: Offset,
    theme: KumlTheme,
) {
    EdgeCanvas(source, target) {
        drawSolidLine(source, target, theme.colors.edge, theme.borders.regular.value)
    }
    relationship.name?.let { label ->
        Text(text = label, style = theme.typography.small, color = theme.colors.muted)
    }
}

/**
 * UML Include — dashed line with open arrowhead and fixed `«include»` label.
 */
@Composable
internal fun IncludeEdge(
    relationship: UmlInclude,
    source: Offset,
    target: Offset,
    theme: KumlTheme,
) {
    EdgeCanvas(source, target) {
        drawDashedLine(source, target, theme.colors.edgeMuted, theme.borders.regular.value)
        drawOpenArrow(source, target, theme.colors.edgeMuted, theme.borders.regular.value)
    }
    Text(text = "«include»", style = theme.typography.stereotype, color = theme.colors.muted)
}

/**
 * UML Extend — dashed line with open arrowhead and fixed `«extend»` label.
 */
@Composable
internal fun ExtendEdge(
    relationship: UmlExtend,
    source: Offset,
    target: Offset,
    theme: KumlTheme,
) {
    EdgeCanvas(source, target) {
        drawDashedLine(source, target, theme.colors.edgeMuted, theme.borders.regular.value)
        drawOpenArrow(source, target, theme.colors.edgeMuted, theme.borders.regular.value)
    }
    Text(text = "«extend»", style = theme.typography.stereotype, color = theme.colors.muted)
}

// ── Drawing helpers ───────────────────────────────────────────────────────────

@Composable
private fun EdgeCanvas(
    source: Offset,
    target: Offset,
    drawBlock: DrawScope.() -> Unit,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawBlock()
    }
}

private fun DrawScope.drawSolidLine(
    source: Offset,
    target: Offset,
    color: Color,
    strokeWidth: Float,
) {
    drawLine(color = color, start = source, end = target, strokeWidth = strokeWidth)
}

private fun DrawScope.drawDashedLine(
    source: Offset,
    target: Offset,
    color: Color,
    strokeWidth: Float,
) {
    drawLine(
        color = color,
        start = source,
        end = target,
        strokeWidth = strokeWidth,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f), 0f),
    )
}

private fun DrawScope.drawOpenArrow(
    source: Offset,
    target: Offset,
    color: Color,
    strokeWidth: Float,
) {
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
    drawLine(color, p1, target, strokeWidth)
    drawLine(color, p2, target, strokeWidth)
}

private fun DrawScope.drawHollowTriangle(
    source: Offset,
    target: Offset,
    color: Color,
    strokeWidth: Float,
) {
    val angle = atan2(target.y - source.y, target.x - source.x)
    val arrowLen = 14f
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
    // Outline of the triangle (no fill)
    drawLine(color, target, p1, strokeWidth)
    drawLine(color, target, p2, strokeWidth)
    drawLine(color, p1, p2, strokeWidth)
}
