package dev.kuml.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * kUML-eigene Icon-Familie (P2/P5, design review): bewusst KEINE Compose-Material-Icons-
 * Abhängigkeit — `material-icons-core` deckt nur 1 von 6 ursprünglich benötigten Glyphen ab
 * (kein Zoom-In/Zoom-Out/Fit-to-Window/Source-Split-Diagram-Satz aus einer Quelle), und
 * `material-icons-extended` wäre mehrere tausend Vektoren nur für die paar benötigten Formen.
 *
 * Alle Icons in einem 24×24-Viewport, Strichstärke 2dp (Zoom/Fit/Chevron/Close) bzw. gefüllte
 * Flächen (View-Modus-Icons), `currentColor`-Prinzip: die Pfade selbst tragen nur eine
 * Platzhalterfarbe — [androidx.compose.material3.Icon]'s `tint`-Parameter überschreibt sie
 * beim Rendern per ColorFilter, siehe Aufrufstellen in `PreviewPane.kt`/`MainWindow.kt`/`FindBar.kt`.
 *
 * Ein Autor, ein Strichgewicht (Alan Kay/Jony Ive, design review) — alle Vektoren wurden auf
 * demselben Raster gezeichnet (Chevron/Close ergänzt V3.7.5, review fix: FindBar's `MiniButton`
 * war eine zweite, parallele Tooltip-Implementierung neben [dev.kuml.desktop.ui.IconTooltipButton]
 * mit Text-Glyphen statt Vektoren — dieser Satz macht FindBar auf [IconTooltipButton] umstellbar).
 */
object KumlIcons {
    /** Lupe mit "+" — Zoom In. */
    val ZoomIn: ImageVector by lazy { buildZoomIcon(name = "KumlZoomIn", plusSign = true) }

    /** Lupe mit "−" — Zoom Out. */
    val ZoomOut: ImageVector by lazy { buildZoomIcon(name = "KumlZoomOut", plusSign = false) }

    /** Rahmen mit vier nach außen zeigenden Eck-Klammern — Fit to Window. */
    val FitToWindow: ImageVector by lazy { buildFitToWindowIcon() }

    /** Volles Rechteck mit Indikator-Leiste links — Ansichtsmodus "Nur Quelltext". */
    val ViewSource: ImageVector by lazy { buildViewRectIcon(name = "KumlViewSource", indicatorOnLeft = true) }

    /** Zwei Rechtecke nebeneinander (Trenner mittig) — Ansichtsmodus "Geteilt". */
    val ViewSplit: ImageVector by lazy { buildViewSplitIcon() }

    /** Volles Rechteck mit Indikator-Leiste rechts (gespiegelt zu [ViewSource]) — "Nur Diagramm". */
    val ViewDiagram: ImageVector by lazy { buildViewRectIcon(name = "KumlViewDiagram", indicatorOnLeft = false) }

    /** Nach oben zeigendes Chevron — "vorheriger Treffer" in [dev.kuml.desktop.editor.FindBar]. */
    val ChevronUp: ImageVector by lazy { buildChevronIcon(name = "KumlChevronUp", pointingUp = true) }

    /** Nach unten zeigendes Chevron — "nächster Treffer" in [dev.kuml.desktop.editor.FindBar]. */
    val ChevronDown: ImageVector by lazy { buildChevronIcon(name = "KumlChevronDown", pointingUp = false) }

    /** Zwei kreuzende Linien ("×") — Schließen, z. B. [dev.kuml.desktop.editor.FindBar]. */
    val Close: ImageVector by lazy { buildCloseIcon() }

    private const val VIEWPORT = 24f
    private const val STROKE_WIDTH = 2f
    private val PLACEHOLDER = SolidColor(Color.Black)

    /** Approximates a circle centered at ([cx], [cy]) with radius [r] via two half-circle arcs. */
    private fun PathBuilder.circle(
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        moveTo(cx + r, cy)
        arcTo(
            horizontalEllipseRadius = r,
            verticalEllipseRadius = r,
            theta = 0f,
            isMoreThanHalf = true,
            isPositiveArc = true,
            x1 = cx - r,
            y1 = cy,
        )
        arcTo(
            horizontalEllipseRadius = r,
            verticalEllipseRadius = r,
            theta = 0f,
            isMoreThanHalf = true,
            isPositiveArc = true,
            x1 = cx + r,
            y1 = cy,
        )
        close()
    }

