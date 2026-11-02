package dev.kuml.sysml2.dsl

import dev.kuml.sysml2.CalcParameter
import dev.kuml.sysml2.CalcParameterDirection
import dev.kuml.sysml2.CalcUsageRef
import dev.kuml.sysml2.CalculationDefinition
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Limits

internal fun requireCalcText(
    what: String,
    value: String,
) {
    require(value.length <= Sysml2Limits.CALC_TEXT) { "$what is longer than ${Sysml2Limits.CALC_TEXT} characters" }
}

internal fun requireCalcCount(
    what: String,
    count: Int,
) {
    require(count <= Sysml2Limits.CALC_ENTRIES) { "more than ${Sysml2Limits.CALC_ENTRIES} $what" }
}

/** Scope of `calcDef("TotalMass") { input("m1", "Mass"); expression("m1 + m2"); returns("total", "Mass") }` (wave 2I-b). */
@Sysml2Dsl
class CalcDefBuilder internal constructor() {
    private val params = mutableListOf<CalcParameter>()
    private var expr = ""
    private var result: CalcParameter? = null

    private fun add(
        name: String,
        type: String,
        direction: CalcParameterDirection,
    ) {
        requireCalcText(what = "parameter name", value = name)
        requireCalcText(what = "parameter type", value = type)
        requireCalcCount(what = "parameters", count = params.size + 1)
        params += CalcParameter(name = name, typeName = type, direction = direction)
    }

    /** `in name : type`. */
    fun input(
        name: String,
        type: String = "",
    ) = add(name = name, type = type, direction = CalcParameterDirection.IN)

    /** `out name : type`. */
    fun output(
        name: String,
        type: String = "",
    ) = add(name = name, type = type, direction = CalcParameterDirection.OUT)

    /** `inout name : type`. */
    fun inout(
        name: String,
        type: String = "",
    ) = add(name = name, type = type, direction = CalcParameterDirection.INOUT)

    /** The raw expression body, e.g. `"m1 + m2"`. */
    fun expression(text: String) {
        requireCalcText(what = "expression", value = text)
        expr = text
    }

    /** `return name : type`. */
    fun returns(
        name: String,
        type: String = "",
    ) {
        requireCalcText(what = "result name", value = name)
        requireCalcText(what = "result type", value = type)
        result = CalcParameter(name = name, typeName = type, direction = CalcParameterDirection.OUT)
    }

    internal fun parameters(): List<CalcParameter> = params.toList()

    internal fun expressionText(): String = expr

    internal fun resultParameter(): CalcParameter? = result
}

/** Scope of `analysisDef("MassAnalysis") { subject(vehicle); objective("..."); calc("total", totalMass) }` (wave 2I-b). */
@Sysml2Dsl
class AnalysisDefBuilder internal constructor() {
    private var subject: String? = null
    private var objectiveText = ""
    private val calcs = mutableListOf<CalcUsageRef>()
    private var result: CalcParameter? = null

    /** The analysed subject (a part definition declared earlier). */
    fun subject(part: PartDefinition) {
        subject = part.id
    }

    /** The analysed subject by id (forward reference). */
    fun subject(id: String) {
        requireCalcText(what = "subject id", value = id)
        subject = id
    }

    /** The objective of the analysis, shown in the `objective` compartment. */
    fun objective(text: String) {
        requireCalcText(what = "objective", value = text)
        objectiveText = text
    }

    /** `calc name : Definition`; the definition must be declared earlier (use the id overload for a forward reference). */
    fun calc(
        name: String,
        definition: CalculationDefinition,
    ) = calc(name = name, id = definition.id)

    /** `calc name : Definition` by definition id. */
    fun calc(
        name: String,
        id: String,
    ) {
        requireCalcText(what = "calc name", value = name)
        requireCalcText(what = "calc definition id", value = id)
        requireCalcCount(what = "calcs", count = calcs.size + 1)
        calcs += CalcUsageRef(name = name, definitionId = id)
    }

    /** `return name : type`. */
    fun returns(
        name: String,
        type: String = "",
    ) {
        requireCalcText(what = "result name", value = name)
        requireCalcText(what = "result type", value = type)
        result = CalcParameter(name = name, typeName = type, direction = CalcParameterDirection.OUT)
    }

    internal fun subjectId(): String? = subject

    internal fun objectiveText(): String = objectiveText

    internal fun calculations(): List<CalcUsageRef> = calcs.toList()

    internal fun resultParameter(): CalcParameter? = result
}
