package dev.kuml.core.ocl

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlConstraint
import dev.kuml.uml.UmlInterface

public object OclValidator {
    /**
     * Parse an OCL expression string for syntax errors without evaluating it.
     *
     * Throws [OclEvaluationException] if the expression has a syntax error.
     * Used by `kuml profile validate` to check profile constraint bodies at
     * profile-self-check time.
     */
    public fun parseOclSyntax(expression: String) {
        val tokens = OclLexer.tokenize(expression)
        OclParser(tokens).parse()
    }

    public fun validate(diagram: KumlDiagram): KumlValidationResult {
        val violations = mutableListOf<KumlViolation>()
        for (element in diagram.elements) {
            when (element) {
                is UmlClass ->
                    violations +=
                        validateClassifier(element, element.id, element.name, element.constraints, diagram.elements)
                is UmlInterface ->
                    violations +=
                        validateClassifier(element, element.id, element.name, element.constraints, diagram.elements)
                else -> Unit
            }
        }
        return KumlValidationResult(valid = violations.isEmpty(), violations = violations)
    }

    /**
     * Validates a single element against a list of OCL constraint expressions.
     *
     * This overload is used by profile-level constraint tests (V1.1 AP-4.4) where
     * the constraints live in a [KumlStereotype] rather than on the element itself.
     *
     * @param self The element to validate (used as OCL `self`).
     * @param elementId Stable identifier for violation messages.
     * @param elementName Display name for violation messages.
     * @param constraintBodies Map of constraint name to OCL expression body.
     * @param model The enclosing model's elements, used to resolve association-end
     *   navigation (`self.assocEnd`) and `closure()` in constraint bodies. Defaults
     *   to empty when no surrounding model is available (e.g. stereotype constraints
     *   evaluated without a `KumlDiagram` in scope).
     */
    public fun validateWithExpressions(
        self: Any,
        elementId: String,
        elementName: String,
        constraintBodies: Map<String, String>,
        model: List<Any> = emptyList(),
    ): KumlValidationResult {
        val constraints =
            constraintBodies.map { (name, body) ->
                UmlConstraint(id = "$elementId::$name", name = name, body = body)
            }
        val violations = validateClassifier(self, elementId, elementName, constraints, model)
        return KumlValidationResult(valid = violations.isEmpty(), violations = violations)
    }

    private fun validateClassifier(
        self: Any,
        classifierId: String,
        classifierName: String,
        constraints: List<UmlConstraint>,
        model: List<Any> = emptyList(),
    ): List<KumlViolation> =
        constraints.mapNotNull { c ->
            try {
                val tokens = OclLexer.tokenize(c.body)
                val expr = OclParser(tokens).parse()
                val result = OclEvaluator(self, model).eval(expr)
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
