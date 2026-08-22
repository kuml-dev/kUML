package dev.kuml.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * An [IconButton] wrapped in a [TooltipArea] so an icon-only affordance still names itself on
 * hover (a11y) — same tooltip styling as [dev.kuml.desktop.workspace.WorkspaceTreePane]'s
 * `TypeBadge`. Shared between [dev.kuml.desktop.preview.PreviewPane]'s zoom/fit controls (P2)
 * and the view-mode segmented control (P5) so both stay visually identical instead of two
 * near-duplicate tooltip implementations drifting apart.
 *
 * @param iconSize defaults to 18.dp — matches the compact strip height both call sites use.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconTooltipButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.padding(4.dp),
            ) {
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
    ) {
        IconButton(onClick = onClick, modifier = modifier) {
            Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(iconSize))
        }
    }
}
