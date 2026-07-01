package dev.kuml.io.svg.sysml2

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Snappt die Endpunkte einer Activity-Edge an die **tatsächliche Form**
 * des Quell-/Zielknotens (V2.0.46).
 *
 * Hintergrund: Die Layout-Engine (ELK) routet Kanten so, dass die Endpunkte
 * auf dem **Rand der achsparallelen Bounding-Box** eines Knotens landen.
 * Für rechteckige Knoten (z. B. Action-Boxen) ist das richtig — der
 * Rand der Bounding-Box deckt sich mit dem sichtbaren Knoten-Rand.
 *
 * Für **nicht-rechteckige** Activity-Formen — die **Raute** (Decision /
 * Merge) und die **Kreise** (Initial / Final / FlowFinal) — fällt der
 * Bounding-Box-Rand sichtbar **außerhalb** der gezeichneten Form ab. Die
 * Folge: ELK-Endpunkte sitzen z. B. an einer Kante der Raute-Bounding-Box,
 * aber dort ist auf der gezeichneten Raute „nichts" — der Pfeil schwebt
 * im Leerraum zwischen der Bounding-Box-Kante und dem schräg verlaufenden
 * Polygon-Rand der Raute. Analog für Kreise: ein 28×28-Bounding-Box-Quadrat
 * enthält einen Kreis mit Radius ~13 px, der nur an den vier Kardinalpunkten
 * die Bounding-Box berührt — überall sonst klafft eine Lücke.
 *
 * Lösung: für jeden Endpunkt einer Activity-Edge nehmen wir die Gerade
 * vom **Knoten-Mittelpunkt** zum **anliegenden Routen-Punkt** (Waypoint
 * direkt nach `source`, bzw. direkt vor `target`; bei Direct-Routen der
 * jeweils andere Endpunkt) und schieben den Endpunkt entlang dieser
 * Geraden auf den **Shape-Rand**. Welcher Shape-Rand das ist, wird vom
 * Aufrufer pro Knoten als [Shape] übergeben — der Clipper selbst kennt
 * keine Metamodell-Begriffe (`ActivityNodeKind` für SysML 2,
 * `UmlActivityNodeKind` für UML 2.x) und ist damit zwischen den beiden
 * Activity-Render-Pfaden teilbar.
 *
 * Shape-spezifische Geometrie:
 *  - [Shape.Rectangle] — Rechteckrand: `t = min(halfW/|dx|, halfH/|dy|)`.
 *    ELK liefert hier ohnehin schon korrekte Endpunkte; der Snap ist
 *    idempotent, kostet aber nichts und macht die Routine formunabhängig
 *    sicher.
 *  - [Shape.Diamond] — Manhattan-Boundary: der Endpunkt liegt dort, wo
 *    `|dx| / halfW + |dy| / halfH = 1` (klassische L1-Geometrie eines
 *    achsparallelen Diamanten). Der Faktor `t` entlang der Richtung ist
 *    `1 / (|dx|/halfW + |dy|/halfH)`.
 *  - [Shape.Circle] — Euklidische Boundary: der Endpunkt liegt auf dem
 *    Kreis mit dem **vom Aufrufer übergebenen** Außenradius. Der Aufrufer
 *    wählt den Radius passend zum Renderer — z. B. `min(halfW, halfH) *
 *    0.45f` für die SysML-2-Pseudo-Knoten oder die fixen 10/12-px-Werte
 *    der UML-1.1-Pseudo-Knoten.
 *
 * Wenn der Endpunkt nach dem Snap **weiter draußen** wäre als der ELK-
 * Endpunkt selbst (also `t > 1`, was nur passieren kann, wenn der
 * „aimedAt"-Punkt im Inneren des Shapes liegt — pathologischer Fall mit
 * sehr kurzer Edge), behalten wir den ELK-Endpunkt bei.
 */
internal object Sysml2ActivityEdgeClipper {
    /**
     * Form-Beschreibung eines Activity-Knotens für den Clipper.
     *
     * Bounds beziehen sich **immer auf den Koordinatenraum der übergebenen
     * Route** — d. h. der Aufrufer muss die Padding-Verschiebung des
     * Renderers (`+ paddingPx`) bereits auf die Bounds angewandt haben,
     * sonst sind die Mittelpunkte gegenüber den Routen-Punkten versetzt.
     */
    sealed interface Shape {
        val bounds: Rect

        /** Achsparalleles Rechteck (Action-Box, Fork-/Join-Bar, Object-Node). */
        data class Rectangle(
            override val bounds: Rect,
        ) : Shape

        /**
         * Kreis mit explizitem Außenradius (Pseudo-Knoten: Initial / Final
         * / FlowFinal). Der Radius ist **absolut in Pixeln**, damit
         * Aufrufer mit fixen Renderer-Radien (UML 1.1: 10/12 px) und solche
         * mit bounds-relativen Renderer-Radien (SysML 2: `0.45 * min(halfW,
         * halfH)`) beide korrekt andocken.
         */
        data class Circle(
            override val bounds: Rect,
            val radiusPx: Float,
        ) : Shape

        /**
         * Raute (Decision / Merge). Die Raute füllt die ganze Bounding-Box —
         * Eckpunkte liegen bei (cx, cy − halfH), (cx + halfW, cy), (cx, cy +
         * halfH), (cx − halfW, cy).
         */
        data class Diamond(
            override val bounds: Rect,
        ) : Shape
    }

