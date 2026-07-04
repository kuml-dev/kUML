package dev.kuml.core.script.interpreter

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.Visibility
import dev.kuml.uml.dsl.AssociationBuilder
import dev.kuml.uml.dsl.AssociationEndBuilder
import dev.kuml.uml.dsl.ClassBuilder
import dev.kuml.uml.dsl.ClassDiagramBuilder
import dev.kuml.uml.dsl.EnumerationBuilder
import dev.kuml.uml.dsl.InterfaceBuilder
import dev.kuml.uml.dsl.OperationBuilder
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.comment
import dev.kuml.uml.dsl.constraint
import dev.kuml.uml.dsl.dependency
import dev.kuml.uml.dsl.enumOf
import dev.kuml.uml.dsl.extends
import dev.kuml.uml.dsl.generalization
import dev.kuml.uml.dsl.implements
import dev.kuml.uml.dsl.interfaceOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.realization

/**
 * Thrown when the interpreter meets a construct that has no production rule /
 * no allowlisted builder. Carries a 1-based [line] for diagnostics.
 */
internal class DslInterpretException(
    override val message: String,
    val line: Int,
) : RuntimeException(message)

/**
 * Interprets a parsed [DslScript] by driving the **real** kUML DSL builders
 * (`ClassDiagramBuilder`, `classOf`, `enumOf`, …) — never compiling or executing
 * any bytecode from the script.
 *
 * ## The security guarantee, made concrete
 *
 * The set of things this interpreter can *do* is precisely the set of `when`
 * branches in [dispatchStatement] / [callBuilderHandle]. There is no reflective
 * dispatch, no `Class.forName`, no way to reach `java.lang.Runtime` — a script
 * naming those simply fails to parse (no grammar) or fails here with an
 * "unknown builder" error (no allowlist entry). RCE is therefore *structurally*
 * impossible on this path, not filtered.
 *
 * ## Scope of coverage (Welle 9 initial slice — honest limits)
 *
 * Covers **UML class diagrams** only:
 *   `classDiagram`, `classOf`, `interfaceOf`, `enumOf` (+ `literal`),
 *   `attribute`, `operation` (+ `parameter`, `returns`), `constraint`,
 *   `association` (+ `source`/`target`/`multiplicity`/`role`/`navigable`),
 *   `generalization`, `realization`, `dependency`, `extends`, `implements`,
 *   `comment`, and the `Visibility` / `AggregationKind` enums.
 *
 * Any other top-level diagram (`c4Model`, `sysml2Model`, `bpmnModel`,
 * `blueprint`, `useCaseDiagram`, `stateDiagram`, …) is rejected with a clear
 * "not supported by the interpreter mode; use --eval-strategy=compiler" error.
 *
 * V0.23.3 — Welle 9.
 */
internal object DslInterpreter {
    /** The single allowlisted top-level entry point for this slice. */
    private const val ENTRY_CLASS_DIAGRAM = "classDiagram"

    /**
     * Top-level diagram builders the interpreter *recognises the name of* but
     * does not yet support — so it can produce a specific, actionable error
     * instead of a generic "unknown builder". Anything not in either list is
     * simply unknown.
     */
    private val KNOWN_UNSUPPORTED_ENTRIES =
        setOf(
            "diagram",
            "c4Model",
            "sysml2Model",
            "bpmnModel",
            "blueprint",
            "useCaseDiagram",
            "stateDiagram",
            "sequenceDiagram",
            "activityDiagram",
            "componentDiagram",
            "packageDiagram",
            "objectDiagram",
            "deploymentDiagram",
            "compositeStructureDiagram",
            "profileDiagram",
            "communicationDiagram",
            "timingDiagram",
            "interactionOverviewDiagram",
            "userJourney",
        )

