package dev.kuml.renderer.kuiver

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.core.model.KumlElement
import dev.kuml.renderer.theme.KumlTheme

/**
 * Fallback composable for element types not covered by V1 dispatch.
 *
 * Renders a simple border-box with the element's ID and class name.
 * Not expected to appear in normal V1 diagrams — present to avoid a runtime crash
 * when the model contains future element types.
 */
@Composable
internal fun GenericFallbackNode(element: KumlElement, theme: KumlTheme) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .border(theme.borders.thin, theme.colors.muted)
            .padding(8.dp),
    ) {
        Text(
            text = "?${element::class.simpleName}",
            style = theme.typography.small,
            color = theme.colors.muted,
        )
    }
}
