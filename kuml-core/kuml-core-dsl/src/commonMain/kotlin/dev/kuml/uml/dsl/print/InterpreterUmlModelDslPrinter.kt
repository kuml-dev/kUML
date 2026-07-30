package dev.kuml.uml.dsl.print

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlModel
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlConstraint
import dev.kuml.uml.UmlConstraintKind
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility

/**
 * Pretty-prints a [KumlModel] (rooted at a [KumlDiagram]) as a `*.kuml.kts`
 * source string in the **interpreter dialect** — the strict, `val`-based
 * grammar subset accepted by
 * [dev.kuml.core.script.interpreter.InterpreterScriptEvaluator] / [DslInterpreter]
 * (`dev.kuml.core.script.interpreter.DslInterpreter`), rather than the
 * compiler dialect emitted by [UmlModelDslPrinter].
 *
 * ## Why a separate printer
 *
 * The interpreter accepts only a much stricter subset of the Kotlin DSL than
 * the real compiler:
 *
 * - **No string-ID relationships.** `generalization`/`realization`/
 *   `dependency`/`association` must reference classifiers via `val` handles
 *   (`specific = dog`), never `specificId = "Dog"`.
 * - **No `+=` operator at all** — the interpreter's lexer has no `+` token,
 *   and tokenizes the *entire* script in one pass before parsing starts. A
 *   single `stereotypes += "…"` anywhere — on a class, interface, enum,
 *   attribute, operation, or association — fails the **whole script**, not
 *   just that one element. Stereotypes are therefore never emitted by this
 *   printer, on any construct.
 *
 * ## Two-pass emission
 *
 * Every classifier (enum, then interface, then class — the same traversal
 * order [UmlModelDslPrinter] already uses) is first declared as a `val`,
 * *unconditionally*, even if it participates in zero relationships:
 *
 * ```kotlin
 * val orderStatus = enumOf(name = "OrderStatus", id = "OrderStatus") { … }
 * val greeter = interfaceOf(name = "Greeter", id = "Greeter") { … }
 * val order = classOf(name = "Order", id = "Order") { … }
 * ```
 *
 * Only *after* every classifier has been declared are relationships and
 * comments emitted, referencing those same `val`s:
 *
 * ```kotlin
 * generalization(specific = dog, general = animal)
 * realization(implementing = orderSvc, iface = iOrderSvc)
 * association(source = customer, target = order) { … }
 * dependency(client = order, supplier = notifier, name = "notifies")
 * comment(text = "…", firstAnchor = order)
 * ```
 *
 * This trivially covers circular associations (two classes each referencing
 * the other), self-associations, and self-dependencies — both endpoints'
 * `val`s already exist by the time any relationship line runs.
 *
 * ## `val` identifier generation
 *
 * Identifiers are derived from each classifier's `name`, sanitized to a legal
 * Kotlin identifier (non-alnum/underscore characters replaced with `_`,
 * lowercased first letter, digit-leading names prefixed with `_`), then
 * de-duplicated (`foo`, `foo_2`, `foo_3`, …) and checked against the full
 * Kotlin hard-keyword set (`val`, `class`, `fun`, `is`, `in`, …) so the output
 * is itself valid, compilable Kotlin — not just interpretable. `val` is
 * defended even though it is only a *soft* concern for the interpreter's own
 * lexer (which special-cases the literal token `val`): a classifier named
 * `"Val"` would otherwise sanitize to the identifier `val`, and `val val =
 * classOf(...)` re-lexes its second `val` as the keyword, not an identifier.
 *
 * ## Known non-round-tripping fields (interpreter-dialect-specific, in
 * addition to every limitation already documented on [UmlModelDslPrinter])
 *
 * - **All stereotypes** — [dev.kuml.uml.UmlClass.stereotypes],
 *   [dev.kuml.uml.UmlInterface.stereotypes],
 *   [dev.kuml.uml.UmlEnumeration.stereotypes],
 *   [UmlProperty.stereotypes], [UmlOperation.stereotypes], and
 *   [UmlAssociation.stereotypes] are never printed on any element — the
 *   interpreter's grammar has no `+=` operator at all (see above).
 * - **Enum and interface `visibility`** — [DslInterpreter]'s enum-body and
 *   interface-body statement handling rejects *any* property assignment
 *   (only `literal(...)` calls are allowed in an enum body; interfaces have
 *   no writable property at all through the interpreter). A non-default
 *   `visibility` on a [UmlEnumeration] or [UmlInterface] is therefore always
 *   dropped, regardless of its value. Class-level `visibility` **is**
 *   supported (`ClassBuilder`'s statement handling allows it) and is printed
 *   exactly like [UmlModelDslPrinter].
 * - **Grid layout hints (`layout { … }`)** — not part of the interpreter's
 *   grammar at all (no diagram/classifier body dispatches a `"layout"` call).
 *   Never printed; no `import dev.kuml.core.dsl.layout.layout` line either.
 * - **Attribute `multiplicity`** — still printed (`multiplicity =
 *   parseMultiplicity(...)`) for parity with the compiler dialect's text, but
 *   [DslInterpreter]'s `addAttribute` never reads this named argument, so it
 *   parses harmlessly and is silently dropped on read-back.
 * - **Constraint `kind` / `contextOperation`** — same story: still printed,
 *   silently ignored by `addConstraint` on read-back.
 * - **Parameter `direction` / `defaultValue`** — still printed, silently
 *   ignored (the interpreter's `parameter(...)` handling only reads `name`
 *   and `type`).
 * - **Operation return type is emitted as a `returns(typeName = "…")` call,
 *   never a `returnType = …` property assignment** — unlike the compiler
 *   dialect, [DslInterpreter]'s operation-body statement handling has no
 *   `"returnType"` property case at all (only `visibility`/`isAbstract`/
 *   `isStatic`); emitting `returnType = …` there would fail the whole script.
 * - **Parameter type / operation return type `referencedId` is always
 *   dropped** — the interpreter's `parameter(...)` and `returns(...)`
 *   productions only accept a plain string type name; there is no
 *   classifier-handle overload reachable through this grammar for either,
 *   even though the underlying compiled builders do have one.
 * - **Attribute type `referencedId` is preserved only when the referenced
 *   classifier is (a) present in this diagram, and (b) already declared as a
 *   `val` earlier in the fixed enum → interface → class emission order** —
 *   otherwise (forward reference, self-reference, or a classifier outside
 *   this diagram) it falls back to a plain string type name, since the
 *   interpreter evaluates `val` bindings strictly top-to-bottom and a
 *   forward reference would be an unresolved-identifier error, not a partial
 *   result.
 * - **Comments beyond the first anchor are dropped** — [DslInterpreter]'s
 *   `comment(...)` handling only recognises a single `firstAnchor` argument;
 *   a `// TODO` line is emitted recording how many additional anchors were
 *   lost.
 * - **Comment `id` is never printed** — the interpreter's `comment(...)`
 *   handling does not read an `id` argument at all; the ID is always
 *   re-derived from the running `takenIds` size, unlike the compiler
 *   dialect's explicit `id = "…"`.
 * - **Relationship IDs are never preserved**, regardless of what this printer
 *   emits — [DslInterpreter]'s `generalization`/`realization`/`dependency`/
 *   `association` cases never read an `id` argument; every relationship ID is
 *   unconditionally re-derived via `UmlIds.disambiguate(...)`. This is a
 *   grammar limitation, not something this printer could route around.
 * - [UmlPackage] — out of scope, exactly as in [UmlModelDslPrinter]; a
 *   `// TODO` marker is emitted instead of a `packageOf(...)` call.
 *
 * Format:
 * ```
 * classDiagram(name = "X") {
 *     val greeter = interfaceOf(name = "Greeter", id = "Greeter") {
 *         operation(name = "greet")
 *     }
 *     val person = classOf(name = "Person", id = "Person") {
 *         attribute(name = "name", type = "String")
 *         constraint(name = "hasName", body = "self.name->notEmpty()")
 *     }
 *     val manager = classOf(name = "Manager", id = "Manager")
 *     generalization(specific = manager, general = person)
 *     realization(implementing = person, iface = greeter)
 *     dependency(client = person, supplier = greeter)
 *     comment(text = "Free-standing note.")
 * }
 * ```
 */