    /** Interprets [script] into a [KumlDiagram] (UML class diagram). */
    fun interpret(script: DslScript): KumlDiagram {
        val root = script.root
        if (root.name != ENTRY_CLASS_DIAGRAM) {
            val hint =
                if (root.name in KNOWN_UNSUPPORTED_ENTRIES) {
                    "'${root.name}' is a valid kUML diagram type but is not supported by the interpreter mode " +
                        "(Welle 9 covers UML class diagrams only). Use --eval-strategy=compiler for this diagram type."
                } else {
                    "Unknown top-level builder '${root.name}'. The interpreter mode currently only supports " +
                        "'classDiagram(...)'."
                }
            throw DslInterpretException(hint, root.line)
        }

        val name = requireStringArg(root, "name", positionalIndex = 0)
        val builder = ClassDiagramBuilder(name = name)
        val env = Env()
        root.body?.forEach { stmt -> interpretDiagramStatement(builder, env, stmt) }
        return builder.build()
    }

    // ── Environment ────────────────────────────────────────────────────────────

    /** `val` bindings: name → the built handle (UmlClass/UmlInterface/UmlEnumeration). */
    private class Env {
        private val bindings = mutableMapOf<String, Any>()

        fun bind(
            name: String,
            value: Any,
            line: Int,
        ) {
            if (bindings.containsKey(name)) {
                throw DslInterpretException("Duplicate 'val $name' — re-binding is not allowed", line)
            }
            bindings[name] = value
        }

        fun resolve(
            name: String,
            line: Int,
        ): Any =
            bindings[name]
                ?: throw DslInterpretException("Unknown reference '$name' — not a declared 'val'", line)
    }

    // ── Diagram-scope statements ─────────────────────────────────────────────────

    private fun interpretDiagramStatement(
        builder: ClassDiagramBuilder,
        env: Env,
        stmt: DslStatement,
    ) {
        when (stmt) {
            is DslValBinding -> {
                val handle = callDiagramBuilder(builder, env, stmt.value)
                env.bind(stmt.name, handle, stmt.line)
            }
            is DslCallStatement -> callDiagramBuilder(builder, env, stmt.call)
            is DslPropertyAssignment -> applyDiagramProperty(builder, stmt)
        }
    }

    /** Diagram-level display-option assignments (`showOperations = false`, …). */
    private fun applyDiagramProperty(
        builder: ClassDiagramBuilder,
        stmt: DslPropertyAssignment,
    ) {
        when (stmt.property) {
            "showAttributes" -> builder.showAttributes = asBool(stmt.value, stmt.line)
            "showOperations" -> builder.showOperations = asBool(stmt.value, stmt.line)
            "showVisibility" -> builder.showVisibility = asBool(stmt.value, stmt.line)
            "showPackageNames" -> builder.showPackageNames = asBool(stmt.value, stmt.line)
            "mergeEdges" -> builder.mergeEdges = asBool(stmt.value, stmt.line)
            else ->
                throw DslInterpretException(
                    "Unknown diagram property '${stmt.property}' — supported: showAttributes, showOperations, " +
                        "showVisibility, showPackageNames, mergeEdges",
                    stmt.line,
                )
        }
    }

