package dev.kuml.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Kantenlänge kompakter Toolbar-Icon-Buttons (V3.7.4, design review P4). */
val COMPACT_ICON_BUTTON_SIZE: Dp = 32.dp

/**
 * Icon-Kantenlänge innerhalb eines kompakten Buttons. Das Verhältnis 32/18 liest sich als EIN
 * zusammengehöriges Objekt (Icon deutlich innerhalb seiner Klickfläche), nicht als "Icon lose in
 * einer Fläche" (V3.7.4, design review P4).
 */
val COMPACT_ICON_SIZE: Dp = 18.dp

/**
 * Tooltip über dem Button, mittig, 4 dp Luft (V3.7.4, design review P5) — Default für Controls,
 * die nicht am oberen Fensterrand sitzen (z. B. die Statusleisten-Segmented-Control).
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun tooltipAbove(): TooltipPlacement =
    TooltipPlacement.ComponentRect(
        anchor = Alignment.TopCenter,
        alignment = Alignment.BottomCenter,
        offset = DpOffset(0.dp, (-4).dp),
    )

/**
 * Tooltip unter dem Button (V3.7.4, design review P5) — für Leisten, die am oberen Rand ihres
 * Panes sitzen (z. B. PreviewPane's Zoom-Leiste), wo ein Tooltip darüber am Fensterrand
 * abgeschnitten würde.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun tooltipBelow(): TooltipPlacement =
    TooltipPlacement.ComponentRect(
        anchor = Alignment.BottomCenter,
        alignment = Alignment.TopCenter,
        offset = DpOffset(0.dp, 4.dp),
    )

/**
 * An [IconButton] wrapped in a [TooltipArea] so an icon-only affordance still names itself on
 * hover (a11y) — same tooltip styling as [dev.kuml.desktop.workspace.WorkspaceTreePane]'s
 * `TypeBadge`. Shared between [dev.kuml.desktop.preview.PreviewPane]'s zoom/fit controls (P2)
 * and the view-mode segmented control (P5) so both stay visually identical instead of two
 * near-duplicate tooltip implementations drifting apart.
 *
 * V3.7.4 (design review P4/P5): defaults tightened to [COMPACT_ICON_BUTTON_SIZE] /
 * [COMPACT_ICON_SIZE] (was an un-sized `IconButton` — Material3's 48.dp default — around an
 * 18.dp icon, i.e. a small icon floating in an oversized hit target) and the tooltip is now
 * anchored to the button itself via [tooltipPlacement] rather than following the cursor.
 *
 * GELTUNGSBEREICH (verbindlich, Don Norman): kompakt wird, was FOLGENLOS UND HÄUFIG ist — Zoom-
 * Leiste, Ansichtsmodus, Wasserzeichen-Umschalter. Was FOLGENREICH UND SELTEN ist — Dialog-
 * Aktionen wie Speichern/Verwerfen/Schließen — behält die Material3-Standardgröße, weil ein
 * Fehlklick dort etwas kostet. Nicht "Toolbar vs. Dialog": `Button`/`TextButton` in
 * `AboutDialog`, `PatchPreviewDialog`, `TrustDialog`, `WorkspaceModeChooserDialog`,
 * `AiProviderSettingsDialog` und `PluginManagerPane` bleiben unangetastet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconTooltipButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = COMPACT_ICON_SIZE,
    buttonSize: Dp = COMPACT_ICON_BUTTON_SIZE,
    tooltipPlacement: TooltipPlacement = tooltipAbove(),
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
        tooltipPlacement = tooltipPlacement,
    ) {
        IconButton(onClick = onClick, modifier = modifier.size(buttonSize)) {
            Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(iconSize))
        }
    }
}
