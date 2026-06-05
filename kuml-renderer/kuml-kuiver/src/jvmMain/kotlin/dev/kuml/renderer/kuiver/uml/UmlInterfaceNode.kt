package dev.kuml.renderer.kuiver.uml

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.uml.UmlInterface

/**
 * Renders a [UmlInterface] as a three-section rectangle with a `«interface»` stereotype header.
 *
 * @param element the UML interface to render
 * @param theme active visual theme
 */
@Composable
internal fun UmlInterfaceNode(element: UmlInterface, theme: KumlTheme) {
    Column(
        modifier = Modifier
            .border(theme.borders.regular, theme.colors.border)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "«interface»",
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

        if (element.attributes.isNotEmpty() || element.operations.isNotEmpty()) {
            Divider(color = theme.colors.border, thickness = theme.borders.thin)
        }

        element.attributes.forEach { attr ->
            Text(
                text = attr.format(),
                style = theme.typography.body,
                color = theme.colors.foreground,
            )
        }

        if (element.attributes.isNotEmpty() && element.operations.isNotEmpty()) {
            Divider(color = theme.colors.border, thickness = theme.borders.thin)
        }

        element.operations.forEach { op ->
            Text(
                text = op.format(),
                style = theme.typography.body,
                color = theme.colors.foreground,
            )
        }
    }
}