    /**
     * Dispatches a call in the *diagram* scope. Returns the built handle for
     * `val` bindings (UmlClass etc.); relationship builders return [Unit].
     */
    private fun callDiagramBuilder(
        builder: ClassDiagramBuilder,
        env: Env,
        call: DslCall,
    ): Any =
        when (call.name) {
            "classOf" -> {
                val nm = requireStringArg(call, "name", 0)
                val explicitId = optStringArg(call, "id")
                builder.classOf(name = nm, id = explicitId) {
                    call.body?.forEach { s -> interpretClassStatement(this, env, s) }
                }
            }
            "interfaceOf" -> {
                val nm = requireStringArg(call, "name", 0)
                val explicitId = optStringArg(call, "id")
                builder.interfaceOf(name = nm, id = explicitId) {
                    call.body?.forEach { s -> interpretInterfaceStatement(this, env, s) }
                }
            }
            "enumOf" -> {
                val nm = requireStringArg(call, "name", 0)
                val explicitId = optStringArg(call, "id")
                builder.enumOf(name = nm, id = explicitId) {
                    call.body?.forEach { s -> interpretEnumStatement(this, s) }
                }
            }
            "association" -> {
                buildAssociation(builder, env, call)
                Unit
            }
            "generalization" -> {
                val specific = requireClassifierArg(env, call, "specific", 0)
                val general = requireClassifierArg(env, call, "general", 1)
                builder.generalization(specific = specific, general = general)
                Unit
            }
            "realization" -> {
                val impl = requireClassifierArg(env, call, "implementing", 0)
                val iface = requireInterfaceArg(env, call, "iface", 1)
                builder.realization(implementing = impl, iface = iface)
                Unit
            }
            "dependency" -> {
                val client = requireClassifierArg(env, call, "client", 0)
                val supplier = requireClassifierArg(env, call, "supplier", 1)
                val nm = optStringArg(call, "name")
                builder.dependency(client = client, supplier = supplier, name = nm)
                Unit
            }
            "comment" -> {
                buildComment(builder, env, call)
                Unit
            }
            else -> throw unknownBuilder(call, "the class-diagram scope")
        }

    // ── Class body ───────────────────────────────────────────────────────────────

    private fun interpretClassStatement(
        cls: ClassBuilder,
        env: Env,
        stmt: DslStatement,
    ) {
        when (stmt) {
            is DslValBinding ->
                throw DslInterpretException(
                    "'val' bindings are not supported inside a class body",
                    stmt.line,
                )
            is DslPropertyAssignment -> applyClassProperty(cls, stmt)
            is DslCallStatement -> callClassMember(cls, env, stmt.call)
        }
    }

    private fun applyClassProperty(
        cls: ClassBuilder,
        stmt: DslPropertyAssignment,
    ) {
        when (stmt.property) {
            "isAbstract" -> cls.isAbstract = asBool(stmt.value, stmt.line)
            "visibility" -> cls.visibility = asVisibility(stmt.value, stmt.line)
            else ->
                throw DslInterpretException(
                    "Unknown class property '${stmt.property}' — supported: isAbstract, visibility",
                    stmt.line,
                )
        }
    }

    private fun callClassMember(
        cls: ClassBuilder,
        env: Env,
        call: DslCall,
    ) {
        when (call.name) {
            "attribute" -> addAttribute(cls, env, call)
            "operation" -> addOperation(cls, call)
            "constraint" -> addConstraint(cls, call)
            "extends" -> cls.extends(requireClassifierArg(env, call, "general", 0))
            "implements" -> cls.implements(requireInterfaceArg(env, call, "iface", 0))
            else -> throw unknownBuilder(call, "a class body")
        }
    }

    // ── Interface body (subset of class body) ────────────────────────────────────

    private fun interpretInterfaceStatement(
        iface: InterfaceBuilder,
        env: Env,
        stmt: DslStatement,
    ) {
        when (stmt) {
            is DslValBinding ->
                throw DslInterpretException("'val' bindings are not supported inside an interface body", stmt.line)
            is DslPropertyAssignment ->
                throw DslInterpretException(
                    "Unknown interface property '${stmt.property}'",
                    stmt.line,
                )
            is DslCallStatement -> {
                val call = stmt.call
                when (call.name) {
                    "attribute" -> addAttribute(iface, env, call)
                    "operation" -> addOperation(iface, call)
                    "constraint" -> addConstraint(iface, call)
                    else -> throw unknownBuilder(call, "an interface body")
                }
            }
        }
    }

    // ── Enum body ────────────────────────────────────────────────────────────────

