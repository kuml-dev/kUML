package dev.kuml.layout.grid

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.Point
import kotlin.math.abs

private const val DEFAULT_CORNER_RADIUS_PX: Float = 8f

/**
 * Routet eine einzelne Kante zwischen [source] und [target] im gewünschten [style].
 *
 * **V1.1.12-Strategie (pure Geometrie, kein Channel-Routing):**
 *  - [EdgeRouteStyle.Direct]: einzelne Linie.
 *  - [EdgeRouteStyle.OrthogonalRounded]: 3-Segment-H-V-H- oder V-H-V-Route mit
 *    einem mittleren Waypoint. Welche Variante gewählt wird, hängt davon ab,
 *    welche der beiden Dimensionen größer ist — die Route folgt der "Haupt-
 *    Strecke" zuerst, biegt dann zur Seite ab.
 *  - [EdgeRouteStyle.Bezier]: kubische Bezier mit zwei Kontrollpunkten, die
 *    senkrecht zur Direktlinie ausgelenkt sind (10 % der Direktdistanz).
 *  - [EdgeRouteStyle.TreeRounded]: V1.1.12 fällt auf OrthogonalRounded zurück
 *    (Hierarchie-spezifisches Routing ist V1.2).
 *
 * Channel-Routing (Kollisionen mit anderen Knoten umgehen) ist V1.2-Thema; das
 * ist hier explizit verzichtet — die V1.1.12-Routen können durch Fremdknoten
 * gehen, wenn die Grid-Platzierung das so ergibt. Property-Tests prüfen das
 * **nur** für direkt benachbarte Endpunkte.
 */
internal fun routeEdge(
    source: Point,
    target: Point,
    style: EdgeRouteStyle,
): EdgeRoute =
    when (style) {
        EdgeRouteStyle.Direct -> EdgeRoute.Direct(source = source, target = target)
        EdgeRouteStyle.OrthogonalRounded -> orthogonalRounded(source, target)
        EdgeRouteStyle.TreeRounded -> orthogonalRoundedAsTree(source, target)
        EdgeRouteStyle.Bezier -> bezier(source, target)
    }

private fun orthogonalRounded(
    source: Point,
    target: Point,
): EdgeRoute.OrthogonalRounded {
    val dx = target.x - source.x
    val dy = target.y - source.y
    val waypoints =
        when {
            // Quasi-horizontal: H-V-H mit Knick in der Mitte (V-Segment).
            abs(dx) >= abs(dy) -> {
                val midX = source.x + dx / 2f
                listOf(Point(midX, source.y), Point(midX, target.y))
            }
            // Quasi-vertikal: V-H-V mit Knick in der Mitte (H-Segment).
            else -> {
                val midY = source.y + dy / 2f
                listOf(Point(source.x, midY), Point(target.x, midY))
            }
        }
    return EdgeRoute.OrthogonalRounded(
        source = source,
        target = target,
        waypoints = waypoints,
        cornerRadiusPx = DEFAULT_CORNER_RADIUS_PX,
    )
}

private fun orthogonalRoundedAsTree(
    source: Point,
    target: Point,
): EdgeRoute.TreeRounded {
    // Eltern-Kind-Hierarchien fließen typischerweise top-to-bottom — wir nutzen
    // hier V-H-V als bevorzugte Route (Wurzel nach unten, dann zur Seite, dann
    // wieder runter zum Kind). Wenn dx == 0 gibt es keinen Knick.
    val midY = source.y + (target.y - source.y) / 2f
    val waypoints =
        if (source.x == target.x) {
            emptyList()
        } else {
            listOf(Point(source.x, midY), Point(target.x, midY))
        }
    return EdgeRoute.TreeRounded(
        source = source,
        target = target,
        waypoints = waypoints,
        cornerRadiusPx = DEFAULT_CORNER_RADIUS_PX,
    )
}

private fun bezier(
    source: Point,
    target: Point,
): EdgeRoute.Bezier {
    val dx = target.x - source.x
    val dy = target.y - source.y
    // Senkrechte Auslenkung der zwei Kontrollpunkte (10 % der Direktdistanz),
    // um eine sichtbare Kurve zu erzeugen. Ohne diese Auslenkung wäre die
    // "Bezier" optisch identisch zur Direct-Linie.
    val perpScale = 0.10f
    val nx = -dy
    val ny = dx
    val len = kotlin.math.sqrt(nx * nx + ny * ny).coerceAtLeast(1f)
    val ox = (nx / len) * perpScale * kotlin.math.sqrt(dx * dx + dy * dy)
    val oy = (ny / len) * perpScale * kotlin.math.sqrt(dx * dx + dy * dy)
    val c1 = Point(source.x + dx / 3f + ox, source.y + dy / 3f + oy)
    val c2 = Point(source.x + 2f * dx / 3f + ox, source.y + 2f * dy / 3f + oy)
    return EdgeRoute.Bezier(source = source, target = target, controlPoints = listOf(c1, c2))
}
