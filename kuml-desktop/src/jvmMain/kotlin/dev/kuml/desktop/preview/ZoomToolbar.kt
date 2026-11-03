package dev.kuml.desktop.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.IconTooltipButton
import dev.kuml.desktop.ui.KumlIcons
import dev.kuml.desktop.ui.tooltipEnd
import org.apache.batik.swing.JSVGCanvas
import java.awt.geom.AffineTransform

/** Zoom step for the preview's Zoom In / Zoom Out buttons (P4, design review). */
private const val ZOOM_STEP = PREVIEW_ZOOM_STEP

/**
 * Zoom in / zoom out / 1:1 / fit / center strip for a Batik preview [canvas] — shared by the single-file
 * [PreviewPane] and the Knowledge Workspace's diagram column, so both offer the same controls.
 *
 * Design review (UI team session): "100 %" and "Fit" were identical code (Batik's
 * `resetRenderingTransform()` only resets the user transform; fit-to-viewport is a separate
 * viewing transform recomputed on every resize). "100 %" was removed, not "Fit", because "Fit"
 * names what the user expects rather than what the code does. There is deliberately no zoom
 * factor readout — a correct one never existed, and introducing it is a separate decision.
 *
 * The strip sits directly above the heavyweight `SwingPanel`, which hides any Compose popup
 * overlapping it, so the tooltips go to the RIGHT of each button, inside the strip's own band
 * (see the warning in `IconTooltipButton.kt`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZoomToolbar(
    canvas: JSVGCanvas,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        IconTooltipButton(
            icon = KumlIcons.ZoomIn,
            description = strings.previewZoomIn,
            onClick = { zoomCanvas(canvas = canvas, factor = ZOOM_STEP) },
            tooltipPlacement = tooltipEnd(),
        )
        IconTooltipButton(
            icon = KumlIcons.ZoomOut,
            description = strings.previewZoomOut,
            onClick = { zoomCanvas(canvas = canvas, factor = 1.0 / ZOOM_STEP) },
            tooltipPlacement = tooltipEnd(),
        )
        IconTooltipButton(
            icon = KumlIcons.ActualSize,
            description = strings.previewView.actualSize,
            onClick = { navigationOf(canvas)?.actualSize?.invoke() },
            tooltipPlacement = tooltipEnd(),
        )
        IconTooltipButton(
            icon = KumlIcons.FitToWindow,
            description = fitTooltip(strings = strings, mac = isMacOs()),
            onClick = { navigationOf(canvas)?.fit?.invoke() ?: canvas.resetRenderingTransform() },
            tooltipPlacement = tooltipEnd(),
        )
        IconTooltipButton(
            icon = KumlIcons.Center,
            description = strings.previewView.center,
            onClick = { navigationOf(canvas)?.center?.invoke() },
            tooltipPlacement = tooltipEnd(),
        )
    }
}

/** Zooms [canvas] by [factor] around its own viewport centre. */
private fun zoomCanvas(
    canvas: JSVGCanvas,
    factor: Double,
) {
    val width = canvas.width.takeIf { it > 0 } ?: 400
    val height = canvas.height.takeIf { it > 0 } ?: 300
    val current = canvas.renderingTransform ?: AffineTransform()
    val zoomed = zoomedTransform(current = current, factor = factor, centerX = width / 2.0, centerY = height / 2.0)
    // Through the shared navigation path so the visible-area limit applies to buttons too.
    navigationOf(canvas)?.setTransform?.invoke(zoomed) ?: run { canvas.renderingTransform = zoomed }
}

private fun navigationOf(canvas: JSVGCanvas): PreviewNavigation? = canvas.getClientProperty(PREVIEW_NAV_KEY) as? PreviewNavigation

private fun isMacOs(): Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("mac")

/** Fit tooltip with its shortcuts: Home and Ctrl/Cmd+0 (modifier shown as the symbol on macOS). */
internal fun fitTooltip(
    strings: Strings,
    mac: Boolean,
): String {
    val modifier = if (mac) "\u2318" else strings.previewView.ctrlKey
    return strings.previewView.fitWithShortcuts.format(modifier, strings.previewView.homeKey)
}