    private fun interpretEnumStatement(
        enum: EnumerationBuilder,
        stmt: DslStatement,
    ) {
        when (stmt) {
            is DslCallStatement -> {
                val call = stmt.call
                if (call.name != "literal") throw unknownBuilder(call, "an enum body (only 'literal' is allowed)")
                val nm = requireStringArg(call, "name", 0)
                enum.literal(name = nm, id = optStringArg(call, "id"))
            }
            else -> throw DslInterpretException("Only 'literal(...)' calls are allowed inside an enum body", 0)
        }
    }

    // ── Attribute / operation / constraint (shared by class + interface) ──────────

    private fun addAttribute(
        scope: dev.kuml.uml.dsl.UmlClassifierScope,
        env: Env,
        call: DslCall,
    ) {
        val nm = requireStringArg(call, "name", 0)
        // `type` may be a String literal OR a reference to an enumOf handle.
        val typeArg = arg(call, "type", 1) ?: throw missingArg(call, "type")
        val visibility = optVisibilityArg(call, "visibility") ?: Visibility.PRIVATE
        val default = optStringArg(call, "defaultValue")
        val isStatic = optBoolArg(call, "isStatic") ?: false
        val isReadOnly = optBoolArg(call, "isReadOnly") ?: false

        when (val v = typeArg.value) {
            is DslString ->
                scope.attribute(
                    name = nm,
                    type = v.value,
                    visibility = visibility,
                    defaultValue = default,
                    isStatic = isStatic,
                    isReadOnly = isReadOnly,
                )
            is DslIdentifier -> {
                val handle = env.resolve(v.name, v.line)
                val enumHandle =
                    handle as? UmlEnumeration
                        ?: (handle as? UmlClassifier)
                        ?: throw DslInterpretException(
                            "Attribute 'type = ${v.name}' must reference an enumOf/classifier handle",
                            v.line,
                        )
                scope.attribute(
                    name = nm,
                    type = enumHandle,
                    visibility = visibility,
                    defaultValue = default,
                    isStatic = isStatic,
                    isReadOnly = isReadOnly,
                )
            }
            else ->
                throw DslInterpretException(
                    "Attribute 'type' must be a string literal or a classifier handle reference",
                    call.line,
                )
        }
    }

    private fun addOperation(
        scope: dev.kuml.uml.dsl.UmlClassifierScope,
        call: DslCall,
    ) {
        val nm = requireStringArg(call, "name", 0)
        scope.operation(name = nm) {
            call.body?.forEach { s -> interpretOperationStatement(this, s) }
        }
    }

    private fun interpretOperationStatement(
        op: OperationBuilder,
        stmt: DslStatement,
    ) {
        when (stmt) {
            is DslPropertyAssignment -> {
                when (stmt.property) {
                    "visibility" -> op.visibility = asVisibility(stmt.value, stmt.line)
                    "isAbstract" -> op.isAbstract = asBool(stmt.value, stmt.line)
                    "isStatic" -> op.isStatic = asBool(stmt.value, stmt.line)
                    else -> throw DslInterpretException("Unknown operation property '${stmt.property}'", stmt.line)
                }
            }
            is DslCallStatement -> {
                val call = stmt.call
                when (call.name) {
                    "parameter" -> {
                        val pName = requireStringArg(call, "name", 0)
                        val pType = requireStringArg(call, "type", 1)
                        op.parameter(name = pName, type = pType)
                    }
                    "returns" -> {
                        val tn = requireStringArg(call, "typeName", 0)
                        op.returns(typeName = tn)
                    }
                    else -> throw unknownBuilder(call, "an operation body (only 'parameter'/'returns' allowed)")
                }
            }
            is DslValBinding ->
                throw DslInterpretException("'val' bindings are not supported inside an operation body", stmt.line)
        }
    }

    private fun addConstraint(
        scope: dev.kuml.uml.dsl.UmlClassifierScope,
        call: DslCall,
    ) {
        val nm = requireStringArg(call, "name", 0)
        val body = requireStringArg(call, "body", 1)
        scope.constraint(name = nm, body = body)
    }

    // ── Association ──────────────────────────────────────────────────────────────