public object InterpreterUmlModelDslPrinter {
    public fun print(model: KumlModel): String {
        val diagram =
            model.root as? KumlDiagram
                ?: return "// model root is not a KumlDiagram — cannot serialize\n"
        return print(diagram)
    }

    /** Convenience overload for callers that already have a [KumlDiagram] (no wrapping [KumlModel]). */
    public fun print(diagram: KumlDiagram): String {
        val sb = StringBuilder()
        val elements = diagram.elements

        sb.appendLine("classDiagram(name = ${quote(diagram.name)}) {")

        val enums = elements.filterIsInstance<UmlEnumeration>()
        val ifaces = elements.filterIsInstance<UmlInterface>()
        val classes = elements.filterIsInstance<UmlClass>()

        // Identifiers for ALL classifiers are computed up front, in the same
        // enum -> interface -> class order they will be declared in, so naming
        // is deterministic and collision-safe regardless of which classifier
        // ends up referencing which.
        val used = mutableSetOf<String>()
        val nextSuffixForBase = mutableMapOf<String, Int>()
        val identOf = mutableMapOf<String, String>()
        (enums.asSequence() + ifaces.asSequence() + classes.asSequence()).forEach { classifier ->
            identOf[classifier.id] = identifierFor(name = classifier.name, used = used, nextSuffixForBase = nextSuffixForBase)
        }

        // Pass 1 — declare every classifier as a `val`, unconditionally.
        // `declaredSoFar` grows as we go so attribute type references can tell
        // an already-bound `val` (safe to reference) from a forward/self
        // reference (must fall back to a plain string type name).
        val declaredSoFar = mutableSetOf<String>()
        enums.forEach { e ->
            printEnum(sb = sb, e = e, ident = identOf.getValue(e.id))
            declaredSoFar += e.id
        }
        ifaces.forEach { i ->
            printInterface(sb = sb, i = i, ident = identOf.getValue(i.id), identOf = identOf, declaredSoFar = declaredSoFar)
            declaredSoFar += i.id
        }
        classes.forEach { c ->
            printClass(sb = sb, c = c, ident = identOf.getValue(c.id), identOf = identOf, declaredSoFar = declaredSoFar)
            declaredSoFar += c.id
        }
        elements.filterIsInstance<UmlPackage>().forEach { pkg -> printPackageTodo(sb = sb, pkg = pkg) }

        // Pass 2 — relationships + comments, referencing the vals from pass 1.
        elements.filterIsInstance<UmlGeneralization>().forEach { g -> printGeneralization(sb = sb, g = g, identOf = identOf) }
        elements.filterIsInstance<UmlInterfaceRealization>().forEach { r -> printRealization(sb = sb, r = r, identOf = identOf) }
        elements.filterIsInstance<UmlAssociation>().forEach { a -> printAssociation(sb = sb, a = a, identOf = identOf) }
        elements.filterIsInstance<UmlDependency>().forEach { d -> printDependency(sb = sb, d = d, identOf = identOf) }
        printComments(sb = sb, elements = elements, identOf = identOf)

        sb.appendLine("}")
        return sb.toString()
    }

