package dev.kuml.runtime

import dev.kuml.core.model.KumlEvalContext
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlVertex

/**
 * Konkrete `ModelInstance`-Implementierung für State Machines (V1.1.5).
 *
 * Mutabilität: die Felder werden vom `StateMachineRuntime` direkt
 * mutiert — keine Copy-on-Write. Snapshot ist die einzige Konsistenz-Brücke.
 *
 * Implementiert [KumlEvalContext] aus `kuml-core-model`, damit der OCL-
 * Property-Accessor (`UmlPropertyAccessor`) auf Variables und
 * `currentVertexIds` navigieren kann, ohne dass `kuml-core-ocl` eine
 * direkte Dependency auf `kuml-runtime-core` braucht.
 */
public class StateMachineInstance internal constructor(
    override val model: UmlStateMachine,
    internal val parentOf: Map<String, String>,
    internal val vertexById: Map<String, UmlVertex>,
) : ModelInstance<UmlStateMachine>,
    KumlEvalContext {
    internal val mutCurrentVertices: MutableList<UmlVertex> = mutableListOf()
    internal val mutInternalQueue: ArrayDeque<Event> = ArrayDeque()
    internal val mutTrace: MutableList<TraceEntry> = mutableListOf()
    internal var seqCounter: Long = 0

    override val currentVertices: List<UmlVertex> get() = mutCurrentVertices.toList()
    override val variables: MutableMap<String, Any?> = mutableMapOf()
    override val trace: List<TraceEntry> get() = mutTrace.toList()
    override var isTerminated: Boolean = false
        internal set

    // KumlEvalContext
    override val currentVertexIds: List<String> get() = mutCurrentVertices.map { it.id }
}
