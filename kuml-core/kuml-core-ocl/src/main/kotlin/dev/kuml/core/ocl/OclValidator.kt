package dev.kuml.core.ocl

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.core.model.KumlDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlConstraint
import dev.kuml.uml.UmlConstraintKind
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
     * Validates the OCL invariants declared on every [BpmnModel] process
     * (V3.2.23 — `BpmnProcess.constraints`).
     *
     * `self` is bound to the [BpmnModel]'s process; navigation resolves via
     * [BpmnPropertyAccessor] (analogous to [UmlPropertyAccessor] for the UML
     * branch). `model` for association-style navigation is the process's own
     * flow nodes + sequence flows — BPMN processes have no cross-process
     * associations comparable to UML's [dev.kuml.uml.UmlAssociation], so the
     * navigation model is scoped per-process rather than model-wide.
     */
    public fun validateBpmn(model: BpmnModel): KumlValidationResult {
        val violations = mutableListOf<KumlViolation>()
        for (process in model.processes) {
            if (process.constraints.isEmpty()) continue
            violations +=
                validateClassifier(
                    self = process,
                    classifierId = process.id,
                    classifierName = process.name ?: process.id,
                    constraints = process.constraints,
                    model = process.flowNodes + process.sequenceFlows,
                )
        }
        return KumlValidationResult(valid = violations.isEmpty(), violations = violations)
    }

    /**
     * Validates the OCL invariants declared on every [Sysml2Model]
     * [PartDefinition] (V3.2.23 — `PartDefinition.constraints`).
     *
     * `self` is bound to the [PartDefinition]; navigation resolves via
     * [Sysml2PropertyAccessor]. Coexists with the PAR
     * [dev.kuml.sysml2.constraint.Sysml2ConstraintChecker] path — that one
     * type-checks parametric equations bound via `BindingConnectorUsage`s,
     * this one evaluates classifier-scoped OCL invariants against `self`.
     */
    public fun validateSysml2(model: Sysml2Model): KumlValidationResult {
        val violations = mutableListOf<KumlViolation>()
        for (def in model.definitions.filterIsInstance<PartDefinition>()) {
            if (def.constraints.isEmpty()) continue
            violations +=
                validateClassifier(
                    self = def,
                    classifierId = def.id,
                    classifierName = def.name,
                    constraints = def.constraints,
                    model = model.definitions + model.usages,
                )
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
    ): List<KumlViolation> {
        val operations =
            when (self) {
                is UmlClass -> self.operations
                is UmlInterface -> self.operations
                else -> emptyList()
            }

        // `def:` constraints are reusable named helpers, not assertions — evaluate
        // them once up front and bind their value into the shared environment so
        // later `inv:`/`pre:`/`post:`/`body:` constraints in the same classifier can
        // reference them by name (`self`-scoped, `let`-like visibility).
        val defs = constraints.filter { it.kind == UmlConstraintKind.Definition }
        val defEnv = mutableMapOf<String, Any?>("self" to self)
        val defViolations = mutableListOf<KumlViolation>()
        for (d in defs) {
            try {
                val (tokens, positions) = OclLexer.tokenizeWithPositions(d.body)
                val expr = OclParser(tokens, positions).parse()
                defEnv[d.name] = OclEvaluator(self, model).eval(expr, defEnv.toMap())
            } catch (e: OclEvaluationException) {
                defViolations +=
                    KumlViolation(
                        constraintId = d.id,
                        constraintName = d.name,
                        classifierId = classifierId,
                        classifierName = classifierName,
                        oclExpression = d.body,
                        message = "Definition '${d.name}' on '$classifierName' threw: ${e.message}",
                        sourcePosition = e.position ?: FALLBACK_POSITION,
                    )
            }
        }

        val assertions = constraints.filter { it.kind != UmlConstraintKind.Definition }
        return defViolations +
            assertions.mapNotNull { c ->
                // `pre:`/`post:`/`body:` are operation-scoped and require a matching
                // `contextOperation` on the classifier — structural check first, since
                // there is no operation to evaluate against otherwise.
                val requiresOperation =
                    c.kind == UmlConstraintKind.Precondition ||
                        c.kind == UmlConstraintKind.Postcondition ||
                        c.kind == UmlConstraintKind.Body
                if (requiresOperation && operations.none { it.name == c.contextOperation }) {
                    return@mapNotNull KumlViolation(
                        constraintId = c.id,
                        constraintName = c.name,
                        classifierId = classifierId,
                        classifierName = classifierName,
                        oclExpression = c.body,
                        message =
                            "${c.kind} constraint '${c.name}' on '$classifierName' references unknown " +
                                "operation '${c.contextOperation}'",
                    )
                }
                evaluateAssertion(self, classifierId, classifierName, c, defEnv, model)
            }
    }

    /**
     * Evaluates a single `inv:`/`pre:`/`post:`/`body:` constraint, extending the
     * shared `def:` environment with the two `post:`/`body:`-only bindings:
     *
     * - `result` — the operation's return value. This evaluator has no
     *   operation-call runtime (no actual invocation happens during model
     *   validation), so `result` is bound to `null` here; constraint authors
     *   writing `body:`/`post:` contracts against a *runtime* executor (e.g. a
     *   future guard/interpreter) get real binding there — see
     *   [dev.kuml.core.ocl.OclEvaluator]'s `result`/`@pre` KDoc.
     * - `expr@pre` — resolved by [OclEvaluator] itself (no explicit env entry
     *   needed here); with no distinct pre-state available in static
     *   validation, `@pre` snapshots the current (only) state, i.e. is a no-op.
     */
    private fun evaluateAssertion(
        self: Any,
        classifierId: String,
        classifierName: String,
        c: UmlConstraint,
        defEnv: Map<String, Any?>,
        model: List<Any>,
    ): KumlViolation? =
        try {
            val (tokens, positions) = OclLexer.tokenizeWithPositions(c.body)
            val expr = OclParser(tokens, positions).parse()
            val bindsResult = c.kind == UmlConstraintKind.Postcondition || c.kind == UmlConstraintKind.Body
            val env = if (bindsResult) defEnv + mapOf("result" to null) else defEnv
            val result = OclEvaluator(self, model).eval(expr, env)
            if (result != true) {
                KumlViolation(
                    constraintId = c.id,
                    constraintName = c.name,
                    classifierId = classifierId,
                    classifierName = classifierName,
                    oclExpression = c.body,
                    message = "Constraint '${c.name}' violated on '$classifierName': evaluated to $result",
                    sourcePosition = FALLBACK_POSITION,
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
                sourcePosition = e.position ?: FALLBACK_POSITION,
            )
        }

    /**
     * Fallback [OclPosition] used when a violation has no token-level position
     * (e.g. a successfully-parsed constraint that evaluated to non-`true` — the
     * failure is semantic, not syntactic, so there is no single failing token).
     * Points at the start of the constraint body.
     */
    private val FALLBACK_POSITION = OclPosition(line = 1, col = 1)
}
