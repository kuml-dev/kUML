package dev.kuml.runtime

import dev.kuml.uml.UmlVertex

/**
 * Laufzeit-Zustand einer Verhaltens-Instanz.
 *
 * Die konkrete Implementierung (`StateMachineInstance`) lebt in Ticket 2.
 * In V1.1.5 ist `M` immer eine Sub-Klasse der UML-State-Machine-Familie.
 */
public interface ModelInstance<M : Any> {
    /** Das Modell, von dem diese Instanz interpretiert wird. */
    public val model: M

    /**
     * Aktive Vertices: bei Composite-States enthält die Liste alle Ebenen
     * (z.B. `[Processing, Picking]` wenn man im Sub-State `Picking` von
     * `Processing` steht). `[]` = noch nicht gestartet oder bereits terminiert.
     */
    public val currentVertices: List<UmlVertex>

    /** Modifizierbarer Variablen-Scope (für Guards und V2-Actions). */
    public val variables: MutableMap<String, Any?>

    /** Bisheriger Trace dieser Instanz. */
    public val trace: List<TraceEntry>

    /** True, wenn ein `UmlFinalState` erreicht wurde. */
    public val isTerminated: Boolean
}
