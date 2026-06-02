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
import dev.kuml.c4.model.C4Container
import dev.kuml.renderer.theme.KumlTheme

/**
 * Renders a [C4Container] as a rounded rectangle.
 *
 * Layout follows C4 notation:
 * - `[Container: <technology>]` or `[Container]` (muted, italic)
 * - Name (bold)
 * - Optional description (small, italic)
 *
 * @param element the C4 container to render
 * @param theme active visual theme
 */
@Composable
internal fun C4ContainerNode(element: C4Container, theme: KumlTheme) {
    val technologyLabel = element.technology
        ?.let { "[Container: $it]" }
        ?: "[Container]"

    Column(
        modifier = Modifier
            .border(
                width = theme.borders.regular,
                color = theme.colors.border,
                shape = RoundedCornerShape(theme.borders.cornerRadius),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
