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

    /** Composite states keyed by id, with their (implicit and explicit) regions. */
    internal val regionsByOwner: Map<String, List<dev.kuml.runtime.internal.RegionInfo>> by lazy {
        dev.kuml.runtime.internal
            .buildRegionsByOwner(model)
    }

    /** `true` when the machine declares explicit (orthogonal) regions. */
    internal val hasExplicitRegions: Boolean by lazy { regionsByOwner.values.any { rs -> rs.any { it.explicit } } }

    /** History memory: history pseudostate id → remembered vertex ids (child for shallow, leaves for deep). */
    internal val historyMemory: MutableMap<String, List<String>> = mutableMapOf()

    /** Timers armed for the currently active states. */
    internal val armedTimerList: MutableList<ArmedTimer> = mutableListOf()
    internal var timerSeq: Long = 0

    /** Virtual clock in epoch milliseconds; `null` = wall clock (the runtime's `clock`). */
    internal var virtualNowMs: Long? = null

    override val currentVertices: List<UmlVertex> get() = mutCurrentVertices.toList()
    override val variables: MutableMap<String, Any?> = mutableMapOf()
    override val trace: List<TraceEntry> get() = mutTrace.toList()
    override var isTerminated: Boolean = false
        internal set

    // KumlEvalContext
    override val currentVertexIds: List<String> get() = mutCurrentVertices.map { it.id }
}