    // ── classifiers ────────────────────────────────────────────────────────

    /**
     * Enum-level `visibility` is deliberately never printed — see the class
     * KDoc's "Known non-round-tripping fields" section. [DslInterpreter]'s
     * enum-body statement handling rejects any statement that is not a
     * `literal(...)` call, so emitting `visibility = …` here would fail the
     * whole script rather than just lose the value.
     */
    private fun printEnum(
        sb: StringBuilder,
        e: UmlEnumeration,
        ident: String,
    ) {
        sb.appendLine("    val $ident = enumOf(name = ${quote(e.name)}, id = ${quote(e.id)}) {")
        e.literals.forEach { lit ->
            sb.appendLine("        literal(${quote(lit.name)}, id = ${quote(lit.id)})")
        }
        sb.appendLine("    }")
    }

    /**
     * Interface-level `visibility` is deliberately never printed — same
     * reasoning as [printEnum]: [DslInterpreter]'s interface-body statement
     * handling rejects any property assignment outright.
     */
    private fun printInterface(
        sb: StringBuilder,
        i: UmlInterface,
        ident: String,
        identOf: Map<String, String>,
        declaredSoFar: Set<String>,
    ) {
        sb.appendLine("    val $ident = interfaceOf(name = ${quote(i.name)}, id = ${quote(i.id)}) {")
        i.attributes.forEach { printAttribute(sb = sb, p = it, indent = "        ", identOf = identOf, declaredSoFar = declaredSoFar) }
        i.operations.forEach { printOperation(sb = sb, o = it, indent = "        ") }
        i.constraints.forEach { printConstraint(sb = sb, c = it, indent = "        ") }
        sb.appendLine("    }")
    }

