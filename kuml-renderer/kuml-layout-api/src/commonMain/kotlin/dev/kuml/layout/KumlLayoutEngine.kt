package dev.kuml.layout

/**
 * Hauptschnittstelle einer Layout-Engine.
 *
 * Implementierungen berechnen absolute Positionen und Größen für einen abstrakten
 * Diagrammgraphen. Sie sind **stateless und thread-safe**: es darf kein
 * mutabler Zustand zwischen zwei [layout]-Aufrufen gehalten werden.
 *
 * Engines registrieren sich explizit (keine Reflection) — dies garantiert
 * Native-Image-Kompatibilität (GraalVM).
 *
 * Vertrag:
 * - [layout] ist eine reine Funktion: kein I/O, keine globalen Caches.
 * - Bei `capabilities.deterministic == true`: gleiche Eingabe + gleicher
 *   `hints.deterministicSeed` ⇒ bit-identisches [LayoutResult].
 * - Nicht erfüllbare Hints werden schweigend ignoriert und als [LayoutWarning] gemeldet.
 * - Das Zeitbudget (`hints.timeBudgetMillis`) ist ein weiches Maximum;
 *   bei Überschreitung: bestmögliches Teilergebnis + Warning `time.budget.exceeded`.
 */
public interface KumlLayoutEngine {
    /**
     * Stabile, maschinenlesbare ID dieser Engine, z.B. `"elk.layered"` oder `"kuml.grid"`.
     */
    public val id: LayoutEngineId

    /**
     * Maschinenlesbare Fähigkeiten dieser Engine (Determinismus, Edge-Stile, Diagrammtypen).
     *
     * Renderer und Clients verwenden diese, um die passende Engine zu wählen.
     */
    public val capabilities: LayoutCapabilities

    /**
     * Berechnet das Layout für [graph] gemäß [hints].
     *
     * @param graph Der zu layoutende Diagrammgraph.
     * @param hints Steuerparameter; Defaults in [LayoutHints.DEFAULT].
     * @return Vollständiges Layout-Ergebnis mit absoluten Positionen und Routing-Pfaden.
     */
    public fun layout(
        graph: LayoutGraph,
        hints: LayoutHints = LayoutHints.DEFAULT,
    ): LayoutResult
}
