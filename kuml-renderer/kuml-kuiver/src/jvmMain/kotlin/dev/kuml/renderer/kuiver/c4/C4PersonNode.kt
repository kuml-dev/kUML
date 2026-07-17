package dev.kuml.renderer.kuiver.c4

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.kuml.c4.model.C4Person
import dev.kuml.renderer.kuiver.uml.StickFigure
import dev.kuml.renderer.theme.KumlTheme

/**
 * Renders a [C4Person] as a stick figure above a labelled box.
 *
 * The box shows the name (bold) and an optional description in small italic text,
 * following the standard C4 notation for persons/users.
 *
 * @param element the C4 person to render
 * @param theme active visual theme
 */
@Composable
internal fun C4PersonNode(
    element: C4Person,
    theme: KumlTheme,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp),
    ) {
        StickFigure(
            modifier = Modifier.padding(bottom = 4.dp),
            theme = theme,
        )
        Column(
            modifier =
                Modifier
                    .border(theme.borders.regular, theme.colors.border)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = element.name,
                style = theme.typography.title,
                color = theme.colors.foreground,
            )
            element.description?.let { desc ->
                Text(
                    text = desc,
                    style = theme.typography.small.copy(fontStyle = FontStyle.Italic),
                    color = theme.colors.muted,
                )
            }
        }
    }
}
