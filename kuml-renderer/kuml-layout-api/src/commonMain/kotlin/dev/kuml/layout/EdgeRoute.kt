package dev.kuml.layout

import kotlinx.serialization.Serializable

/**
 * Versiegelte Hierarchie der möglichen Kanten-Routing-Stile im Layout-Ergebnis.
 *
 * Jede Subklasse trägt Quell- und Zielpunkt; geometrische Zusatzdaten sind
 * stil-spezifisch. Wird in [LayoutResult.edges] pro Kante geliefert.
 */
@Serializable
public sealed interface EdgeRoute {
    /** Absoluter Startpunkt der Kante im Canvas-Koordinatenraum. */
    public val source: Point

    /** Absoluter Endpunkt der Kante im Canvas-Koordinatenraum. */
    public val target: Point

    /**
     * Direkte Verbindung ohne Umwege: eine einzige Linie von [source] nach [target].
     */
    @Serializable
    public data class Direct(
        override val source: Point,
        override val target: Point,
    ) : EdgeRoute

    /**
     * Orthogonales Routing (nur waagerechte/senkrechte Segmente) mit abgerundeten Ecken.
     *
     * [waypoints] sind die Knickpunkte zwischen [source] und [target].
     * [cornerRadiusPx] bestimmt den Radius der abgerundeten Ecken in Pixeln.
     */
    @Serializable
    public data class OrthogonalRounded(
        override val source: Point,
        override val target: Point,
        val waypoints: List<Point>,
        val cornerRadiusPx: Float,
    ) : EdgeRoute

    /**
     * Baumartiges orthogonales Routing mit abgerundeten Ecken, optimiert für Hierarchie-Diagramme.
     *
     * [waypoints] sind die Knickpunkte zwischen [source] und [target].
     * [cornerRadiusPx] bestimmt den Radius der abgerundeten Ecken in Pixeln.
     */
    @Serializable
    public data class TreeRounded(
        override val source: Point,
        override val target: Point,
        val waypoints: List<Point>,
        val cornerRadiusPx: Float,
    ) : EdgeRoute

    /**
     * Bézierkurve, die durch explizite Kontrollpunkte definiert wird.
     *
     * [controlPoints] sind die Bézierkontrollpunkte (zwischen [source] und [target]).
     */
    @Serializable
    public data class Bezier(
        override val source: Point,
        override val target: Point,
        val controlPoints: List<Point>,
    ) : EdgeRoute
}