    private fun printClass(
        sb: StringBuilder,
        c: UmlClass,
        ident: String,
        identOf: Map<String, String>,
        declaredSoFar: Set<String>,
    ) {
        sb.appendLine("    val $ident = classOf(name = ${quote(c.name)}, id = ${quote(c.id)}) {")
        if (c.visibility != Visibility.PUBLIC) sb.appendLine("        visibility = Visibility.${c.visibility.name}")
        if (c.isAbstract) sb.appendLine("        isAbstract = true")
        c.attributes.forEach { printAttribute(sb = sb, p = it, indent = "        ", identOf = identOf, declaredSoFar = declaredSoFar) }
        c.operations.forEach { printOperation(sb = sb, o = it, indent = "        ") }
        c.constraints.forEach { printConstraint(sb = sb, c = it, indent = "        ") }
        sb.appendLine("    }")
    }

    /** Same out-of-scope handling as [UmlModelDslPrinter] — see its KDoc. */
    private fun printPackageTodo(
        sb: StringBuilder,
        pkg: UmlPackage,
    ) {
        val lostCount = countNestedMembers(pkg)
        sb.appendLine(
            "    // TODO: UmlPackage ${quote(pkg.name)} (id = ${quote(pkg.id)}) not serialized — " +
                "packageOf is out of scope for InterpreterUmlModelDslPrinter. " +
                "$lostCount nested member(s) (classifiers/attributes/operations/constraints, recursively) are NOT represented anywhere in this output.",
        )
    }

