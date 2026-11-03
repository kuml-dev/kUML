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

    /** Zwei "1" um einen Doppelpunkt — tatsächliche Größe (1:1). */
    val ActualSize: ImageVector by lazy { buildActualSizeIcon() }

    /** Kreis mit Mittelpunkt und vier kurzen Strichen — Zentrieren. */
    val Center: ImageVector by lazy { buildCenterIcon() }

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

    // V3.x — Live-Simulation von Zustandsautomaten im Editor (SimulationBar).

    /** Pfeil auf Endanschlag — "Schritt" (ein Event manuell feuern). */
    val SimStep: ImageVector by lazy { buildSimStepIcon() }

    /** Doppeldreieck nach rechts (Vorspul-Symbol, KEIN Play-Dreieck) — "Automatisch weiter". */
    val SimAutoAdvance: ImageVector by lazy { buildSimAutoAdvanceIcon() }

    /** Zwei senkrechte Balken — "Pause" (ersetzt [SimAutoAdvance], solange Auto-Advance läuft). */
    val SimPause: ImageVector by lazy { buildSimPauseIcon() }

    /** Kreispfeil (Reset) — "Zurücksetzen". */
    val SimReset: ImageVector by lazy { buildSimResetIcon() }

    /** Rahmen mit senkrechter Trennlinie (Seitenbereich) — Inspektor ein-/ausblenden. */
    val SimInspector: ImageVector by lazy { buildSimInspectorIcon() }

    /** Stift — Guard bearbeiten. */
    val EditGuard: ImageVector by lazy { buildEditGuardIcon() }

    /** Rückwärtspfeil — ursprünglichen Guard wiederherstellen. */
    val RevertGuard: ImageVector by lazy { buildRevertGuardIcon() }

    // Export aus der Vorschau: Ablage-Schale mit Pfeil nach unten, rechts oben ein Abzeichen fürs Format.

    /** Schale mit Pfeil und Kurve zwischen zwei Quadraten (Vektor-Abzeichen) — "Als SVG speichern". */
    val SaveSvg: ImageVector by lazy { buildSaveIcon(name = "KumlSaveSvg", badge = SaveBadge.Vector) }

    /** Schale mit Pfeil und 2x2-Pixelraster (Raster-Abzeichen) — "Als PNG speichern". */
    val SavePng: ImageVector by lazy { buildSaveIcon(name = "KumlSavePng", badge = SaveBadge.Raster) }

    /** Blatt mit Eselsohr und drei Textzeilen, ohne Pfeil — "Dieses Dokument als PDF exportieren". */
    val PdfDocument: ImageVector by lazy { buildPdfDocumentIcon() }

    // Menu icons (welle desktop-menu-icons b1): one stroke-only family at STROKE_WIDTH, round caps/joins.
    // Only Info and Server carry small filled dots.

    /** Sheet with folded corner and a "+" — File > New. */
    val DocumentNew: ImageVector by lazy { buildDocumentNewIcon() }

    /** Opened folder — File > Open. */
    val FolderOpen: ImageVector by lazy { buildFolderOpenIcon() }

    /** Folder with a "+" — File > New Workspace. */
    val WorkspaceNew: ImageVector by lazy { buildWorkspaceNewIcon() }

    /** Folder with a window grid — File > Open Workspace. */
    val Workspace: ImageVector by lazy { buildWorkspaceIcon() }

    /** Tray with a downward arrow, no format badge — File > Save. */
    val Save: ImageVector by lazy { buildSaveIcon(name = "KumlSave", badge = null) }

    /** Floppy outline with a pencil at the lower right — File > Save As. */
    val SaveAs: ImageVector by lazy { buildSaveAsIcon() }

    /** Arrow leaving an open bracket — File > Quit. */
    val Quit: ImageVector by lazy { buildQuitIcon() }

    /** Curved arrow pointing left — Edit > Undo. */
    val Undo: ImageVector by lazy { buildUndoRedoIcon(name = "KumlUndo", pointingLeft = true) }

    /** Curved arrow pointing right (mirror of [Undo]) — Edit > Redo. */
    val Redo: ImageVector by lazy { buildUndoRedoIcon(name = "KumlRedo", pointingLeft = false) }

    /** Magnifying glass without a sign — Edit > Find. */
    val Search: ImageVector by lazy { buildZoomIcon(name = "KumlSearch", plusSign = null) }

    /** "?" in a circle — Help > Documentation. */
    val Help: ImageVector by lazy { buildHelpIcon() }

    /** "i" in a circle — Help > About. */
    val Info: ImageVector by lazy { buildInfoIcon() }

    /** Three horizontal sliders with offset knobs — settings. */
    val Settings: ImageVector by lazy { buildSettingsIcon() }

    /** Four-point star with a small star at the upper right — AI. */
    val Ai: ImageVector by lazy { buildAiIcon() }

    /** Speech bubble with a "+" — new AI session. */
    val ChatNew: ImageVector by lazy { buildChatNewIcon() }

    /** Outlined play triangle — start simulation. */
    val SimStart: ImageVector by lazy { buildSimStartIcon() }

    /** Outlined square with rounded corners — stop simulation. */
    val SimStop: ImageVector by lazy { buildSimStopIcon() }

    /** Two stacked rectangles, each with a status dot — MCP server. */
    val Server: ImageVector by lazy { buildServerIcon() }

    /** Three squares plus one offset square — plugin manager. */
    val Plugins: ImageVector by lazy { buildPluginsIcon() }

    // Dialog-button icons (welle desktop-dialog-button-icons b2a): same stroke family, only used by IconLabel.

    /** Check mark — confirm / keep / copied. */
    val Check: ImageVector by lazy { buildCheckIcon() }

    /** Triangle with exclamation mark — proceed despite a warning. */
    val Warning: ImageVector by lazy { buildWarningIcon() }

    /** Two overlapping rounded squares — copy to the clipboard. */
    val Copy: ImageVector by lazy { buildCopyIcon() }

    /** Open circular arrow with an arrowhead — regenerate / reload. */
    val Refresh: ImageVector by lazy { buildRefreshIcon() }

    /** Sheet with folded corner and two text lines — open a document. */
    val Document: ImageVector by lazy { buildDocumentIcon() }

    /** Landscape sheet with folded corner — A3 landscape. */
    val PageLandscape: ImageVector by lazy { buildPageLandscapeIcon() }

    private enum class SaveBadge { Vector, Raster }

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
        plusSign: Boolean?,
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
                // Horizontal bar of the "+"/"−" sign inside the lens; absent for the plain search lens.
                if (plusSign != null) {
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
                }
                if (plusSign == true) {
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

    private fun buildActualSizeIcon(): ImageVector =
        builder("KumlActualSize")
            .apply {
                path(
                    name = "KumlActualSize.digits",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    // Left "1": entry stroke and stem.
                    moveTo(3.5f, 9f)
                    lineTo(6f, 7f)
                    lineTo(6f, 17f)
                    // Right "1": entry stroke and stem.
                    moveTo(15.5f, 9f)
                    lineTo(18f, 7f)
                    lineTo(18f, 17f)
                }
                // Colon between the digits: two filled dots.
                path(name = "KumlActualSize.colon", fill = PLACEHOLDER) {
                    circle(cx = 11f, cy = 10f, r = 1.25f)
                    circle(cx = 11f, cy = 14f, r = 1.25f)
                }
            }.build()

    private fun buildCenterIcon(): ImageVector =
        builder("KumlCenter")
            .apply {
                path(
                    name = "KumlCenter.ring",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    circle(cx = 12f, cy = 12f, r = 6f)
                    // Four short ticks pointing at the centre.
                    moveTo(12f, 2f)
                    lineTo(12f, 4f)
                    moveTo(12f, 20f)
                    lineTo(12f, 22f)
                    moveTo(2f, 12f)
                    lineTo(4f, 12f)
                    moveTo(20f, 12f)
                    lineTo(22f, 12f)
                }
                path(name = "KumlCenter.dot", fill = PLACEHOLDER) {
                    circle(cx = 12f, cy = 12f, r = 1.75f)
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

    /** Horizontal bar with an arrowhead against a vertical end-stop — "fire one step". */
    private fun buildSimStepIcon(): ImageVector =
        builder("KumlSimStep")
            .apply {
                path(
                    name = "KumlSimStep.shaft",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(4f, 12f)
                    lineTo(15f, 12f)
                    moveTo(11f, 7f)
                    lineTo(16f, 12f)
                    lineTo(11f, 17f)
                }
                path(
                    name = "KumlSimStep.stop",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                ) {
                    moveTo(20f, 6f)
                    lineTo(20f, 18f)
                }
            }.build()

    /** Two right-pointing triangles ("fast-forward") — deliberately NOT a single play triangle. */
    private fun buildSimAutoAdvanceIcon(): ImageVector =
        builder("KumlSimAutoAdvance")
            .apply {
                path(name = "KumlSimAutoAdvance.tri1", fill = PLACEHOLDER) {
                    moveTo(3f, 6f)
                    lineTo(11f, 12f)
                    lineTo(3f, 18f)
                    close()
                }
                path(name = "KumlSimAutoAdvance.tri2", fill = PLACEHOLDER) {
                    moveTo(12f, 6f)
                    lineTo(20f, 12f)
                    lineTo(12f, 18f)
                    close()
                }
            }.build()

    /** Two vertical bars — "pause", replacing [SimAutoAdvance] while auto-advance is running. */
    private fun buildSimPauseIcon(): ImageVector =
        builder("KumlSimPause")
            .apply {
                path(name = "KumlSimPause.bar1", fill = PLACEHOLDER) {
                    moveTo(6f, 5f)
                    lineTo(10f, 5f)
                    lineTo(10f, 19f)
                    lineTo(6f, 19f)
                    close()
                }
                path(name = "KumlSimPause.bar2", fill = PLACEHOLDER) {
                    moveTo(14f, 5f)
                    lineTo(18f, 5f)
                    lineTo(18f, 19f)
                    lineTo(14f, 19f)
                    close()
                }
            }.build()

    /** Circular arrow — "reset the simulation to its initial state". */
    private fun buildSimResetIcon(): ImageVector =
        builder("KumlSimReset")
            .apply {
                path(
                    name = "KumlSimReset.arc",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                ) {
                    // Nearly-full circle (open at the top-right, where the arrowhead sits).
                    moveTo(18.36f, 7.64f)
                    arcTo(
                        horizontalEllipseRadius = 7f,
                        verticalEllipseRadius = 7f,
                        theta = 0f,
                        isMoreThanHalf = true,
                        isPositiveArc = true,
                        x1 = 12f,
                        y1 = 19f,
                    )
                    arcTo(
                        horizontalEllipseRadius = 7f,
                        verticalEllipseRadius = 7f,
                        theta = 0f,
                        isMoreThanHalf = false,
                        isPositiveArc = true,
                        x1 = 17.32f,
                        y1 = 9f,
                    )
                }
                path(
                    name = "KumlSimReset.arrowhead",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(18.36f, 3.64f)
                    lineTo(18.36f, 7.64f)
                    lineTo(14.36f, 7.64f)
                }
            }.build()

    /** Frame with a vertical divider on its right third — "side panel". */
    private fun buildSimInspectorIcon(): ImageVector =
        builder("KumlSimInspector")
            .apply {
                path(
                    name = "KumlSimInspector.frame",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(4f, 5f)
                    lineTo(20f, 5f)
                    lineTo(20f, 19f)
                    lineTo(4f, 19f)
                    close()
                    moveTo(14f, 5f)
                    lineTo(14f, 19f)
                }
            }.build()

    /** Pencil — "edit the guard". */
    private fun buildEditGuardIcon(): ImageVector =
        builder("KumlEditGuard")
            .apply {
                path(
                    name = "KumlEditGuard.pencil",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(4f, 20f)
                    lineTo(5f, 15f)
                    lineTo(16f, 4f)
                    lineTo(20f, 8f)
                    lineTo(9f, 19f)
                    close()
                    moveTo(14f, 6f)
                    lineTo(18f, 10f)
                }
            }.build()

    /** Hooked arrow pointing back — "restore the original". */
    private fun buildRevertGuardIcon(): ImageVector =
        builder("KumlRevertGuard")
            .apply {
                path(
                    name = "KumlRevertGuard.arrow",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(9f, 6f)
                    lineTo(4f, 11f)
                    lineTo(9f, 16f)
                    moveTo(4f, 11f)
                    lineTo(14f, 11f)
                    lineTo(19f, 13f)
                    lineTo(19f, 17f)
                    lineTo(15f, 19f)
                    lineTo(9f, 19f)
                }
            }.build()

    /**
     * Shared base of [Save]/[SaveSvg]/[SavePng] (a null badge leaves the plain tray): tray `M4 15 V19 H20 V15` and an arrow at x=8 (tip right edge x=13),
     * kept clear of the format badge in the field x 14..23, y 2..9.
     */
    private fun buildSaveIcon(
        name: String,
        badge: SaveBadge?,
    ): ImageVector =
        builder(name)
            .apply {
                path(
                    name = "$name.base",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(4f, 15f)
                    lineTo(4f, 19f)
                    lineTo(20f, 19f)
                    lineTo(20f, 15f)
                    moveTo(8f, 3f)
                    lineTo(8f, 13f)
                    moveTo(4f, 9f)
                    lineTo(8f, 13f)
                    lineTo(12f, 9f)
                }
                if (badge == SaveBadge.Raster) {
                    // Frame (thinner stroke, the field is only 7 units wide) with two of four 2.5-unit cells filled.
                    path(
                        name = "$name.frame",
                        fill = null,
                        stroke = PLACEHOLDER,
                        strokeLineWidth = 1.5f,
                        strokeLineJoin = StrokeJoin.Round,
                    ) {
                        moveTo(15.75f, 2.75f)
                        lineTo(21.25f, 2.75f)
                        lineTo(21.25f, 8.25f)
                        lineTo(15.75f, 8.25f)
                        close()
                    }
                    path(name = "$name.cells", fill = PLACEHOLDER) {
                        moveTo(16.5f, 3.5f)
                        lineTo(19f, 3.5f)
                        lineTo(19f, 6f)
                        lineTo(16.5f, 6f)
                        close()
                        moveTo(19f, 6f)
                        lineTo(21f, 6f)
                        lineTo(21f, 8f)
                        lineTo(19f, 8f)
                        close()
                    }
                } else if (badge == SaveBadge.Vector) {
                    path(
                        name = "$name.curve",
                        fill = null,
                        stroke = PLACEHOLDER,
                        strokeLineWidth = STROKE_WIDTH,
                        strokeLineCap = StrokeCap.Round,
                    ) {
                        moveTo(16f, 8f)
                        curveTo(17f, 3f, 21f, 8f, 22f, 3f)
                    }
                    path(name = "$name.anchors", fill = PLACEHOLDER) {
                        moveTo(15f, 7f)
                        lineTo(17f, 7f)
                        lineTo(17f, 9f)
                        lineTo(15f, 9f)
                        close()
                        moveTo(21f, 2f)
                        lineTo(23f, 2f)
                        lineTo(23f, 4f)
                        lineTo(21f, 4f)
                        close()
                    }
                }
            }.build()

    /** Sheet with folded corner and three text lines. */
    private fun buildPdfDocumentIcon(): ImageVector =
        builder("KumlPdfDocument")
            .apply {
                path(
                    name = "KumlPdfDocument.sheet",
                    fill = null,
                    stroke = PLACEHOLDER,
                    strokeLineWidth = STROKE_WIDTH,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(6f, 2f)
                    lineTo(14f, 2f)
                    lineTo(19f, 7f)
                    lineTo(19f, 22f)
                    lineTo(6f, 22f)
                    close()
                    moveTo(14f, 2f)
                    lineTo(14f, 7f)
                    lineTo(19f, 7f)
                    moveTo(9f, 12f)
                    lineTo(16f, 12f)
                    moveTo(9f, 15f)
                    lineTo(16f, 15f)
                    moveTo(9f, 18f)
                    lineTo(13f, 18f)
                }
            }.build()

    private fun ImageVector.Builder.strokePath(
        name: String,
        block: PathBuilder.() -> Unit,
    ) = path(
        name = name,
        fill = null,
        stroke = PLACEHOLDER,
        strokeLineWidth = STROKE_WIDTH,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

    private fun PathBuilder.sheetOutline() {
        moveTo(6f, 2f)
        lineTo(14f, 2f)
        lineTo(19f, 7f)
        lineTo(19f, 22f)
        lineTo(6f, 22f)
        close()
        moveTo(14f, 2f)
        lineTo(14f, 7f)
        lineTo(19f, 7f)
    }

    private fun PathBuilder.folderOutline() {
        moveTo(3f, 6f)
        lineTo(9f, 6f)
        lineTo(11f, 8f)
        lineTo(21f, 8f)
        lineTo(21f, 19f)
        lineTo(3f, 19f)
        close()
    }

    private fun PathBuilder.quarterArc(
        x: Float,
        y: Float,
        clockwise: Boolean = true,
    ) = arcTo(
        horizontalEllipseRadius = 2f,
        verticalEllipseRadius = 2f,
        theta = 0f,
        isMoreThanHalf = false,
        isPositiveArc = clockwise,
        x1 = x,
        y1 = y,
    )

    private fun buildCheckIcon(): ImageVector =
        builder("KumlCheck")
            .apply {
                strokePath(name = "KumlCheck.tick") {
                    moveTo(5f, 12f)
                    lineTo(10f, 17f)
                    lineTo(19f, 7f)
                }
            }.build()

    private fun buildWarningIcon(): ImageVector =
        builder("KumlWarning")
            .apply {
                strokePath(name = "KumlWarning.triangle") {
                    moveTo(12f, 3f)
                    lineTo(22f, 20f)
                    lineTo(2f, 20f)
                    close()
                    moveTo(12f, 9f)
                    lineTo(12f, 13f)
                    moveTo(12f, 16.5f)
                    lineTo(12f, 16.6f)
                }
            }.build()

    private fun buildCopyIcon(): ImageVector =
        builder("KumlCopy")
            .apply {
                strokePath(name = "KumlCopy.sheets") {
                    // Front square (9..20), then the back square's visible L (4..15).
                    moveTo(11f, 9f)
                    lineTo(18f, 9f)
                    quarterArc(x = 20f, y = 11f)
                    lineTo(20f, 18f)
                    quarterArc(x = 18f, y = 20f)
                    lineTo(11f, 20f)
                    quarterArc(x = 9f, y = 18f)
                    lineTo(9f, 11f)
                    quarterArc(x = 11f, y = 9f)
                    close()
                    moveTo(15f, 9f)
                    lineTo(15f, 6f)
                    quarterArc(x = 13f, y = 4f, clockwise = false)
                    lineTo(6f, 4f)
                    quarterArc(x = 4f, y = 6f, clockwise = false)
                    lineTo(4f, 13f)
                    quarterArc(x = 6f, y = 15f, clockwise = false)
                    lineTo(9f, 15f)
                }
            }.build()

    private fun buildRefreshIcon(): ImageVector =
        builder("KumlRefresh")
            .apply {
                strokePath(name = "KumlRefresh.arc") {
                    // 300 degrees of a circle, gap at the top; arrowhead corner at the end.
                    moveTo(15.5f, 5.94f)
                    arcTo(
                        horizontalEllipseRadius = 7f,
                        verticalEllipseRadius = 7f,
                        theta = 0f,
                        isMoreThanHalf = true,
                        isPositiveArc = true,
                        x1 = 8.5f,
                        y1 = 5.94f,
                    )
                    moveTo(5.3f, 5.94f)
                    lineTo(8.5f, 5.94f)
                    lineTo(8.5f, 9.1f)
                }
            }.build()

    private fun buildDocumentIcon(): ImageVector =
        builder("KumlDocument")
            .apply {
                strokePath(name = "KumlDocument.sheet") {
                    moveTo(6f, 3f)
                    lineTo(14f, 3f)
                    lineTo(18f, 7f)
                    lineTo(18f, 21f)
                    lineTo(6f, 21f)
                    close()
                    moveTo(14f, 3f)
                    lineTo(14f, 7f)
                    lineTo(18f, 7f)
                    moveTo(9f, 12f)
                    lineTo(15f, 12f)
                    moveTo(9f, 16f)
                    lineTo(15f, 16f)
                }
            }.build()

    private fun buildPageLandscapeIcon(): ImageVector =
        builder("KumlPageLandscape")
            .apply {
                strokePath(name = "KumlPageLandscape.sheet") {
                    moveTo(3f, 6f)
                    lineTo(17f, 6f)
                    lineTo(21f, 10f)
                    lineTo(21f, 18f)
                    lineTo(3f, 18f)
                    close()
                    moveTo(17f, 6f)
                    lineTo(17f, 10f)
                    lineTo(21f, 10f)
                }
            }.build()

    private fun buildDocumentNewIcon(): ImageVector =
        builder("KumlDocumentNew")
            .apply {
                strokePath(name = "KumlDocumentNew.sheet") {
                    sheetOutline()
                    moveTo(12.5f, 12f)
                    lineTo(12.5f, 18f)
                    moveTo(9.5f, 15f)
                    lineTo(15.5f, 15f)
                }
            }.build()

    private fun buildFolderOpenIcon(): ImageVector =
        builder("KumlFolderOpen")
            .apply {
                strokePath(name = "KumlFolderOpen.folder") {
                    // Back wall with tab, then the tilted front flap.
                    moveTo(3f, 19f)
                    lineTo(3f, 6f)
                    lineTo(9f, 6f)
                    lineTo(11f, 8f)
                    lineTo(18f, 8f)
                    lineTo(18f, 11f)
                    moveTo(3f, 19f)
                    lineTo(6f, 11f)
                    lineTo(21.5f, 11f)
                    lineTo(18.5f, 19f)
                    close()
                }
            }.build()

    private fun buildWorkspaceNewIcon(): ImageVector =
        builder("KumlWorkspaceNew")
            .apply {
                strokePath(name = "KumlWorkspaceNew.folder") {
                    folderOutline()
                    moveTo(12f, 11.5f)
                    lineTo(12f, 16.5f)
                    moveTo(9.5f, 14f)
                    lineTo(14.5f, 14f)
                }
            }.build()

    private fun buildWorkspaceIcon(): ImageVector =
        builder("KumlWorkspace")
            .apply {
                strokePath(name = "KumlWorkspace.folder") {
                    folderOutline()
                    // 2x2 window grid spanning the folder body.
                    moveTo(12f, 8f)
                    lineTo(12f, 19f)
                    moveTo(3f, 13.5f)
                    lineTo(21f, 13.5f)
                }
            }.build()

    /** Floppy-style outline (cut corner top right, label bar) plus a pencil overlapping the lower right. */
    private fun buildSaveAsIcon(): ImageVector =
        builder("KumlSaveAs")
            .apply {
                strokePath(name = "KumlSaveAs.disk") {
                    moveTo(11f, 21f)
                    lineTo(3f, 21f)
                    lineTo(3f, 3f)
                    lineTo(16f, 3f)
                    lineTo(21f, 8f)
                    lineTo(21f, 10f)
                    moveTo(7f, 3f)
                    lineTo(7f, 8f)
                    lineTo(14f, 8f)
                    lineTo(14f, 3f)
                }
                strokePath(name = "KumlSaveAs.pencil") {
                    moveTo(12f, 22f)
                    lineTo(13f, 17f)
                    lineTo(19f, 11f)
                    lineTo(23f, 15f)
                    lineTo(17f, 21f)
                    close()
                }
            }.build()

    private fun buildQuitIcon(): ImageVector =
        builder("KumlQuit")
            .apply {
                strokePath(name = "KumlQuit.door") {
                    moveTo(10f, 4f)
                    lineTo(5f, 4f)
                    lineTo(5f, 20f)
                    lineTo(10f, 20f)
                }
                strokePath(name = "KumlQuit.arrow") {
                    moveTo(10f, 12f)
                    lineTo(20f, 12f)
                    moveTo(16f, 8f)
                    lineTo(20f, 12f)
                    lineTo(16f, 16f)
                }
            }.build()

    private fun buildUndoRedoIcon(
        name: String,
        pointingLeft: Boolean,
    ): ImageVector =
        builder(name)
            .apply {
                // Redo is the exact horizontal mirror (x -> 24 - x) of Undo.
                fun x(v: Float) = if (pointingLeft) v else 24f - v
                strokePath(name = "$name.arrow") {
                    moveTo(x(8f), 5f)
                    lineTo(x(3.5f), 9.5f)
                    lineTo(x(8f), 14f)
                    moveTo(x(3.5f), 9.5f)
                    lineTo(x(14f), 9.5f)
                    arcTo(
                        horizontalEllipseRadius = 5.5f,
                        verticalEllipseRadius = 5.5f,
                        theta = 0f,
                        isMoreThanHalf = false,
                        isPositiveArc = pointingLeft,
                        x1 = x(14f),
                        y1 = 20.5f,
                    )
                    lineTo(x(9f), 20.5f)
                }
            }.build()

    private fun buildHelpIcon(): ImageVector =
        builder("KumlHelp")
            .apply {
                strokePath(name = "KumlHelp.ring") { circle(cx = 12f, cy = 12f, r = 9f) }
                strokePath(name = "KumlHelp.mark") {
                    moveTo(9.5f, 9.5f)
                    curveTo(9.5f, 6.5f, 14.5f, 6.5f, 14.5f, 9.5f)
                    curveTo(14.5f, 11.5f, 12f, 11.5f, 12f, 13.5f)
                    // Dot: a zero-length-ish round-capped stroke.
                    moveTo(12f, 16.4f)
                    lineTo(12f, 16.6f)
                }
            }.build()

    private fun buildInfoIcon(): ImageVector =
        builder("KumlInfo")
            .apply {
                strokePath(name = "KumlInfo.ring") { circle(cx = 12f, cy = 12f, r = 9f) }
                strokePath(name = "KumlInfo.stem") {
                    moveTo(12f, 11f)
                    lineTo(12f, 16.5f)
                }
                path(name = "KumlInfo.dot", fill = PLACEHOLDER) { circle(cx = 12f, cy = 7.75f, r = 1.25f) }
            }.build()

    private fun buildSettingsIcon(): ImageVector =
        builder("KumlSettings")
            .apply {
                strokePath(name = "KumlSettings.sliders") {
                    // Three rails, each broken around its knob.
                    moveTo(4f, 6f)
                    lineTo(5.5f, 6f)
                    moveTo(10.5f, 6f)
                    lineTo(20f, 6f)
                    circle(cx = 8f, cy = 6f, r = 2.5f)
                    moveTo(4f, 12f)
                    lineTo(13.5f, 12f)
                    moveTo(18.5f, 12f)
                    lineTo(20f, 12f)
                    circle(cx = 16f, cy = 12f, r = 2.5f)
                    moveTo(4f, 18f)
                    lineTo(7.5f, 18f)
                    moveTo(12.5f, 18f)
                    lineTo(20f, 18f)
                    circle(cx = 10f, cy = 18f, r = 2.5f)
                }
            }.build()

    private fun buildAiIcon(): ImageVector =
        builder("KumlAi")
            .apply {
                strokePath(name = "KumlAi.star") {
                    moveTo(9f, 6f)
                    lineTo(10.6f, 12.4f)
                    lineTo(17f, 14f)
                    lineTo(10.6f, 15.6f)
                    lineTo(9f, 22f)
                    lineTo(7.4f, 15.6f)
                    lineTo(1f, 14f)
                    lineTo(7.4f, 12.4f)
                    close()
                }
                strokePath(name = "KumlAi.spark") {
                    moveTo(19f, 2f)
                    lineTo(19.8f, 4.2f)
                    lineTo(22f, 5f)
                    lineTo(19.8f, 5.8f)
                    lineTo(19f, 8f)
                    lineTo(18.2f, 5.8f)
                    lineTo(16f, 5f)
                    lineTo(18.2f, 4.2f)
                    close()
                }
            }.build()

    private fun buildChatNewIcon(): ImageVector =
        builder("KumlChatNew")
            .apply {
                strokePath(name = "KumlChatNew.bubble") {
                    moveTo(4f, 4f)
                    lineTo(20f, 4f)
                    lineTo(20f, 16f)
                    lineTo(11f, 16f)
                    lineTo(7f, 20f)
                    lineTo(7f, 16f)
                    lineTo(4f, 16f)
                    close()
                    moveTo(12f, 7.5f)
                    lineTo(12f, 12.5f)
                    moveTo(9.5f, 10f)
                    lineTo(14.5f, 10f)
                }
            }.build()

    private fun buildSimStartIcon(): ImageVector =
        builder("KumlSimStart")
            .apply {
                strokePath(name = "KumlSimStart.triangle") {
                    moveTo(7f, 4f)
                    lineTo(19f, 12f)
                    lineTo(7f, 20f)
                    close()
                }
            }.build()

    private fun buildSimStopIcon(): ImageVector =
        builder("KumlSimStop")
            .apply {
                strokePath(name = "KumlSimStop.square") {
                    moveTo(8f, 6f)
                    lineTo(16f, 6f)
                    quarterArc(x = 18f, y = 8f)
                    lineTo(18f, 16f)
                    quarterArc(x = 16f, y = 18f)
                    lineTo(8f, 18f)
                    quarterArc(x = 6f, y = 16f)
                    lineTo(6f, 8f)
                    quarterArc(x = 8f, y = 6f)
                    close()
                }
            }.build()

    private fun buildServerIcon(): ImageVector =
        builder("KumlServer")
            .apply {
                strokePath(name = "KumlServer.racks") {
                    moveTo(4f, 4f)
                    lineTo(20f, 4f)
                    lineTo(20f, 10f)
                    lineTo(4f, 10f)
                    close()
                    moveTo(4f, 14f)
                    lineTo(20f, 14f)
                    lineTo(20f, 20f)
                    lineTo(4f, 20f)
                    close()
                }
                path(name = "KumlServer.dots", fill = PLACEHOLDER) {
                    circle(cx = 8f, cy = 7f, r = 1f)
                    circle(cx = 8f, cy = 17f, r = 1f)
                }
            }.build()

    private fun buildPluginsIcon(): ImageVector =
        builder("KumlPlugins")
            .apply {
                strokePath(name = "KumlPlugins.squares") {
                    moveTo(4f, 4f)
                    lineTo(10f, 4f)
                    lineTo(10f, 10f)
                    lineTo(4f, 10f)
                    close()
                    moveTo(4f, 14f)
                    lineTo(10f, 14f)
                    lineTo(10f, 20f)
                    lineTo(4f, 20f)
                    close()
                    moveTo(14f, 14f)
                    lineTo(20f, 14f)
                    lineTo(20f, 20f)
                    lineTo(14f, 20f)
                    close()
                    // The fourth square sits offset outwards (up and right).
                    moveTo(15f, 3f)
                    lineTo(21f, 3f)
                    lineTo(21f, 9f)
                    lineTo(15f, 9f)
                    close()
                }
            }.build()
}
