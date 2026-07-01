package dev.kuml.bpmn.model

import kotlinx.serialization.Serializable

/** Describes the loop / multi-instance behaviour of a BPMN activity. */
@Serializable
sealed interface LoopCharacteristics

/**
 * A standard loop that repeats while (or until) a condition holds.
 *
 * @property testBefore When `true`, the condition is evaluated before executing the activity body.
 * @property loopCondition Optional boolean expression that governs the loop.
 */
@Serializable
data class StandardLoop(
    val testBefore: Boolean = false,
    val loopCondition: String? = null,
) : LoopCharacteristics

/**
 * A multi-instance loop that creates parallel or sequential instances of an activity.
 *
 * @property sequential When `true`, instances run one after the other; when `false` they run in parallel.
 * @property cardinality Optional expression that resolves to the number of instances.
 */
@Serializable
data class MultiInstanceLoop(
    val sequential: Boolean = false,
    val cardinality: String? = null,
) : LoopCharacteristics
