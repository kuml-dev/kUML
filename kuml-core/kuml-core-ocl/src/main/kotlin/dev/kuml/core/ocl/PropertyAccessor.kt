package dev.kuml.core.ocl

import dev.kuml.bpmn.model.BpmnElement
import dev.kuml.sysml2.PartDefinition

/**
 * Dispatches `self.prop` navigation to the metamodel-specific accessor that
 * matches [self]'s runtime type (V3.2.23).
 *
 * [OclEvaluator] previously called [UmlPropertyAccessor] directly (the only
 * metamodel `OclValidator` supported). Now that BPMN and SysML 2 also have
 * OCL branches ([OclValidator.validateBpmn] / [OclValidator.validateSysml2]),
 * this dispatcher routes by receiver type before falling back to the UML
 * accessor — which remains the default because it is by far the most common
 * receiver type and the one every other accessor's "not found" sentinel
 * ultimately falls through to (matching pre-V3.2.23 behaviour byte-for-byte
 * for UML models).
 */
internal object PropertyAccessor {
    internal fun get(
        self: Any,
        prop: String,
        model: List<Any> = emptyList(),
    ): Any? =
        when {
            self is BpmnElement -> {
                val resolved = BpmnPropertyAccessor.get(self, prop)
                if (resolved !== BpmnPropertyAccessor.NOT_FOUND) {
                    resolved
                } else {
                    throw OclEvaluationException("Cannot navigate property '$prop' on ${self::class.simpleName}")
                }
            }
            self is PartDefinition -> {
                val resolved = Sysml2PropertyAccessor.get(self, prop)
                if (resolved !== Sysml2PropertyAccessor.NOT_FOUND) {
                    resolved
                } else {
                    throw OclEvaluationException("Cannot navigate property '$prop' on ${self::class.simpleName}")
                }
            }
            // KermlFeature results from a prior Sysml2PropertyAccessor navigation —
            // keep resolving through the same accessor for chained navigation
            // (e.g. `self.mass.multiplicity`).
            self is dev.kuml.kerml.KermlFeature -> {
                val resolved = Sysml2PropertyAccessor.get(self, prop)
                if (resolved !== Sysml2PropertyAccessor.NOT_FOUND) {
                    resolved
                } else {
                    throw OclEvaluationException("Cannot navigate property '$prop' on ${self::class.simpleName}")
                }
            }
            else -> UmlPropertyAccessor.get(self, prop, model)
        }
}
