package dev.kuml.layout

/**
 * Optionaler Post-Prozessor, der rohe Polylines aus einem [LayoutResult] in
 * einen gewünschten [EdgeRoute]-Stil umwandelt.
 *
 * Wird benötigt, wenn eine Engine einen bestimmten Edge-Stil nicht nativ
 * unterstützt (z.B. ELK kann keine Bézierkurven liefern). Der Renderer
 * kann dann einen passenden [KumlEdgeRouter] nachschalten.
 */
public interface KumlEdgeRouter {
    /**
     * Konvertiert eine rohe Liste von Punkten in einen typisierten [EdgeRoute].
     *
     * @param raw Polyline-Punkte aus dem rohen Layout-Ergebnis der Engine.
     * @param style Der gewünschte Ziel-Routing-Stil.
     * @param hints Die Kanten-Hints, die beim Layout-Lauf galten.
     * @return Fertig geroutete Kante im gewünschten Stil.
     */
    public fun route(
        raw: List<Point>,
        style: EdgeRouteStyle,
        hints: EdgeHints,
    ): EdgeRoute
}

/**
 * Aufzählung der unterstützten Kanten-Routing-Stile.
 *
 * Wird in [LayoutHints.defaultEdgeStyle], [LayoutCapabilities.supportedEdgeStyles]
 * und [KumlEdgeRouter.route] verwendet.
 */
public enum class EdgeRouteStyle {
    Direct,
    OrthogonalRounded,
    TreeRounded,
    Bezier,
}
