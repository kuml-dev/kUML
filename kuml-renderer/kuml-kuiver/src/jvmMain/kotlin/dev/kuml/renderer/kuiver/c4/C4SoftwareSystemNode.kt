package dev.kuml.renderer.kuiver.c4

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.renderer.theme.KumlTheme

/**
 * Renders a [C4SoftwareSystem] as a rounded rectangle with a thick border.
 *
 * Layout follows C4 notation:
 * - `[Software System]` tag (small, italic)
 * - Name (bold)
 * - Optional description (small, italic, muted)
 *
 * @param element the C4 software system to render
 * @param theme active visual theme
 */
@Composable
internal fun C4SoftwareSystemNode(element: C4SoftwareSystem, theme: KumlTheme) {
    Column(
        modifier = Modifier
            .border(
                width = theme.borders.thick,
                color = theme.colors.border,
                shape = RoundedCornerShape(theme.borders.cornerRadius),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "[Software System]",
            style = theme.typography.small.copy(fontStyle = FontStyle.Italic),
            color = theme.colors.muted,
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider(color = theme.colors.border, thickness = theme.borders.thin)
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
