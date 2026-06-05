package dev.kuml.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Kern-Interface für Verhaltens-Interpreter.
 *
 * V1.1.5 hat eine Implementierung: `StateMachineRuntime` (Ticket 2).
 * V2 ergänzt `ActivityRuntime` mit der gleichen Schnittstelle.
 *
 * @param M Konkreter Modell-Typ (z.B. `UmlStateMachine`).
 * @param I Konkreter Instanz-Typ (z.B. `StateMachineInstance`).
 */
public interface BehaviourInterpreter<M : Any, I : ModelInstance<M>> {
    /**
     * Erzeugt eine neue Instanz und führt Initial-Pseudostate + entry-Actions aus.
     * Tritt **kein** Default-Initial auf, wirft die Implementierung mit klarer Meldung.
     */
    public fun start(model: M): I

    /**
     * Verarbeitet das Event auf der Instanz nach Run-to-Completion-Semantik.
     * Interne Events (von Actions gepostet) werden vor Rückkehr abgearbeitet.
     */
    public fun step(
        instance: I,
        event: Event,
    ): StepResult

    /**
     * Liefert einen serialisierbaren Snapshot des aktuellen Zustands.
     */
    public fun snapshot(instance: I): Snapshot

    /** Stellt eine Instanz aus einem Snapshot wieder her. */
    public fun restore(
        model: M,
        snapshot: Snapshot,
    ): I
}

/**
 * Opaker Snapshot — konkretes Layout in [BehaviourInterpreter.snapshot].
 */
@Serializable
public data class Snapshot(
    public val currentVertexIds: List<String>,
    public val variables: Map<String, JsonElement>,
    public val traceSeqNo: Long,
)
