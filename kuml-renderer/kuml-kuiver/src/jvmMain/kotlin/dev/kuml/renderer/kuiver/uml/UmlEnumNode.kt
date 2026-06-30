package dev.kuml.renderer.kuiver.uml

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.uml.UmlEnumeration

/**
 * Renders a [UmlEnumeration] as a rectangle with a `«enumeration»` stereotype header and
 * a list of literal values below the divider.
 *
 * @param element the UML enumeration to render
 * @param theme active visual theme
 */
@Composable
internal fun UmlEnumNode(element: UmlEnumeration, theme: KumlTheme) {
    Column(
        modifier = Modifier
            .border(theme.borders.regular, theme.colors.border)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "«enumeration»",
            style = theme.typography.stereotype,
            color = theme.colors.muted,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = element.name,
            style = theme.typography.title,
            color = theme.colors.foreground,
            modifier = Modifier.fillMaxWidth(),
        )

        if (element.literals.isNotEmpty()) {
            HorizontalDivider(color = theme.colors.border, thickness = theme.borders.thin)
        }

        element.literals.forEach { literal ->
            Text(
                text = literal.name,
                style = theme.typography.body,
                color = theme.colors.foreground,
            )
        }
    }
}
