package dev.kuml.layout.elk

/**
 * Feinabstimmungs-Parameter für [ElkLayoutEngine].
 *
 * Alle Felder steuern das Verhalten von `elk.layered` und werden beim ersten
 * `layout()`-Aufruf auf den ELK-Graphen angewendet. Änderungen erfordern eine
 * neue Instanz von [ElkLayoutEngine].
 *
 * **Unterstützte Konfiguration:**
 * - Knotenabstände ([nodeSpacing]) und Layer-Abstände ([layerSpacing])
 * - Mindestabstand zwischen Kanten und Knoten ([edgeNodeSpacing])
 * - Kreuzungsminimierungs-Strategie ([crossingMinimizationStrategy])
 *
 * **Nicht konfigurierbar (Out of Scope V1):**
 * - Rundungsradius von Kanten (bleibt 0, Rundung passiert im Renderer)
 * - Grid-Constraints (ELK unterstützt kein Grid-Layout)
 *
 * Spec: `03 Bereiche/kUML/Plan/Phase 1 — ELK-Adapter (Designentwurf).md`
 */
public data class ElkEngineConfiguration(
    /** Mindestabstand zwischen zwei benachbarten Knoten in abstrakten Pixeln. */
    val nodeSpacing: Float = 40f,
    /**
     * Abstand zwischen den Layern (Schichten) im Sugiyama-Layout in abstrakten Pixeln.
     * Entspricht `SPACING_NODE_NODE_BETWEEN_LAYERS` in ELK layered.
     *
     * V11.x — von 60 auf 90 angehoben: 60 px reichten bei C4-Context-Diagrammen
     * mit beidseitigen Edge-Labels nicht aus; ein ~14 px hohes Label rechts der
     * vertikalen Kante landete entweder an der Source- oder Target-Unterkante.
     * 90 px gibt dem Label-Mittelpunkt etwa 38–40 px freien Raum zu jedem
     * angrenzenden Knoten, womit auch zweizeilige Labels mit Halo passen.
     */
    val layerSpacing: Float = 90f,
    /**
     * Mindestabstand zwischen Kanten und Knoten in abstrakten Pixeln.
     *
     * V11.x — von 20 auf 30 angehoben: gibt ELK mehr Spielraum, paralleler
     * verlaufende Edges aus den Knoten-Rändern heraus zu ziehen, bevor sie
     * sich treffen. Reduziert dichte Label-Cluster direkt an der Source-/
     * Target-Kante.
     */
    val edgeNodeSpacing: Float = 30f,
    /** Strategie zur Kreuzungsminimierung zwischen den Knotenreihen im Layer-Layout. */
    val crossingMinimizationStrategy: CrossingMinimization = CrossingMinimization.LayerSweep,
) {
    public companion object {
        /** Standard-Konfiguration mit sinnvollen Defaults für UML- und C4-Diagramme. */
        public val DEFAULT: ElkEngineConfiguration = ElkEngineConfiguration()
    }
}
