package dev.kuml.core.ocl

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlConstraint
import dev.kuml.uml.UmlInterface

public object OclValidator {
    public fun validate(diagram: KumlDiagram): KumlValidationResult {
        val violations = mutableListOf<KumlViolation>()
        for (element in diagram.elements) {
            when (element) {
                is UmlClass ->
                    violations += validateClassifier(element, element.id, element.name, element.constraints)
                is UmlInterface ->
                    violations += validateClassifier(element, element.id, element.name, element.constraints)
                else -> Unit
            }
        }
        return KumlValidationResult(valid = violations.isEmpty(), violations = violations)
    }

    private fun validateClassifier(
        self: Any,
        classifierId: String,
        classifierName: String,
        constraints: List<UmlConstraint>,
    ): List<KumlViolation> =
        constraints.mapNotNull { c ->
            try {
                val tokens = OclLexer.tokenize(c.body)
                val expr = OclParser(tokens).parse()
                val result = OclEvaluator(self).eval(expr)
                if (result != true) {
                    KumlViolation(
                        constraintId = c.id,
                        constraintName = c.name,
                        classifierId = classifierId,
                        classifierName = classifierName,
                        oclExpression = c.body,
                        message = "Constraint '${c.name}' violated on '$classifierName': evaluated to $result",
                    )
                } else {
                    null
                }
            } catch (e: OclEvaluationException) {
                KumlViolation(
                    constraintId = c.id,
                    constraintName = c.name,
                    classifierId = classifierId,
                    classifierName = classifierName,
                    oclExpression = c.body,
                    message = "Constraint '${c.name}' on '$classifierName' threw: ${e.message}",
                )
            }
        }
}
