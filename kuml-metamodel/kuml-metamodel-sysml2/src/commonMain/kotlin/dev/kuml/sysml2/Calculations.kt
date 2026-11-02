package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/** Direction of a [CalcParameter] (wave 2I-b). */
@Serializable
enum class CalcParameterDirection { IN, OUT, INOUT }

/**
 * One parameter of a [CalculationDefinition] (wave 2I-b). [typeName] is free text and is not resolved against the
 * model (like [ConstraintParameter]).
 */
@Serializable
data class CalcParameter(
    val name: String,
    val typeName: String = "",
    val direction: CalcParameterDirection = CalcParameterDirection.IN,
)

/** A named use of a [CalculationDefinition] inside an [AnalysisCaseDefinition] (`calc total : TotalMass`). */
@Serializable
data class CalcUsageRef(
    val name: String,
    val definitionId: String,
)

/**
 * A named reference to a behavior definition: `perform drive : Drive` (an [ActionDefinition]) or
 * `exhibit charging : Charging` (a [StateDefinition]) on a [PartDefinition].
 */
@Serializable
data class BehaviorRef(
    val name: String,
    val definitionId: String,
)
