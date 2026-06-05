package dev.kuml.renderer.kuiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import dev.kuml.renderer.theme.KumlTheme

/**
 * Fallback edge Composable for relationship types not covered by V1 dispatch.
 *
 * Renders a plain dashed line from [source] to [target] in the muted colour.
 * Not expected to appear in normal V1 diagrams.
 */
@Composable
internal fun GenericFallbackEdge(
    source: Offset,
    target: Offset,
    theme: KumlTheme,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawLine(
            color = theme.colors.muted,
            start = source,
            end = target,
            strokeWidth = theme.borders.thin.value,
        )
    }
}
