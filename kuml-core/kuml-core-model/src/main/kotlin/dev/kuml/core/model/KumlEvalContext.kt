package dev.kuml.core.model

/**
 * Marker für Objekte, die als `self` an einen OCL-Evaluator gereicht werden
 * und Laufzeit-Felder anbieten (Variables, aktuelle Vertex-IDs, Termination).
 *
 * Wird in V1.1.5 von `dev.kuml.runtime.StateMachineInstance` implementiert.
 * Ermöglicht dem OCL-Property-Accessor die Navigation auf Runtime-Zustand,
 * ohne dass `kuml-core-ocl` auf `kuml-runtime-core` zeigen muss.
 */
public interface KumlEvalContext {
    public val variables: Map<String, Any?>
    public val currentVertexIds: List<String>
    public val isTerminated: Boolean
}
