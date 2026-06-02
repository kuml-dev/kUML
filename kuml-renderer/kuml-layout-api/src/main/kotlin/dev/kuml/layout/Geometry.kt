package dev.kuml.layout

import kotlinx.serialization.Serializable

/**
 * Zweidimensionaler Punkt im Koordinatenraum des Layout-Canvas.
 *
 * Koordinaten sind in abstrakten Pixeln; der Renderer bestimmt den Maßstab.
 */
@Serializable
public data class Point(
    val x: Float,
    val y: Float,
)

/**
 * Breite und Höhe eines Rechtecks.
 *
 * Wird sowohl für [LayoutNode.intrinsicSize] (Renderer-Messung) als auch
 * für das Gesamt-Canvas in [LayoutResult.canvas] verwendet.
 */
@Serializable
public data class Size(
    val width: Float,
    val height: Float,
)

/**
 * Achsenparalleles Rechteck mit Ursprungspunkt und Ausdehnung.
 *
 * Repräsentiert die berechneten Bounds eines Knotens oder einer Gruppe nach dem Layout.
 */
@Serializable
public data class Rect(
    val origin: Point,
    val size: Size,
)

/**
 * Innenabstände an allen vier Seiten eines Containers.
 *
 * Wird in [LayoutGroup.padding] verwendet, damit die Engine Kindknoten
 * mit ausreichend Abstand zum Gruppen-Rand platziert.
 */
@Serializable
public data class Insets(
    val top: Float,
    val right: Float,
    val bottom: Float,
    val left: Float,
) {
    public companion object {
        /** Insets ohne Abstand auf allen Seiten. */
        public val ZERO: Insets = Insets(0f, 0f, 0f, 0f)
    }
}

/**
 * Abstands-Parameter für den Layout-Algorithmus.
 *
 * Steuerung über [LayoutHints.spacing]; Engines verwenden diese Werte als Mindestabstände.
 */
@Serializable
public data class Spacing(
    val nodeToNode: Float,
    val edgeToEdge: Float,
    val groupPadding: Float,
) {
    public companion object {
        /** Standard-Abstände: 40 px Knoten-zu-Knoten, 12 px Kante-zu-Kante, 16 px Gruppen-Padding. */
        public val DEFAULT: Spacing = Spacing(40f, 12f, 16f)
    }
}

/**
 * Bevorzugte Haupt-Flussrichtung des Layout-Algorithmus.
 *
 * Wird in [LayoutHints.direction] übergeben. Die Interpretation ist engine-spezifisch;
 * alle Standard-Engines sollen diese Richtung respektieren.
 */
public enum class LayoutDirection {
    TopToBottom,
    BottomToTop,
    LeftToRight,
    RightToLeft,
}
