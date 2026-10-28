package dev.kuml.runtime.sysml2

import dev.kuml.runtime.Event
import dev.kuml.runtime.ProseGuardPolicy
import dev.kuml.runtime.StepResult
import dev.kuml.runtime.StmRuntimeLimits
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import java.time.Instant

/** Shared helpers of the wave-A2 runtime tests. */
internal fun Sysml2Model.stm(): StmDiagram = diagrams.filterIsInstance<StmDiagram>().first()

internal fun Sysml2Model.start(
    startTime: Instant? = null,
    prose: ProseGuardPolicy = ProseGuardPolicy.Block,
    limits: StmRuntimeLimits = StmRuntimeLimits.DEFAULT,
): Sysml2RuntimeHandle =
    Sysml2StateMachineAdapter.runtimeFor(model = this, diagram = stm(), startTime = startTime, proseGuards = prose, limits = limits)

internal fun Sysml2RuntimeHandle.active(): Set<String> = instance.currentVertexIds.toSet()

internal fun Sysml2RuntimeHandle.send(name: String): StepResult = runtime.step(instance = instance, event = Event.of(name))

internal fun Sysml2RuntimeHandle.traceNames(): List<String> = instance.trace.map { it::class.simpleName ?: "?" }
