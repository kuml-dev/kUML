package dev.kuml.renderer.kuiver.c4

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.kuml.c4.model.C4Component
import dev.kuml.renderer.theme.KumlTheme

/**
 * Renders a [C4Component] as a rounded rectangle.
 *
 * Layout follows C4 notation:
 * - `[Component: <technology>]` or `[Component]` (muted, italic)
 * - Name (bold)
 * - Optional description (small, italic)
 *
 * @param element the C4 component to render
 * @param theme active visual theme
 */
@Composable
internal fun C4ComponentNode(
    element: C4Component,
    theme: KumlTheme,
) {
    val technologyLabel =
        element.technology
            ?.let { "[Component: $it]" }
            ?: "[Component]"

    Column(
        modifier =
            Modifier
                .border(
                    width = theme.borders.regular,
                    color = theme.colors.border,
                    shape = RoundedCornerShape(theme.borders.cornerRadius),
                ).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = technologyLabel,
            style = theme.typography.small.copy(fontStyle = FontStyle.Italic),
            color = theme.colors.muted,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = element.name,
            style = theme.typography.title,
            color = theme.colors.foreground,
            modifier = Modifier.fillMaxWidth(),
        )
        element.description?.let { desc ->
            Text(
                text = desc,
                style = theme.typography.small.copy(fontStyle = FontStyle.Italic),
                color = theme.colors.muted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
