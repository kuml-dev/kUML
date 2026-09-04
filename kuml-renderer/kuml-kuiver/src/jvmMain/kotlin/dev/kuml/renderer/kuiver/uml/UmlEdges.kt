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
import dev.kuml.uml.UmlNavigability
import dev.kuml.uml.navigability
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
    EdgeCanvas(source = source, target = target) {
        drawSolidLine(source = source, target = target, color = theme.colors.edge, strokeWidth = theme.borders.regular.value)
        associationArrow(nav = relationship.navigability(), source = source, target = target)?.let { (s, t) ->
            drawOpenArrow(source = s, target = t, color = theme.colors.edge, strokeWidth = theme.borders.regular.value)
        }
    }
    relationship.name?.let { label ->
        Text(text = label, style = theme.typography.small, color = theme.colors.muted)
    }
}

/**
 * Resolves which (from, to) pair — if any — [drawOpenArrow] should be
 * called with for an association end [nav]. Returns `null` when no
 * arrowhead should be drawn ([UmlNavigability.BOTH] / [UmlNavigability.NEITHER]).
 * [drawOpenArrow] always places the tip at its `target` parameter, so
 * [UmlNavigability.SOURCE_ONLY] returns the pair reversed ([target] then
 * [source]) to point the arrowhead at the association's actual source end.
 *
 * Kuiver draws no aggregation/composition diamonds (unlike the SVG
 * renderer) — no tip-inset collision case exists here; see the
 * fix/uml-association-navigable-arrows CHANGELOG entry's "explicitly not in
 * this wave" note for the follow-up scope.
 */
internal fun associationArrow(
    nav: UmlNavigability,
    source: Offset,
    target: Offset,
): Pair<Offset, Offset>? =
    when (nav) {
        UmlNavigability.BOTH, UmlNavigability.NEITHER -> null
        UmlNavigability.TARGET_ONLY -> source to target
        UmlNavigability.SOURCE_ONLY -> target to source
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
    EdgeCanvas(source = source, target = target) {
        drawSolidLine(source = source, target = target, color = theme.colors.edge, strokeWidth = theme.borders.regular.value)
        drawHollowTriangle(source = source, target = target, color = theme.colors.edge, strokeWidth = theme.borders.regular.value)
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
    EdgeCanvas(source = source, target = target) {
        drawDashedLine(source = source, target = target, color = theme.colors.edgeMuted, strokeWidth = theme.borders.regular.value)
        drawHollowTriangle(source = source, target = target, color = theme.colors.edgeMuted, strokeWidth = theme.borders.regular.value)
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
    EdgeCanvas(source = source, target = target) {
        drawDashedLine(source = source, target = target, color = theme.colors.edgeMuted, strokeWidth = theme.borders.regular.value)
        drawOpenArrow(source = source, target = target, color = theme.colors.edgeMuted, strokeWidth = theme.borders.regular.value)
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
    EdgeCanvas(source = source, target = target) {
        drawSolidLine(source = source, target = target, color = theme.colors.edge, strokeWidth = theme.borders.regular.value)
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
    EdgeCanvas(source = source, target = target) {
        drawDashedLine(source = source, target = target, color = theme.colors.edgeMuted, strokeWidth = theme.borders.regular.value)
        drawOpenArrow(source = source, target = target, color = theme.colors.edgeMuted, strokeWidth = theme.borders.regular.value)
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
    EdgeCanvas(source = source, target = target) {
        drawDashedLine(source = source, target = target, color = theme.colors.edgeMuted, strokeWidth = theme.borders.regular.value)
        drawOpenArrow(source = source, target = target, color = theme.colors.edgeMuted, strokeWidth = theme.borders.regular.value)
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