    private fun buildAssociation(
        builder: ClassDiagramBuilder,
        env: Env,
        call: DslCall,
    ) {
        val source = requireClassifierArg(env, call, "source", 0)
        val target = requireClassifierArg(env, call, "target", 1)
        builder.association(source = source, target = target) {
            call.body?.forEach { s -> interpretAssociationStatement(this, s) }
        }
    }

    private fun interpretAssociationStatement(
        assoc: AssociationBuilder,
        stmt: DslStatement,
    ) {
        when (stmt) {
            is DslPropertyAssignment -> {
                when (stmt.property) {
                    "name" -> assoc.name = asString(stmt.value, stmt.line)
                    "aggregation" -> assoc.aggregation = asAggregation(stmt.value, stmt.line)
                    else -> throw DslInterpretException("Unknown association property '${stmt.property}'", stmt.line)
                }
            }
            is DslCallStatement -> {
                val call = stmt.call
                when (call.name) {
                    "source" -> assoc.source { applyEnd(this, call) }
                    "target" -> assoc.target { applyEnd(this, call) }
                    else -> throw unknownBuilder(call, "an association body (only 'source'/'target' allowed)")
                }
            }
            is DslValBinding ->
                throw DslInterpretException("'val' bindings are not supported inside an association body", stmt.line)
        }
    }

    private fun applyEnd(
        end: AssociationEndBuilder,
        call: DslCall,
    ) {
        call.body?.forEach { s ->
            when (s) {
                is DslPropertyAssignment ->
                    when (s.property) {
                        "role" -> end.role = asString(s.value, s.line)
                        "navigable" -> end.navigable = asBool(s.value, s.line)
                        else -> throw DslInterpretException("Unknown association-end property '${s.property}'", s.line)
                    }
                is DslCallStatement -> {
                    val c = s.call
                    if (c.name != "multiplicity") throw unknownBuilder(c, "an association end")
                    val spec = requireStringArg(c, "spec", 0)
                    end.multiplicity(spec = spec)
                }
                is DslValBinding ->
                    throw DslInterpretException("'val' bindings are not supported inside an association end", s.line)
            }
        }
    }

    // ── Comment ──────────────────────────────────────────────────────────────────

    private fun buildComment(
        builder: ClassDiagramBuilder,
        env: Env,
        call: DslCall,
    ) {
        val text = requireStringArg(call, "text", 0)
        // firstAnchor is a classifier handle; further anchors not supported in this slice.
        val anchorArg = arg(call, "firstAnchor", 1) ?: arg(call, "anchor", 1)
        if (anchorArg == null) {
            builder.comment(text = text)
            return
        }
        val v = anchorArg.value
        if (v !is DslIdentifier) {
            throw DslInterpretException("comment 'firstAnchor' must reference a val handle", call.line)
        }
        val handle =
            env.resolve(v.name, v.line) as? UmlNamedElement
                ?: throw DslInterpretException("comment anchor '${v.name}' is not a named element", v.line)
        builder.comment(text = text, firstAnchor = handle)
    }

    // ── Argument helpers ─────────────────────────────────────────────────────────

    /** Finds an argument by [named] name or, failing that, by [positionalIndex]. */
    private fun arg(
        call: DslCall,
        named: String,
        positionalIndex: Int,
    ): DslArg? {
        call.args.firstOrNull { it.name == named }?.let { return it }
        // Positional lookup: count only positional args in order.
        var idx = 0
        for (a in call.args) {
            if (a.name == null) {
                if (idx == positionalIndex) return a
                idx++
            }
        }
        return null
    }

    private fun requireStringArg(
        call: DslCall,
        named: String,
        positionalIndex: Int,
    ): String {
        val a = arg(call, named, positionalIndex) ?: throw missingArg(call, named)
        return asString(a.value, call.line)
    }

    private fun optStringArg(
        call: DslCall,
        named: String,
    ): String? {
        val a = call.args.firstOrNull { it.name == named } ?: return null
        return asString(a.value, call.line)
    }

