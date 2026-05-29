package io.kuml.core.model

/** Root sealed interface for all elements in a kUML model. */
sealed interface KumlElement {
    /** Stabile ID für Traceability & Diff. Phase 0: Platzhalter; Phase 1 definiert die ID-Strategie. */
    val id: String
    val metadata: Map<String, Any>
}
