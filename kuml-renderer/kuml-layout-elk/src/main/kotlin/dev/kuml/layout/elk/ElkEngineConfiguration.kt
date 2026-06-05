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
     */
    val layerSpacing: Float = 60f,
    /** Mindestabstand zwischen Kanten und Knoten in abstrakten Pixeln. */
    val edgeNodeSpacing: Float = 20f,
    /** Strategie zur Kreuzungsminimierung zwischen den Knotenreihen im Layer-Layout. */
    val crossingMinimizationStrategy: CrossingMinimization = CrossingMinimization.LayerSweep,
) {
    public companion object {
        /** Standard-Konfiguration mit sinnvollen Defaults für UML- und C4-Diagramme. */
        public val DEFAULT: ElkEngineConfiguration = ElkEngineConfiguration()
    }
}
