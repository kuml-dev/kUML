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
            throw DslInterpretException(message = hint, line = root.line)
        }

        val name = requireStringArg(call = root, named = "name", positionalIndex = 0)
        val builder = ClassDiagramBuilder(name = name)
        val env = Env()
        root.body?.forEach { stmt -> interpretDiagramStatement(builder = builder, env = env, stmt = stmt) }
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
                throw DslInterpretException(message = "Duplicate 'val $name' — re-binding is not allowed", line = line)
            }
            bindings[name] = value
        }

        fun resolve(
            name: String,
            line: Int,
        ): Any =
            bindings[name]
                ?: throw DslInterpretException(message = "Unknown reference '$name' — not a declared 'val'", line = line)
    }

    // ── Diagram-scope statements ─────────────────────────────────────────────────

    private fun interpretDiagramStatement(
        builder: ClassDiagramBuilder,
        env: Env,
        stmt: DslStatement,
    ) {
        when (stmt) {
            is DslValBinding -> {
                val handle = callDiagramBuilder(builder = builder, env = env, call = stmt.value)
                env.bind(name = stmt.name, value = handle, line = stmt.line)
            }
            is DslCallStatement -> callDiagramBuilder(builder = builder, env = env, call = stmt.call)
            is DslPropertyAssignment -> applyDiagramProperty(builder = builder, stmt = stmt)
        }
    }

    /** Diagram-level display-option assignments (`showOperations = false`, …). */
    private fun applyDiagramProperty(
        builder: ClassDiagramBuilder,
        stmt: DslPropertyAssignment,
    ) {
        when (stmt.property) {
            "showAttributes" -> builder.showAttributes = asBool(e = stmt.value, line = stmt.line)
            "showOperations" -> builder.showOperations = asBool(e = stmt.value, line = stmt.line)
            "showVisibility" -> builder.showVisibility = asBool(e = stmt.value, line = stmt.line)
            "showPackageNames" -> builder.showPackageNames = asBool(e = stmt.value, line = stmt.line)
            "mergeEdges" -> builder.mergeEdges = asBool(e = stmt.value, line = stmt.line)
            else ->
                throw DslInterpretException(
                    message =
                        "Unknown diagram property '${stmt.property}' — supported: showAttributes, showOperations, " +
                            "showVisibility, showPackageNames, mergeEdges",
                    line = stmt.line,
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
                val nm = requireStringArg(call = call, named = "name", positionalIndex = 0)
                val explicitId = optStringArg(call = call, named = "id")
                builder.classOf(name = nm, id = explicitId) {
                    call.body?.forEach { s -> interpretClassStatement(cls = this, env = env, stmt = s) }
                }
            }
            "interfaceOf" -> {
                val nm = requireStringArg(call = call, named = "name", positionalIndex = 0)
                val explicitId = optStringArg(call = call, named = "id")
                builder.interfaceOf(name = nm, id = explicitId) {
                    call.body?.forEach { s -> interpretInterfaceStatement(iface = this, env = env, stmt = s) }
                }
            }
            "enumOf" -> {
                val nm = requireStringArg(call = call, named = "name", positionalIndex = 0)
                val explicitId = optStringArg(call = call, named = "id")
                builder.enumOf(name = nm, id = explicitId) {
                    call.body?.forEach { s -> interpretEnumStatement(enum = this, stmt = s) }
                }
            }
            "association" -> {
                buildAssociation(builder = builder, env = env, call = call)
                Unit
            }
            "generalization" -> {
                val specific = requireClassifierArg(env = env, call = call, named = "specific", positionalIndex = 0)
                val general = requireClassifierArg(env = env, call = call, named = "general", positionalIndex = 1)
                builder.generalization(specific = specific, general = general)
                Unit
            }
            "realization" -> {
                val impl = requireClassifierArg(env = env, call = call, named = "implementing", positionalIndex = 0)
                val iface = requireInterfaceArg(env = env, call = call, named = "iface", positionalIndex = 1)
                builder.realization(implementing = impl, iface = iface)
                Unit
            }
            "dependency" -> {
                val client = requireClassifierArg(env = env, call = call, named = "client", positionalIndex = 0)
                val supplier = requireClassifierArg(env = env, call = call, named = "supplier", positionalIndex = 1)
                val nm = optStringArg(call = call, named = "name")
                builder.dependency(client = client, supplier = supplier, name = nm)
                Unit
            }
            "comment" -> {
                buildComment(builder = builder, env = env, call = call)
                Unit
            }
            else -> throw unknownBuilder(call = call, where = "the class-diagram scope")
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
                    message = "'val' bindings are not supported inside a class body",
                    line = stmt.line,
                )
            is DslPropertyAssignment -> applyClassProperty(cls = cls, stmt = stmt)
            is DslCallStatement -> callClassMember(cls = cls, env = env, call = stmt.call)
        }
    }

    private fun applyClassProperty(
        cls: ClassBuilder,
        stmt: DslPropertyAssignment,
    ) {
        when (stmt.property) {
            "isAbstract" -> cls.isAbstract = asBool(e = stmt.value, line = stmt.line)
            "visibility" -> cls.visibility = asVisibility(e = stmt.value, line = stmt.line)
            else ->
                throw DslInterpretException(
                    message = "Unknown class property '${stmt.property}' — supported: isAbstract, visibility",
                    line = stmt.line,
                )
        }
    }

    private fun callClassMember(
        cls: ClassBuilder,
        env: Env,
        call: DslCall,
    ) {
        when (call.name) {
            "attribute" -> addAttribute(scope = cls, env = env, call = call)
            "operation" -> addOperation(scope = cls, call = call)
            "constraint" -> addConstraint(scope = cls, call = call)
            "extends" -> cls.extends(requireClassifierArg(env = env, call = call, named = "general", positionalIndex = 0))
            "implements" -> cls.implements(requireInterfaceArg(env = env, call = call, named = "iface", positionalIndex = 0))
            else -> throw unknownBuilder(call = call, where = "a class body")
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
                throw DslInterpretException(message = "'val' bindings are not supported inside an interface body", line = stmt.line)
            is DslPropertyAssignment ->
                throw DslInterpretException(
                    message = "Unknown interface property '${stmt.property}'",
                    line = stmt.line,
                )
            is DslCallStatement -> {
                val call = stmt.call
                when (call.name) {
                    "attribute" -> addAttribute(scope = iface, env = env, call = call)
                    "operation" -> addOperation(scope = iface, call = call)
                    "constraint" -> addConstraint(scope = iface, call = call)
                    else -> throw unknownBuilder(call = call, where = "an interface body")
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
                if (call.name != "literal") throw unknownBuilder(call = call, where = "an enum body (only 'literal' is allowed)")
                val nm = requireStringArg(call = call, named = "name", positionalIndex = 0)
                enum.literal(name = nm, id = optStringArg(call = call, named = "id"))
            }
            else -> throw DslInterpretException(message = "Only 'literal(...)' calls are allowed inside an enum body", line = 0)
        }
    }

    // ── Attribute / operation / constraint (shared by class + interface) ──────────

    private fun addAttribute(
        scope: dev.kuml.uml.dsl.UmlClassifierScope,
        env: Env,
        call: DslCall,
    ) {
        val nm = requireStringArg(call = call, named = "name", positionalIndex = 0)
        // `type` may be a String literal OR a reference to an enumOf handle.
        val typeArg = arg(call = call, named = "type", positionalIndex = 1) ?: throw missingArg(call = call, named = "type")
        val visibility = optVisibilityArg(call = call, named = "visibility") ?: Visibility.PRIVATE
        val default = optStringArg(call = call, named = "defaultValue")
        val isStatic = optBoolArg(call = call, named = "isStatic") ?: false
        val isReadOnly = optBoolArg(call = call, named = "isReadOnly") ?: false

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
                val handle = env.resolve(name = v.name, line = v.line)
                val enumHandle =
                    handle as? UmlEnumeration
                        ?: (handle as? UmlClassifier)
                        ?: throw DslInterpretException(
                            message = "Attribute 'type = ${v.name}' must reference an enumOf/classifier handle",
                            line = v.line,
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
                    message = "Attribute 'type' must be a string literal or a classifier handle reference",
                    line = call.line,
                )
        }
    }

    private fun addOperation(
        scope: dev.kuml.uml.dsl.UmlClassifierScope,
        call: DslCall,
    ) {
        val nm = requireStringArg(call = call, named = "name", positionalIndex = 0)
        scope.operation(name = nm) {
            call.body?.forEach { s -> interpretOperationStatement(op = this, stmt = s) }
        }
    }

    private fun interpretOperationStatement(
        op: OperationBuilder,
        stmt: DslStatement,
    ) {
        when (stmt) {
            is DslPropertyAssignment -> {
                when (stmt.property) {
                    "visibility" -> op.visibility = asVisibility(e = stmt.value, line = stmt.line)
                    "isAbstract" -> op.isAbstract = asBool(e = stmt.value, line = stmt.line)
                    "isStatic" -> op.isStatic = asBool(e = stmt.value, line = stmt.line)
                    else -> throw DslInterpretException(message = "Unknown operation property '${stmt.property}'", line = stmt.line)
                }
            }
            is DslCallStatement -> {
                val call = stmt.call
                when (call.name) {
                    "parameter" -> {
                        val pName = requireStringArg(call = call, named = "name", positionalIndex = 0)
                        val pType = requireStringArg(call = call, named = "type", positionalIndex = 1)
                        op.parameter(name = pName, type = pType)
                    }
                    "returns" -> {
                        val tn = requireStringArg(call = call, named = "typeName", positionalIndex = 0)
                        op.returns(typeName = tn)
                    }
                    else -> throw unknownBuilder(call = call, where = "an operation body (only 'parameter'/'returns' allowed)")
                }
            }
            is DslValBinding ->
                throw DslInterpretException(message = "'val' bindings are not supported inside an operation body", line = stmt.line)
        }
    }

    private fun addConstraint(
        scope: dev.kuml.uml.dsl.UmlClassifierScope,
        call: DslCall,
    ) {
        val nm = requireStringArg(call = call, named = "name", positionalIndex = 0)
        val body = requireStringArg(call = call, named = "body", positionalIndex = 1)
        scope.constraint(name = nm, body = body)
    }

    // ── Association ──────────────────────────────────────────────────────────────

    private fun buildAssociation(
        builder: ClassDiagramBuilder,
        env: Env,
        call: DslCall,
    ) {
        val source = requireClassifierArg(env = env, call = call, named = "source", positionalIndex = 0)
        val target = requireClassifierArg(env = env, call = call, named = "target", positionalIndex = 1)
        builder.association(source = source, target = target) {
            call.body?.forEach { s -> interpretAssociationStatement(assoc = this, stmt = s) }
        }
    }

    private fun interpretAssociationStatement(
        assoc: AssociationBuilder,
        stmt: DslStatement,
    ) {
        when (stmt) {
            is DslPropertyAssignment -> {
                when (stmt.property) {
                    "name" -> assoc.name = asString(e = stmt.value, line = stmt.line)
                    "aggregation" -> assoc.aggregation = asAggregation(e = stmt.value, line = stmt.line)
                    else -> throw DslInterpretException(message = "Unknown association property '${stmt.property}'", line = stmt.line)
                }
            }
            is DslCallStatement -> {
                val call = stmt.call
                when (call.name) {
                    "source" -> assoc.source { applyEnd(end = this, call = call) }
                    "target" -> assoc.target { applyEnd(end = this, call = call) }
                    else -> throw unknownBuilder(call = call, where = "an association body (only 'source'/'target' allowed)")
                }
            }
            is DslValBinding ->
                throw DslInterpretException(message = "'val' bindings are not supported inside an association body", line = stmt.line)
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
                        "role" -> end.role = asString(e = s.value, line = s.line)
                        "navigable" -> end.navigable = asBool(e = s.value, line = s.line)
                        else -> throw DslInterpretException(message = "Unknown association-end property '${s.property}'", line = s.line)
                    }
                is DslCallStatement -> {
                    val c = s.call
                    if (c.name != "multiplicity") throw unknownBuilder(call = c, where = "an association end")
                    val spec = requireStringArg(call = c, named = "spec", positionalIndex = 0)
                    end.multiplicity(spec = spec)
                }
                is DslValBinding ->
                    throw DslInterpretException(message = "'val' bindings are not supported inside an association end", line = s.line)
            }
        }
    }

    // ── Comment ──────────────────────────────────────────────────────────────────

    private fun buildComment(
        builder: ClassDiagramBuilder,
        env: Env,
        call: DslCall,
    ) {
        val text = requireStringArg(call = call, named = "text", positionalIndex = 0)
        // firstAnchor is a classifier handle; further anchors not supported in this slice.
        val anchorArg =
            arg(call = call, named = "firstAnchor", positionalIndex = 1) ?: arg(call = call, named = "anchor", positionalIndex = 1)
        if (anchorArg == null) {
            builder.comment(text = text)
            return
        }
        val v = anchorArg.value
        if (v !is DslIdentifier) {
            throw DslInterpretException(message = "comment 'firstAnchor' must reference a val handle", line = call.line)
        }
        val handle =
            env.resolve(name = v.name, line = v.line) as? UmlNamedElement
                ?: throw DslInterpretException(message = "comment anchor '${v.name}' is not a named element", line = v.line)
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
        val a = arg(call = call, named = named, positionalIndex = positionalIndex) ?: throw missingArg(call = call, named = named)
        return asString(e = a.value, line = call.line)
    }

    private fun optStringArg(
        call: DslCall,
        named: String,
    ): String? {
        val a = call.args.firstOrNull { it.name == named } ?: return null
        return asString(e = a.value, line = call.line)
    }

    private fun optBoolArg(
        call: DslCall,
        named: String,
    ): Boolean? {
        val a = call.args.firstOrNull { it.name == named } ?: return null
        return asBool(e = a.value, line = call.line)
    }

    private fun optVisibilityArg(
        call: DslCall,
        named: String,
    ): Visibility? {
        val a = call.args.firstOrNull { it.name == named } ?: return null
        return asVisibility(e = a.value, line = call.line)
    }

    private fun requireClassifierArg(
        env: Env,
        call: DslCall,
        named: String,
        positionalIndex: Int,
    ): UmlClassifier {
        val a = arg(call = call, named = named, positionalIndex = positionalIndex) ?: throw missingArg(call = call, named = named)
        val v =
            a.value as? DslIdentifier
                ?: throw DslInterpretException(message = "Argument '$named' must reference a val handle", line = call.line)
        val handle = env.resolve(name = v.name, line = v.line)
        return handle as? UmlClassifier
            ?: throw DslInterpretException(message = "'${v.name}' is not a classifier (class/interface/enum) handle", line = v.line)
    }

    private fun requireInterfaceArg(
        env: Env,
        call: DslCall,
        named: String,
        positionalIndex: Int,
    ): UmlInterface {
        val a = arg(call = call, named = named, positionalIndex = positionalIndex) ?: throw missingArg(call = call, named = named)
        val v =
            a.value as? DslIdentifier
                ?: throw DslInterpretException(message = "Argument '$named' must reference a val handle", line = call.line)
        val handle = env.resolve(name = v.name, line = v.line)
        return handle as? UmlInterface
            ?: throw DslInterpretException(message = "'${v.name}' is not an interface handle", line = v.line)
    }

    // ── Literal coercion ─────────────────────────────────────────────────────────

    private fun asString(
        e: DslExpr,
        line: Int,
    ): String =
        (e as? DslString)?.value
            ?: throw DslInterpretException(message = "Expected a string literal", line = line)

    private fun asBool(
        e: DslExpr,
        line: Int,
    ): Boolean =
        (e as? DslBool)?.value
            ?: throw DslInterpretException(message = "Expected 'true' or 'false'", line = line)

    private fun asVisibility(
        e: DslExpr,
        line: Int,
    ): Visibility {
        val ref =
            e as? DslMemberRef
                ?: throw DslInterpretException(message = "Expected a Visibility.* value", line = line)
        if (ref.qualifier != "Visibility") {
            throw DslInterpretException(message = "Expected 'Visibility.*', got '${ref.qualifier}.${ref.member}'", line = line)
        }
        return runCatching { Visibility.valueOf(ref.member) }
            .getOrElse {
                throw DslInterpretException(
                    message = "Unknown Visibility '${ref.member}' — one of ${Visibility.entries.joinToString { it.name }}",
                    line = line,
                )
            }
    }

    private fun asAggregation(
        e: DslExpr,
        line: Int,
    ): AggregationKind {
        val ref =
            e as? DslMemberRef
                ?: throw DslInterpretException(message = "Expected an AggregationKind.* value", line = line)
        if (ref.qualifier != "AggregationKind") {
            throw DslInterpretException(message = "Expected 'AggregationKind.*', got '${ref.qualifier}.${ref.member}'", line = line)
        }
        return runCatching { AggregationKind.valueOf(ref.member) }
            .getOrElse {
                throw DslInterpretException(
                    message = "Unknown AggregationKind '${ref.member}' — one of ${AggregationKind.entries.joinToString { it.name }}",
                    line = line,
                )
            }
    }

    // ── Error factories ──────────────────────────────────────────────────────────

    private fun unknownBuilder(
        call: DslCall,
        where: String,
    ): DslInterpretException =
        DslInterpretException(
            message =
                "Unknown builder '${call.name}' in $where. This construct is not part of the interpreter DSL vocabulary " +
                    "(Welle 9, class diagrams). Use --eval-strategy=compiler if you need the full Kotlin DSL.",
            line = call.line,
        )

    private fun missingArg(
        call: DslCall,
        named: String,
    ): DslInterpretException = DslInterpretException(message = "Missing required argument '$named' for '${call.name}'", line = call.line)
}
