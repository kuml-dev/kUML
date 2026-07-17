package dev.kuml.renderer.kuiver.uml

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.Visibility

/**
 * Renders a [UmlClass] as a three-section rectangle:
 * - Top: class name (bold), italic when the class is abstract (UML 2.5)
 * - Middle: attributes list (`name: Type`)
 * - Bottom: operations list (`name(…): ReturnType`)
 *
 * @param element the UML class to render
 * @param theme active visual theme
 */
@Composable
internal fun UmlClassNode(element: UmlClass, theme: KumlTheme) {
    Column(
        modifier = Modifier
            .border(theme.borders.regular, theme.colors.border)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Header — name, italicized when the class is abstract (UML 2.5)
        Text(
            text = element.name,
            style = if (element.isAbstract) {
                theme.typography.title.copy(fontStyle = FontStyle.Italic)
            } else {
                theme.typography.title
            },
            color = theme.colors.foreground,
            modifier = Modifier.fillMaxWidth(),
        )

        if (element.attributes.isNotEmpty() || element.operations.isNotEmpty()) {
            HorizontalDivider(color = theme.colors.border, thickness = theme.borders.thin)
        }

        // Attributes section
        element.attributes.forEach { attr ->
            Text(
                text = attr.format(),
                style = theme.typography.body,
                color = theme.colors.foreground,
            )
        }

        if (element.attributes.isNotEmpty() && element.operations.isNotEmpty()) {
            HorizontalDivider(color = theme.colors.border, thickness = theme.borders.thin)
        }

        // Operations section
        element.operations.forEach { op ->
            Text(
                text = op.format(),
                style = theme.typography.body,
                color = theme.colors.foreground,
            )
        }
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

internal fun UmlProperty.format(): String {
    val vis = visibility.symbol()
    val typeLabel = type.name
    return "$vis $name: $typeLabel"
}

internal fun UmlOperation.format(): String {
    val vis = visibility.symbol()
    val params = parameters
        .filter { it.direction != dev.kuml.uml.ParameterDirection.RETURN }
        .joinToString(", ") { "${it.name}: ${it.type.name}" }
    val ret = returnType?.name?.let { ": $it" } ?: ""
    return "$vis $name($params)$ret"
}

internal fun Visibility.symbol(): String = when (this) {
    Visibility.PUBLIC -> "+"
    Visibility.PRIVATE -> "-"
    Visibility.PROTECTED -> "#"
    Visibility.PACKAGE -> "~"
}
