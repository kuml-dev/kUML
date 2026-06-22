package dev.kuml.bpmn.dsl

import dev.kuml.bpmn.model.LoopCharacteristics
import dev.kuml.bpmn.model.MultiInstanceLoop
import dev.kuml.bpmn.model.StandardLoop

/**
 * Builder for configuring an individual BPMN task inside a [ProcessBuilder] block.
 *
 * Allows attaching loop characteristics and boundary event references to a task
 * via a type-safe DSL. Instances are created and consumed by [ProcessBuilder.task].
 */
@BpmnDsl
class TaskBuilder {
    internal var loopCharacteristics: LoopCharacteristics? = null
    internal val boundaryEventIds: MutableList<String> = mutableListOf()

    /**
     * Attach a standard loop marker to this task.
     *
     * @param testBefore When `true`, the condition is evaluated before executing the body (do-while style).
     * @param condition Optional boolean expression that governs the loop.
     */
    fun standardLoop(
        testBefore: Boolean = false,
        condition: String? = null,
    ) {
        loopCharacteristics = StandardLoop(testBefore = testBefore, loopCondition = condition)
    }

    /**
     * Attach a multi-instance loop marker to this task.
     *
     * @param sequential When `true`, instances run one after the other; when `false` they run in parallel.
     * @param cardinality Optional expression that resolves to the number of instances.
     */
    fun multiInstance(
        sequential: Boolean = false,
        cardinality: String? = null,
    ) {
        loopCharacteristics = MultiInstanceLoop(sequential = sequential, cardinality = cardinality)
    }

    /**
     * Register a boundary event (by id) as attached to this task.
     *
     * @param eventId The id of the boundary event (as returned by [ProcessBuilder.boundaryEvent]).
     */
    fun boundaryEvent(eventId: String) {
        boundaryEventIds += eventId
    }
}