    /**
     * Iterative (explicit-stack) equivalent of the naive recursive
     * `1 + countNestedMembers(member)` walk. A recursive implementation would
     * recurse one Kotlin call-stack frame per level of [UmlPackage] nesting,
     * so a pathologically deep package chain could exhaust the stack
     * ([StackOverflowError]) even though the total member count is modest.
     * Using a heap-allocated [ArrayDeque] as a worklist instead bounds memory
     * use to the total member count rather than the nesting depth, with no
     * risk of blowing the call stack regardless of how deep [pkg] nests.
     */
    private fun countNestedMembers(pkg: UmlPackage): Int {
        var count = 0
        val pending = ArrayDeque<UmlPackage>()
        pending.addLast(pkg)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            current.members.forEach { member ->
                count += 1
                when (member) {
                    is UmlPackage -> pending.addLast(member)
                    is UmlClass -> count += member.attributes.size + member.operations.size + member.constraints.size
                    is UmlInterface -> count += member.attributes.size + member.operations.size + member.constraints.size
                    is UmlEnumeration -> count += member.literals.size
                    else -> Unit
                }
            }
        }
        return count
    }

    // ── features ───────────────────────────────────────────────────────────

    /**
     * Always the flat, single-expression form — the interpreter dialect never
     * uses the block form, because the only reason [UmlModelDslPrinter] needs
     * it (attribute-level `stereotypes += …`) is not representable at all
     * here (no `+=` operator in the grammar).
     */
    private fun printAttribute(
        sb: StringBuilder,
        p: UmlProperty,
        indent: String,
        identOf: Map<String, String>,
        declaredSoFar: Set<String>,
    ) {
        val multExpr =
            if (p.multiplicity != Multiplicity()) {
                "parseMultiplicity(${quote(multiplicityString(p.multiplicity))})"
            } else {
                null
            }
        val visArg = if (p.visibility != Visibility.PRIVATE) "visibility = Visibility.${p.visibility.name}" else null
        val args =
            mutableListOf(
                "name = ${quote(p.name)}",
                "type = ${attributeTypeArg(t = p.type, identOf = identOf, declaredSoFar = declaredSoFar)}",
            )
        visArg?.let { args += it }
        multExpr?.let { args += "multiplicity = $it" }
        p.defaultValue?.let { args += "defaultValue = ${quote(it)}" }
        if (p.isStatic) args += "isStatic = true"
        if (p.isReadOnly) args += "isReadOnly = true"
        sb.appendLine("$indent attribute(${args.joinToString(", ")})")
    }

    /**
     * Operations are emitted with `returns(typeName = "…")` — a call
     * statement — rather than a `returnType = …` property assignment, because
     * [DslInterpreter]'s operation-body handling has no `"returnType"`
     * property case (only `visibility`/`isAbstract`/`isStatic`); the property
     * form would fail interpretation entirely.
     */
    private fun printOperation(
        sb: StringBuilder,
        o: UmlOperation,
        indent: String,
    ) {
        val visArg = if (o.visibility != Visibility.PUBLIC) "visibility = Visibility.${o.visibility.name}" else null
        val hasBody =
            o.returnType != null ||
                o.isAbstract ||
                o.isStatic ||
                visArg != null ||
                o.parameters.isNotEmpty()
        if (!hasBody) {
            sb.appendLine("$indent operation(name = ${quote(o.name)})")
        } else {
            sb.appendLine("$indent operation(name = ${quote(o.name)}) {")
            visArg?.let { sb.appendLine("$indent    $it") }
            if (o.isStatic) sb.appendLine("$indent    isStatic = true")
            if (o.isAbstract) sb.appendLine("$indent    isAbstract = true")
            o.parameters.forEach { printParameter(sb = sb, p = it, indent = "$indent   ") }
            o.returnType?.let { sb.appendLine("$indent    returns(typeName = ${quote(it.name)})") }
            sb.appendLine("$indent }")
        }
    }

    /**
     * Parameter `type` is always a plain string — [DslInterpreter]'s
     * `parameter(...)` handling requires a string literal for `type`
     * (`requireStringArg`), so a classifier-handle reference is never
     * representable here even when [UmlTypeRef.referencedId] is set.
     */
    private fun printParameter(
        sb: StringBuilder,
        p: UmlParameter,
        indent: String,
    ) {
        val args = mutableListOf("name = ${quote(p.name)}", "type = ${quote(p.type.name)}")
        if (p.direction != ParameterDirection.IN) args += "direction = ParameterDirection.${p.direction.name}"
        p.defaultValue?.let { args += "defaultValue = ${quote(it)}" }
        sb.appendLine("$indent parameter(${args.joinToString(", ")})")
    }

    private fun printConstraint(
        sb: StringBuilder,
        c: UmlConstraint,
        indent: String,
    ) {
        val args = mutableListOf("name = ${quote(c.name)}", "body = ${quote(c.body)}")
        if (c.kind != UmlConstraintKind.Invariant) args += "kind = UmlConstraintKind.${c.kind.name}"
        c.contextOperation?.let { args += "contextOperation = ${quote(it)}" }
        sb.appendLine("$indent constraint(${args.joinToString(", ")})")
    }

    // ── relationships ──────────────────────────────────────────────────────

    private fun printGeneralization(
        sb: StringBuilder,
        g: UmlGeneralization,
        identOf: Map<String, String>,
    ) {
        val specific = identOf[g.specificId]
        val general = identOf[g.generalId]
        if (specific == null || general == null) {
            sb.appendLine(
                "    // TODO: generalization ${quote(g.id)} references a classifier not declared in this diagram — " +
                    "not representable by the interpreter dialect",
            )
            return
        }
        sb.appendLine("    generalization(specific = $specific, general = $general)")
    }

    private fun printRealization(
        sb: StringBuilder,
        r: UmlInterfaceRealization,
        identOf: Map<String, String>,
    ) {
        val implementing = identOf[r.implementingId]
        val iface = identOf[r.interfaceId]
        if (implementing == null || iface == null) {
            sb.appendLine(
                "    // TODO: realization ${quote(r.id)} references a classifier not declared in this diagram — " +
                    "not representable by the interpreter dialect",
            )
            return
        }
        sb.appendLine("    realization(implementing = $implementing, iface = $iface)")
    }

    private fun printDependency(
        sb: StringBuilder,
        d: UmlDependency,
        identOf: Map<String, String>,
    ) {
        val client = identOf[d.clientId]
        val supplier = identOf[d.supplierId]
        if (client == null || supplier == null) {
            sb.appendLine(
                "    // TODO: dependency ${quote(d.id)} references a classifier not declared in this diagram — " +
                    "not representable by the interpreter dialect",
            )
            return
        }
        val args = mutableListOf("client = $client", "supplier = $supplier")
        d.name?.let { args += "name = ${quote(it)}" }
        sb.appendLine("    dependency(${args.joinToString(", ")})")
    }

    private fun printAssociation(
        sb: StringBuilder,
        a: UmlAssociation,
        identOf: Map<String, String>,
    ) {
        if (a.ends.size < 2) return
        val source = identOf[a.ends[0].typeId]
        val target = identOf[a.ends[1].typeId]
        if (source == null || target == null) {
            sb.appendLine(
                "    // TODO: association ${quote(a.id)} references a classifier not declared in this diagram — " +
                    "not representable by the interpreter dialect",
            )
            return
        }
        val srcBody = endBody(a.ends[0])
        val tgtBody = endBody(a.ends[1])
        val hasName = a.name != null
        val hasAggregation = a.aggregation != AggregationKind.NONE
        // Association stereotypes are dropped entirely — no `+=` operator in
        // the interpreter grammar (see class KDoc).
        if (srcBody == null && tgtBody == null && !hasName && !hasAggregation) {
            sb.appendLine("    association(source = $source, target = $target)")
        } else {
            sb.appendLine("    association(source = $source, target = $target) {")
            if (hasName) sb.appendLine("        name = ${quote(a.name!!)}")
            if (hasAggregation) sb.appendLine("        aggregation = AggregationKind.${a.aggregation.name}")
            srcBody?.let { sb.appendLine("        source { $it }") }
            tgtBody?.let { sb.appendLine("        target { $it }") }
            sb.appendLine("    }")
        }
    }

    /** Identical shape to [UmlModelDslPrinter]'s `endBody` — role/navigable/multiplicity are all interpreter-safe. */
    private fun endBody(end: UmlAssociationEnd): String? {
        val parts = mutableListOf<String>()
        if (end.multiplicity != Multiplicity()) parts += "multiplicity(${quote(multiplicityString(end.multiplicity))})"
        if (!end.navigable) parts += "navigable = false"
        end.role?.let { parts += "role = ${quote(it)}" }
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }

    // ── comments ───────────────────────────────────────────────────────────

    /**
     * Only the first anchor is representable — [DslInterpreter]'s
     * `comment(...)` handling recognises a single `firstAnchor` argument and
     * nothing beyond it. Additional anchors are dropped with a `// TODO`
     * marker recording the loss. The comment's own `id` is never printed —
     * the interpreter doesn't read one; it is always re-derived.
     *
     * Anchors are looked up via a single [links]-grouped map
     * (`commentId -> annotatedElementId`s) computed once up front, rather
     * than re-filtering the full link list per comment — the latter is
     * `O(comments * links)`, which gets slow fast on diagrams with many
     * comments and/or many comment links; grouping once is `O(comments +
     * links)`.
     */
    private fun printComments(
        sb: StringBuilder,
        elements: List<KumlElement>,
        identOf: Map<String, String>,
    ) {
        val anchorIdsByCommentId =
            elements
                .filterIsInstance<UmlCommentLink>()
                .groupBy(keySelector = { it.commentId }, valueTransform = { it.annotatedElementId })
        elements.filterIsInstance<UmlComment>().forEach { c ->
            val resolvedAnchors =
                anchorIdsByCommentId[c.id]
                    .orEmpty()
                    .mapNotNull { identOf[it] }
            val textArg = "text = ${quote(c.body)}"
            if (resolvedAnchors.isEmpty()) {
                sb.appendLine("    comment($textArg)")
            } else {
                sb.appendLine("    comment($textArg, firstAnchor = ${resolvedAnchors.first()})")
                if (resolvedAnchors.size > 1) {
                    sb.appendLine(
                        "    // TODO: comment ${quote(c.id)} had ${resolvedAnchors.size - 1} more anchor(s) " +
                            "not representable by the interpreter dialect (only firstAnchor is supported)",
                    )
                }
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun multiplicityString(m: Multiplicity): String {
        val upper = m.upper?.toString() ?: "*"
        return if (m.lower == m.upper) m.lower.toString() else "${m.lower}..$upper"
    }

    /**
     * Resolves an attribute `type = …` argument. Uses the bound `val`
     * identifier when [UmlTypeRef.referencedId] points at a classifier that
     * is both present in this diagram *and* already declared earlier in the
     * fixed emission order ([declaredSoFar]) — never a forward or
     * self-reference, since the interpreter binds `val`s strictly
     * top-to-bottom. Falls back to the plain string type name otherwise.
     */
    private fun attributeTypeArg(
        t: UmlTypeRef,
        identOf: Map<String, String>,
        declaredSoFar: Set<String>,
    ): String {
        val refId = t.referencedId
        if (refId != null && refId in declaredSoFar) {
            identOf[refId]?.let { return it }
        }
        return quote(t.name)
    }

    /** Full Kotlin hard-keyword set — output must stay valid, compilable Kotlin, not just interpretable. */
    private val KOTLIN_HARD_KEYWORDS =
        setOf(
            "val",
            "var",
            "fun",
            "class",
            "object",
            "interface",
            "package",
            "import",
            "if",
            "else",
            "when",
            "is",
            "in",
            "for",
            "while",
            "do",
            "return",
            "break",
            "continue",
            "throw",
            "try",
            "catch",
            "finally",
            "null",
            "true",
            "false",
            "this",
            "super",
            "as",
            "typealias",
            "typeof",
        )

    /**
     * Derives a unique, legal Kotlin `val` identifier from a classifier's
     * `name`: non-alphanumeric/underscore characters
     * become `_`, a digit-leading result gets an `_` prefix, the first letter
     * is lowercased, and collisions (including with the reserved word set)
     * are broken with a numeric suffix (`foo`, `foo_2`, `foo_3`, …).
     *
     * [nextSuffixForBase] tracks, per sanitized `base`, the next numeric
     * suffix worth trying first. Without it, every classifier sharing a base
     * with `k` predecessors would re-probe the `used` set from `n = 2`,
     * making a diagram of `K` identically-sanitizing classifiers cost
     * `O(K^2)` set lookups (`O(N^2)` worst case across a diagram of `N`
     * classifiers). Seeding the search at the last-known-free suffix makes
     * the common case (only generated candidates collide, never a literal
     * name that happens to look like one) amortized O(1) per classifier; the
     * `while` loop remains for correctness in the rarer case where a *literal*
     * classifier name collides with an already-claimed generated candidate.
     */
    private fun identifierFor(
        name: String,
        used: MutableSet<String>,
        nextSuffixForBase: MutableMap<String, Int>,
    ): String {
        var base = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        if (base.isEmpty() || !(base[0].isLetter() || base[0] == '_')) base = "_$base"
        base = base.replaceFirstChar { it.lowercaseChar() }
        var candidate = base
        if (candidate in used || candidate in KOTLIN_HARD_KEYWORDS) {
            var n = nextSuffixForBase[base] ?: 2
            candidate = "${base}_$n"
            while (candidate in used || candidate in KOTLIN_HARD_KEYWORDS) {
                n++
                candidate = "${base}_$n"
            }
            nextSuffixForBase[base] = n + 1
        }
        used += candidate
        return candidate
    }

    /**
     * Quotes [s] as a Kotlin string literal — identical escaping rules to
     * [UmlModelDslPrinter.quote] (duplicated here rather than shared, per this
     * wave's decision to keep the kUML-core diff to a single new file).
     */
    private fun quote(s: String): String =
        buildString {
            append('"')
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
}