    private fun optBoolArg(
        call: DslCall,
        named: String,
    ): Boolean? {
        val a = call.args.firstOrNull { it.name == named } ?: return null
        return asBool(a.value, call.line)
    }

    private fun optVisibilityArg(
        call: DslCall,
        named: String,
    ): Visibility? {
        val a = call.args.firstOrNull { it.name == named } ?: return null
        return asVisibility(a.value, call.line)
    }

    private fun requireClassifierArg(
        env: Env,
        call: DslCall,
        named: String,
        positionalIndex: Int,
    ): UmlClassifier {
        val a = arg(call, named, positionalIndex) ?: throw missingArg(call, named)
        val v =
            a.value as? DslIdentifier
                ?: throw DslInterpretException("Argument '$named' must reference a val handle", call.line)
        val handle = env.resolve(v.name, v.line)
        return handle as? UmlClassifier
            ?: throw DslInterpretException("'${v.name}' is not a classifier (class/interface/enum) handle", v.line)
    }

    private fun requireInterfaceArg(
        env: Env,
        call: DslCall,
        named: String,
        positionalIndex: Int,
    ): UmlInterface {
        val a = arg(call, named, positionalIndex) ?: throw missingArg(call, named)
        val v =
            a.value as? DslIdentifier
                ?: throw DslInterpretException("Argument '$named' must reference a val handle", call.line)
        val handle = env.resolve(v.name, v.line)
        return handle as? UmlInterface
            ?: throw DslInterpretException("'${v.name}' is not an interface handle", v.line)
    }

    // ── Literal coercion ─────────────────────────────────────────────────────────

    private fun asString(
        e: DslExpr,
        line: Int,
    ): String =
        (e as? DslString)?.value
            ?: throw DslInterpretException("Expected a string literal", line)

    private fun asBool(
        e: DslExpr,
        line: Int,
    ): Boolean =
        (e as? DslBool)?.value
            ?: throw DslInterpretException("Expected 'true' or 'false'", line)

    private fun asVisibility(
        e: DslExpr,
        line: Int,
    ): Visibility {
        val ref =
            e as? DslMemberRef
                ?: throw DslInterpretException("Expected a Visibility.* value", line)
        if (ref.qualifier != "Visibility") {
            throw DslInterpretException("Expected 'Visibility.*', got '${ref.qualifier}.${ref.member}'", line)
        }
        return runCatching { Visibility.valueOf(ref.member) }
            .getOrElse {
                throw DslInterpretException(
                    "Unknown Visibility '${ref.member}' — one of ${Visibility.entries.joinToString { it.name }}",
                    line,
                )
            }
    }

    private fun asAggregation(
        e: DslExpr,
        line: Int,
    ): AggregationKind {
        val ref =
            e as? DslMemberRef
                ?: throw DslInterpretException("Expected an AggregationKind.* value", line)
        if (ref.qualifier != "AggregationKind") {
            throw DslInterpretException("Expected 'AggregationKind.*', got '${ref.qualifier}.${ref.member}'", line)
        }
        return runCatching { AggregationKind.valueOf(ref.member) }
            .getOrElse {
                throw DslInterpretException(
                    "Unknown AggregationKind '${ref.member}' — one of ${AggregationKind.entries.joinToString { it.name }}",
                    line,
                )
            }
    }

    // ── Error factories ──────────────────────────────────────────────────────────

    private fun unknownBuilder(
        call: DslCall,
        where: String,
    ): DslInterpretException =
        DslInterpretException(
            "Unknown builder '${call.name}' in $where. This construct is not part of the interpreter DSL vocabulary " +
                "(Welle 9, class diagrams). Use --eval-strategy=compiler if you need the full Kotlin DSL.",
            call.line,
        )

    private fun missingArg(
        call: DslCall,
        named: String,
    ): DslInterpretException = DslInterpretException("Missing required argument '$named' for '${call.name}'", call.line)
}