    private fun builder(name: String): ImageVector.Builder =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        )

    private fun buildZoomIcon(
        name: String,
        plusSign: Boolean,
    ): ImageVector =
        builder(name)
            .apply {
                // Lens (stroked circle) — centered at (10, 10), radius 6.
                path(
                    name = "$name.lens",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    circle(cx = 10f, cy = 10f, r = 6f)
                }
                // Handle — diagonal from the lens edge toward the bottom-right corner.
                path(
                    name = "$name.handle",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                ) {
                    moveTo(14.6f, 14.6f)
                    lineTo(20f, 20f)
                }
                // Horizontal bar of the "+"/"−" sign inside the lens (always present).
                path(
                    name = "$name.horizontal",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                ) {
                    moveTo(7f, 10f)
                    lineTo(13f, 10f)
                }
                if (plusSign) {
                    // Vertical bar — only for Zoom In, turning the horizontal bar into a "+".
                    path(
                        name = "$name.vertical",
                        fill = null,
                        stroke = PLACEHOLDER,
                        strokeLineWidth = STROKE_WIDTH,
                        strokeLineCap = StrokeCap.Round,
                    ) {
                        moveTo(10f, 7f)
                        lineTo(10f, 13f)
                    }
                }
            }.build()

    private fun buildFitToWindowIcon(): ImageVector =
        builder("KumlFitToWindow")
            .apply {
                path(
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    // Top-left corner bracket.
                    moveTo(8f, 4f)
                    lineTo(4f, 4f)
                    lineTo(4f, 8f)
                    // Top-right corner bracket.
                    moveTo(16f, 4f)
                    lineTo(20f, 4f)
                    lineTo(20f, 8f)
                    // Bottom-left corner bracket.
                    moveTo(4f, 16f)
                    lineTo(4f, 20f)
                    lineTo(8f, 20f)
                    // Bottom-right corner bracket.
                    moveTo(20f, 16f)
                    lineTo(20f, 20f)
                    lineTo(16f, 20f)
                }
            }.build()

    /** Full outlined rectangle with a filled "active side" indicator bar (left or right). */
    private fun buildViewRectIcon(
        name: String,
        indicatorOnLeft: Boolean,
    ): ImageVector =
        builder(name)
            .apply {
                path(
                    name = "$name.outline",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(4f, 4f)
                    lineTo(20f, 4f)
                    lineTo(20f, 20f)
                    lineTo(4f, 20f)
                    close()
                }
                val indicatorX = if (indicatorOnLeft) 4f else 17f
                path(name = "$name.indicator", fill = PLACEHOLDER) {
                    moveTo(indicatorX, 4f)
                    lineTo(indicatorX + 3f, 4f)
                    lineTo(indicatorX + 3f, 20f)
                    lineTo(indicatorX, 20f)
                    close()
                }
            }.build()

    /** Full outlined rectangle split into two equal panes by a vertical divider. */
    private fun buildViewSplitIcon(): ImageVector =
        builder("KumlViewSplit")
            .apply {
                path(
                    name = "outline",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(4f, 4f)
                    lineTo(20f, 4f)
                    lineTo(20f, 20f)
                    lineTo(4f, 20f)
                    close()
                }
                path(
                    name = "divider",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                ) {
                    moveTo(12f, 4f)
                    lineTo(12f, 20f)
                }
            }.build()

    /** Single-stroke chevron, pointing up or down (V3.7.5, FindBar prev/next). */
    private fun buildChevronIcon(
        name: String,
        pointingUp: Boolean,
    ): ImageVector =
        builder(name)
            .apply {
                path(
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    if (pointingUp) {
                        moveTo(6f, 14f)
                        lineTo(12f, 8f)
                        lineTo(18f, 14f)
                    } else {
                        moveTo(6f, 10f)
                        lineTo(12f, 16f)
                        lineTo(18f, 10f)
                    }
                }
            }.build()

    /** Two crossing diagonal strokes ("×") — V3.7.5, FindBar close button. */
    private fun buildCloseIcon(): ImageVector =
        builder("KumlClose")
            .apply {
                path(
                    name = "KumlClose.diag1",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                ) {
                    moveTo(6f, 6f)
                    lineTo(18f, 18f)
                }
                path(
                    name = "KumlClose.diag2",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                ) {
                    moveTo(18f, 6f)
                    lineTo(6f, 18f)
                }
            }.build()
}