    /**
     * Snappt `route.source` und/oder `route.target` an die jeweiligen
     * Shape-Ränder. Wenn `sourceShape` oder `targetShape` null sind
     * (Endpunkt ist kein Activity-Knoten), bleibt der jeweilige Endpunkt
     * unverändert.
     *
     * Waypoints / Kontroll-Punkte bleiben **immer** unverändert — die
     * Korrektur erfolgt nur am ersten und letzten Punkt der Route, damit
     * der Verlauf der Edge dazwischen unangetastet bleibt.
     */
    fun clip(
        route: EdgeRoute,
        sourceShape: Shape?,
        targetShape: Shape?,
    ): EdgeRoute {
        val newSource =
            if (sourceShape != null) {
                snapToBoundary(sourceShape, aimedAt = secondPoint(route))
            } else {
                route.source
            }
        val newTarget =
            if (targetShape != null) {
                snapToBoundary(targetShape, aimedAt = penultimatePoint(route))
            } else {
                route.target
            }
        return when (route) {
            is EdgeRoute.Direct ->
                route.copy(source = newSource, target = newTarget)
            is EdgeRoute.OrthogonalRounded ->
                route.copy(source = newSource, target = newTarget)
            is EdgeRoute.TreeRounded ->
                route.copy(source = newSource, target = newTarget)
            is EdgeRoute.Bezier ->
                route.copy(source = newSource, target = newTarget)
        }
    }

    /**
     * Punkt, **in dessen Richtung** die Edge den Quellknoten verlässt:
     * der erste Waypoint (orthogonal/Tree), der erste Kontroll-Punkt
     * (Bezier), oder — wenn keiner existiert — der Zielpunkt der Route.
     */
    private fun secondPoint(route: EdgeRoute): Point =
        when (route) {
            is EdgeRoute.Direct -> route.target
            is EdgeRoute.OrthogonalRounded -> route.waypoints.firstOrNull() ?: route.target
            is EdgeRoute.TreeRounded -> route.waypoints.firstOrNull() ?: route.target
            is EdgeRoute.Bezier -> route.controlPoints.firstOrNull() ?: route.target
        }

    /**
     * Punkt, **aus dessen Richtung** die Edge den Zielknoten erreicht:
     * der letzte Waypoint (orthogonal/Tree), der letzte Kontroll-Punkt
     * (Bezier), oder — wenn keiner existiert — der Quellpunkt der Route.
     */
    private fun penultimatePoint(route: EdgeRoute): Point =
        when (route) {
            is EdgeRoute.Direct -> route.source
            is EdgeRoute.OrthogonalRounded -> route.waypoints.lastOrNull() ?: route.source
            is EdgeRoute.TreeRounded -> route.waypoints.lastOrNull() ?: route.source
            is EdgeRoute.Bezier -> route.controlPoints.lastOrNull() ?: route.source
        }

    /**
     * Schiebt den Endpunkt auf den Shape-Rand. Algorithmus: nimm die
     * Gerade `Knoten-Mitte → aimedAt`, finde den Parameter `t ∈ (0, 1]`,
     * an dem die Gerade den Shape-Rand schneidet, und setze den neuen
     * Endpunkt auf `Mitte + t · (aimedAt − Mitte)`. Für `t > 1` (aimedAt
     * liegt im Inneren) clampen wir auf 1, damit der Endpunkt nicht weiter
     * nach außen wandert als der ursprüngliche ELK-Punkt.
     */
    private fun snapToBoundary(
        shape: Shape,
        aimedAt: Point,
    ): Point {
        val bounds = shape.bounds
        val cx = bounds.origin.x + bounds.size.width / 2f
        val cy = bounds.origin.y + bounds.size.height / 2f
        val dx = aimedAt.x - cx
        val dy = aimedAt.y - cy
        // Degenerierter Fall: aimedAt liegt auf der Mitte — keine Richtung,
        // also lassen wir den ELK-Endpunkt unverändert (= Mitte selbst,
        // visueller Fallback der mindestens nicht schlechter ist).
        if (abs(dx) < EPSILON && abs(dy) < EPSILON) return aimedAt
        val halfW = bounds.size.width / 2f
        val halfH = bounds.size.height / 2f
        val t =
            when (shape) {
                is Shape.Circle -> {
                    val length = sqrt(dx * dx + dy * dy)
                    if (length < EPSILON) 1f else shape.radiusPx / length
                }
                is Shape.Diamond -> {
                    val denom = abs(dx) / halfW + abs(dy) / halfH
                    if (denom < EPSILON) 1f else 1f / denom
                }
                is Shape.Rectangle -> {
                    val tx = if (abs(dx) > EPSILON) halfW / abs(dx) else Float.POSITIVE_INFINITY
                    val ty = if (abs(dy) > EPSILON) halfH / abs(dy) else Float.POSITIVE_INFINITY
                    min(tx, ty)
                }
            }
        val clamped = min(t, 1f)
        return Point(cx + dx * clamped, cy + dy * clamped)
    }

    /** Numerische Toleranz für Division-durch-null-Schutz. */
    private const val EPSILON: Float = 1e-3f
}
