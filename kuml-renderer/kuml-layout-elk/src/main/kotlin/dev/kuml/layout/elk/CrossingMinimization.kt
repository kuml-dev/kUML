package dev.kuml.layout.elk

/**
 * Strategie zur Kreuzungsminimierung im `elk.layered`-Algorithmus.
 *
 * Steuert, wie ELK Knotenreihenfolgen innerhalb eines Layers optimiert, um
 * Kantenkreuzungen zu minimieren.
 *
 * Spec: `03 Bereiche/kUML/Plan/Phase 1 — ELK-Adapter (Designentwurf).md`
 */
public enum class CrossingMinimization {
    /**
     * Standard-Layer-Sweep-Heuristik (ELK: `LAYER_SWEEP`).
     *
     * Wählt bei den meisten Graphen die beste Balance zwischen Qualität und Laufzeit.
     * Entspricht `CrossingMinimizationStrategy.LAYER_SWEEP` in ELK.
     */
    LayerSweep,

    /**
     * Interaktive Kreuzungsminimierung (ELK: `INTERACTIVE`).
     *
     * Respektiert bestehende Reihenfolgen der Knoten; nützlich wenn die initiale
     * Knotenposition für den Nutzer sinnvoll ist. Entspricht
     * `CrossingMinimizationStrategy.INTERACTIVE` in ELK.
     */
    Interactive,
}
