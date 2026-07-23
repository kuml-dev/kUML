package dev.kuml.uml.dsl.print

import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlMetaValue
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
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility

/**
 * Pretty-prints a [KumlModel] (rooted at a [KumlDiagram]) as a `*.kuml.kts`
 * source string.
 *
 * The output is **re-parseable**: feeding it back through [dev.kuml.core.script.KumlScriptHost]
 * reconstructs an equivalent model. This is the load-bearing contract for the
 * server-side grid-hint endpoint (`POST /api/layout/hint`) — it round-trips a
 * script through parse → [dev.kuml.layout.bridge.LayoutHintWriter] → print and
 * hands the result back to the client for re-rendering.
 *
 * ## DSL dialect
 *
 * This printer emits the **compiler dialect** (string-ID relationships,
 * `classOf(name=, id=)` without `val` bindings) — the dialect accepted by
 * [dev.kuml.core.script.InProcessScriptEvaluator] / `KumlScriptHost`, the
 * production default. It is **not** guaranteed to round-trip through the
 * experimental `InterpreterScriptEvaluator`, which only accepts a stricter
 * subset (no string-ID relationships, no constraint `kind`/`contextOperation`,
 * no parameter `direction`/`defaultValue`, no multi-anchor comments, no
 * `packageOf`). See `UmlModelDslPrinter`-adjacent round-trip tests in
 * `kuml-core-script` for the compiler-path contract this file guarantees.
 *
 * ## Multi-line text formatting
 *
 * Constraint bodies and comment text may contain newlines. Rather than using
 * Kotlin raw triple-quoted strings (which cannot escape `$`/`"""` and cannot
 * cleanly represent a trailing `"`), this printer keeps every string literal
 * on a single output line and escapes `\n`/`\r`/`\t` via [quote]. This is
 * lower-risk and fully re-parses to the exact original string.
 *
 * ## Known non-round-tripping fields (not expressible via the DSL builders)
 *
 * - [UmlConstraint.language] — always re-parses as `"OCL"` regardless of the
 *   original value; no DSL parameter exists for it.
 * - Visibility on [dev.kuml.uml.UmlEnumerationLiteral], [UmlConstraint], and
 *   [UmlParameter] — none of the corresponding builder calls (`literal`,
 *   `constraint`, `parameter`) expose a visibility parameter.
 * - [dev.kuml.uml.AppliedStereotype] (profile-validated stereotype
 *   applications) — the only DSL path (`stereotype(name) { }`) requires the
 *   originating [dev.kuml.profile.KumlProfile] to be registered and applied
 *   at diagram scope via `applyProfile(...)`, which this printer does not
 *   attempt to reconstruct. The simple string `stereotypes` list (`stereotypes
 *   += "…"`) is unaffected and round-trips normally.
 * - [UmlPackage] — out of scope for this printer (the interpreter's grammar,
 *   which defines this wave's charter, does not support `packageOf` at all;
 *   a compiler-only implementation would additionally have to contend with
 *   recursive package members and `PackageBuilder`'s documented behaviour of
 *   silently dropping inline `extends`/`implements` on packaged classes). A
 *   `UmlPackage` present in [KumlDiagram.elements] is not printed as a
 *   builder call — a `// TODO` comment is emitted instead so the data loss is
 *   visible rather than silent.
 *
 * Format:
 * ```
 * classDiagram(name = "X") {
 *     enumOf(name = "Color", id = "Color") {
 *         literal("RED", id = "Color::RED")
 *     }
 *     interfaceOf(name = "Greeter", id = "Greeter") {
 *         operation(name = "greet")
 *     }
 *     classOf(name = "Person", id = "Person") {
 *         stereotypes += "data"
 *         attribute(name = "name", type = "String")
 *         constraint(name = "hasName", body = "self.name->notEmpty()")
 *         layout { col = 0; row = 0 }
 *     }
 *     generalization(specificId = "Manager", generalId = "Person")
 *     realization(implementingId = "Greeter", interfaceId = "Person")
 *     association(sourceId = "Person", targetId = "Address") {
 *         target { multiplicity("0..*") }
 *     }
 *     dependency(clientId = "Person", supplierId = "Greeter")
 *     comment(text = "Free-standing note.", id = "cmt-1")
 * }
 * ```
 *
 * Classifiers are always printed with an explicit `id = "…"` argument so that
 * their identity survives the round-trip regardless of how the ID was
 * originally derived (auto-derived from the name, or an arbitrary ID from a
 * reverse-engineering importer). Relationships and association ends reference
 * those same IDs directly — never element names — so relationships never
 * dangle after a round-trip.
 */
public object UmlModelDslPrinter {
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

        // `layout { }` lives in `dev.kuml.core.dsl.layout`, which is NOT part of the
        // kUML script host's default imports (only `dev.kuml.core.dsl.*` itself is).
        // Emit the explicit import — but only when a grid hint is actually printed —
        // so plain scripts without layout hints stay import-free.
        if (hasAnyLayoutHints(elements)) {
            sb.appendLine("import dev.kuml.core.dsl.layout.layout")
        }
        sb.appendLine("classDiagram(name = ${quote(diagram.name)}) {")

        elements.filterIsInstance<UmlEnumeration>().forEach { e ->
            printEnum(sb, e)
        }
        elements.filterIsInstance<UmlInterface>().forEach { i ->
            printInterface(sb, i)
        }
        elements.filterIsInstance<UmlClass>().forEach { c ->
            printClass(sb, c)
        }
        elements.filterIsInstance<UmlPackage>().forEach { pkg ->
            printPackageTodo(sb, pkg)
        }
        elements.filterIsInstance<UmlGeneralization>().forEach { g ->
            printGeneralization(sb, g)
        }
        elements.filterIsInstance<UmlInterfaceRealization>().forEach { r ->
            printRealization(sb, r)
        }
        elements.filterIsInstance<UmlAssociation>().forEach { a ->
            printAssociation(sb, a)
        }
        elements.filterIsInstance<UmlDependency>().forEach { d ->
            printDependency(sb, d)
        }
        printComments(sb, elements)

        sb.appendLine("}")
        return sb.toString()
    }

    // ── classifiers ────────────────────────────────────────────────────────

    private fun printEnum(
        sb: StringBuilder,
        e: UmlEnumeration,
    ) {
        sb.appendLine("    enumOf(name = ${quote(e.name)}, id = ${quote(e.id)}) {")
        if (e.visibility != Visibility.PUBLIC) sb.appendLine("        visibility = Visibility.${e.visibility.name}")
        stereotypesLines(e.stereotypes, "        ").forEach(sb::appendLine)
        e.literals.forEach { lit ->
            sb.appendLine("        literal(${quote(lit.name)}, id = ${quote(lit.id)})")
        }
        printLayoutHints(sb, e, indent = "        ")
        sb.appendLine("    }")
    }

    private fun printInterface(
        sb: StringBuilder,
        i: UmlInterface,
    ) {
        sb.appendLine("    interfaceOf(name = ${quote(i.name)}, id = ${quote(i.id)}) {")
        if (i.visibility != Visibility.PUBLIC) sb.appendLine("        visibility = Visibility.${i.visibility.name}")
        stereotypesLines(i.stereotypes, "        ").forEach(sb::appendLine)
        i.attributes.forEach { printAttribute(sb, it, indent = "        ") }
        i.operations.forEach { printOperation(sb, it, indent = "        ") }
        i.constraints.forEach { printConstraint(sb, it, "        ") }
        printLayoutHints(sb, i, indent = "        ")
        sb.appendLine("    }")
    }

    private fun printClass(
        sb: StringBuilder,
        c: UmlClass,
    ) {
        sb.appendLine("    classOf(name = ${quote(c.name)}, id = ${quote(c.id)}) {")
        if (c.visibility != Visibility.PUBLIC) sb.appendLine("        visibility = Visibility.${c.visibility.name}")
        if (c.isAbstract) sb.appendLine("        isAbstract = true")
        stereotypesLines(c.stereotypes, "        ").forEach(sb::appendLine)
        c.attributes.forEach { printAttribute(sb, it, indent = "        ") }
        c.operations.forEach { printOperation(sb, it, indent = "        ") }
        c.constraints.forEach { printConstraint(sb, it, "        ") }
        printLayoutHints(sb, c, indent = "        ")
        sb.appendLine("    }")
    }

    /**
     * [UmlPackage] serialization is out of scope (see the class KDoc "Known
     * non-round-tripping fields" section) — emit a visible marker instead of
     * silently dropping the package and its members. Counts every nested
     * member recursively (not just direct children) so the marker reflects
     * the true blast radius: a package containing a handful of classes with
     * attributes/operations/constraints can otherwise disappear behind a
     * single innocuous-looking comment line, with no indication that dozens
     * of model elements were lost, not just the package wrapper itself.
     */
    private fun printPackageTodo(
        sb: StringBuilder,
        pkg: UmlPackage,
    ) {
        val lostCount = countNestedMembers(pkg)
        sb.appendLine(
            "    // TODO: UmlPackage ${quote(pkg.name)} (id = ${quote(pkg.id)}) not serialized — " +
                "packageOf is out of scope for UmlModelDslPrinter. " +
                "$lostCount nested member(s) (classifiers/attributes/operations/constraints, recursively) are NOT represented anywhere in this output.",
        )
    }

    /**
     * Recursively counts every member lost when a package is skipped: the
     * package's direct children, each classifier's own attributes/
     * operations/constraints, and members of any nested sub-package.
     */
    private fun countNestedMembers(pkg: UmlPackage): Int =
        pkg.members.sumOf { member ->
            1 +
                when (member) {
                    is UmlPackage -> countNestedMembers(member)
                    is UmlClass -> member.attributes.size + member.operations.size + member.constraints.size
                    is UmlInterface -> member.attributes.size + member.operations.size + member.constraints.size
                    is UmlEnumeration -> member.literals.size
                    else -> 0
                }
        }

    // ── features ───────────────────────────────────────────────────────────

    private fun printAttribute(
        sb: StringBuilder,
        p: UmlProperty,
        indent: String,
    ) {
        val multExpr =
            if (p.multiplicity != Multiplicity()) {
                "parseMultiplicity(${quote(multiplicityString(p.multiplicity))})"
            } else {
                null
            }
        val visArg = if (p.visibility != Visibility.PRIVATE) "visibility = Visibility.${p.visibility.name}" else null
        if (p.stereotypes.isEmpty()) {
            // Flat, single-expression form — valid because multiplicity/defaultValue/
            // isStatic/isReadOnly/visibility are all named parameters on the no-block
            // `attribute(...)` overload.
            val args = mutableListOf("name = ${quote(p.name)}", "type = ${typeArg(p.type)}")
            visArg?.let { args += it }
            multExpr?.let { args += "multiplicity = $it" }
            p.defaultValue?.let { args += "defaultValue = ${quote(it)}" }
            if (p.isStatic) args += "isStatic = true"
            if (p.isReadOnly) args += "isReadOnly = true"
            sb.appendLine("$indent attribute(${args.joinToString(", ")})")
        } else {
            // Block form — required because `stereotypes` is only settable inside
            // the `AttributeBuilder` body, not as a named constructor argument.
            sb.appendLine("$indent attribute(name = ${quote(p.name)}, type = ${typeArg(p.type)}) {")
            visArg?.let { sb.appendLine("$indent    $it") }
            multExpr?.let { sb.appendLine("$indent    multiplicity = $it") }
            p.defaultValue?.let { sb.appendLine("$indent    defaultValue = ${quote(it)}") }
            if (p.isStatic) sb.appendLine("$indent    isStatic = true")
            if (p.isReadOnly) sb.appendLine("$indent    isReadOnly = true")
            p.stereotypes.forEach { sb.appendLine("$indent    stereotypes += ${quote(it)}") }
            sb.appendLine("$indent }")
        }
    }

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
                o.parameters.isNotEmpty() ||
                o.stereotypes.isNotEmpty()
        if (!hasBody) {
            sb.appendLine("$indent operation(name = ${quote(o.name)})")
        } else {
            sb.appendLine("$indent operation(name = ${quote(o.name)}) {")
            visArg?.let { sb.appendLine("$indent    $it") }
            if (o.isStatic) sb.appendLine("$indent    isStatic = true")
            if (o.isAbstract) sb.appendLine("$indent    isAbstract = true")
            o.parameters.forEach { printParameter(sb, it, "$indent   ") }
            o.returnType?.let { sb.appendLine("$indent    returnType = ${typeRefExpr(it)}") }
            o.stereotypes.forEach { sb.appendLine("$indent    stereotypes += ${quote(it)}") }
            sb.appendLine("$indent }")
        }
    }

    private fun printParameter(
        sb: StringBuilder,
        p: UmlParameter,
        indent: String,
    ) {
        val args = mutableListOf("name = ${quote(p.name)}", "type = ${typeArg(p.type)}")
        if (p.direction != ParameterDirection.IN) args += "direction = ParameterDirection.${p.direction.name}"
        p.defaultValue?.let { args += "defaultValue = ${quote(it)}" }
        sb.appendLine("$indent parameter(${args.joinToString(", ")})")
    }

    /**
     * Emits an OCL/constraint attachment on the enclosing class or interface.
     *
     * [UmlConstraint.language] and constraint-level visibility are not
     * expressible via the `constraint(...)` DSL builder and are therefore not
     * round-tripped — see the class KDoc.
     */
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
    ) {
        sb.appendLine("    generalization(specificId = ${quote(g.specificId)}, generalId = ${quote(g.generalId)})")
    }

    private fun printRealization(
        sb: StringBuilder,
        r: UmlInterfaceRealization,
    ) {
        sb.appendLine("    realization(implementingId = ${quote(r.implementingId)}, interfaceId = ${quote(r.interfaceId)})")
    }

    private fun printDependency(
        sb: StringBuilder,
        d: UmlDependency,
    ) {
        val args = mutableListOf("clientId = ${quote(d.clientId)}", "supplierId = ${quote(d.supplierId)}")
        d.name?.let { args += "name = ${quote(it)}" }
        sb.appendLine("    dependency(${args.joinToString(", ")})")
    }

    private fun printAssociation(
        sb: StringBuilder,
        a: UmlAssociation,
    ) {
        if (a.ends.size < 2) return
        val srcId = a.ends[0].typeId
        val tgtId = a.ends[1].typeId
        val srcBody = endBody(a.ends[0])
        val tgtBody = endBody(a.ends[1])
        val hasName = a.name != null
        val hasAggregation = a.aggregation != AggregationKind.NONE
        val hasStereotypes = a.stereotypes.isNotEmpty()
        if (srcBody == null && tgtBody == null && !hasName && !hasAggregation && !hasStereotypes) {
            sb.appendLine("    association(sourceId = ${quote(srcId)}, targetId = ${quote(tgtId)})")
        } else {
            sb.appendLine("    association(sourceId = ${quote(srcId)}, targetId = ${quote(tgtId)}) {")
            if (hasName) sb.appendLine("        name = ${quote(a.name!!)}")
            if (hasAggregation) sb.appendLine("        aggregation = AggregationKind.${a.aggregation.name}")
            stereotypesLines(a.stereotypes, "        ").forEach(sb::appendLine)
            srcBody?.let { sb.appendLine("        source { $it }") }
            tgtBody?.let { sb.appendLine("        target { $it }") }
            sb.appendLine("    }")
        }
    }

    /**
     * Builds the `source { … }` / `target { … }` body for one association end,
     * or `null` if the end is entirely default (multiplicity `1`, navigable,
     * no role) and needs no block at all.
     *
     * Multiple parts are joined with `; ` onto a single line — valid Kotlin
     * (semicolon-separated statements) and keeps the existing single-line
     * `source { multiplicity("...") }` style intact for the common one-part case.
     */
    private fun endBody(end: UmlAssociationEnd): String? {
        val parts = mutableListOf<String>()
        if (end.multiplicity != Multiplicity()) parts += "multiplicity(${quote(multiplicityString(end.multiplicity))})"
        if (!end.navigable) parts += "navigable = false"
        end.role?.let { parts += "role = ${quote(it)}" }
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }

    // ── comments ───────────────────────────────────────────────────────────

    /**
     * Emits every free-standing or anchored [UmlComment] in [elements].
     *
     * Anchors are derived by grouping the diagram's [UmlCommentLink]s by
     * `commentId`, preserving first-seen order. [UmlCommentLink] itself has no
     * standalone DSL builder — it is entirely consumed here and must **not**
     * also be dispatched as if it were a printable relationship.
     *
     * The comment `id` is always emitted explicitly: [UmlComment] IDs are
     * derived from a running index over the container's taken-ID set, so
     * omitting `id` would let re-parsing assign a different index and break
     * equality.
     *
     * Anchors are passed via the named `anchors = arrayOf(...)` parameter
     * (rather than positional vararg strings mixed with the trailing named
     * `id = ...`) — an unambiguous, fully-named call form that is guaranteed
     * legal Kotlin regardless of how many anchors are present.
     */
    private fun printComments(
        sb: StringBuilder,
        elements: List<KumlElement>,
    ) {
        val links = elements.filterIsInstance<UmlCommentLink>()
        elements.filterIsInstance<UmlComment>().forEach { c ->
            val anchors = links.filter { it.commentId == c.id }.map { it.annotatedElementId }
            val args = mutableListOf("text = ${quote(c.body)}")
            if (anchors.isNotEmpty()) {
                args += "anchors = arrayOf(${anchors.joinToString(", ") { quote(it) }})"
            }
            args += "id = ${quote(c.id)}"
            sb.appendLine("    comment(${args.joinToString(", ")})")
        }
    }

    // ── layout hints (grid drag-and-drop round-trip) ────────────────────────

    /**
     * Emits a `layout { … }` block for [element] when its metadata carries any
     * `kuml.layout.*` grid keys. Reads the same [LayoutMetadataKeys] that
     * `dev.kuml.layout.bridge.LayoutHintWriter` writes, so a script printed
     * here and re-rendered reflects the same grid placement.
     */
    private fun printLayoutHints(
        sb: StringBuilder,
        element: UmlNamedElement,
        indent: String,
    ) {
        val meta = element.metadata
        val col = meta.intValue(LayoutMetadataKeys.GRID_COL)
        val row = meta.intValue(LayoutMetadataKeys.GRID_ROW)
        val colSpan = meta.intValue(LayoutMetadataKeys.GRID_COL_SPAN)
        val rowSpan = meta.intValue(LayoutMetadataKeys.GRID_ROW_SPAN)
        val pinned = (meta[LayoutMetadataKeys.PINNED] as? KumlMetaValue.Flag)?.value ?: false
        if (col == null && row == null && colSpan == null && rowSpan == null && !pinned) return
        sb.appendLine("$indent layout {")
        col?.let { sb.appendLine("$indent    col = $it") }
        row?.let { sb.appendLine("$indent    row = $it") }
        colSpan?.let { sb.appendLine("$indent    colSpan = $it") }
        rowSpan?.let { sb.appendLine("$indent    rowSpan = $it") }
        if (pinned) sb.appendLine("$indent    pinned = true")
        sb.appendLine("$indent }")
    }

    /** `true` if any [UmlNamedElement] in [elements] carries a `kuml.layout.*` grid key. */
    private fun hasAnyLayoutHints(elements: List<KumlElement>): Boolean =
        elements.filterIsInstance<UmlNamedElement>().any { el ->
            val m = el.metadata
            m.containsKey(LayoutMetadataKeys.GRID_COL) ||
                m.containsKey(LayoutMetadataKeys.GRID_ROW) ||
                m.containsKey(LayoutMetadataKeys.GRID_COL_SPAN) ||
                m.containsKey(LayoutMetadataKeys.GRID_ROW_SPAN) ||
                m.containsKey(LayoutMetadataKeys.PINNED)
        }

    private fun Map<String, KumlMetaValue>.intValue(key: String): Int? = (this[key] as? KumlMetaValue.Integer)?.value?.toInt()

    // ── helpers ────────────────────────────────────────────────────────────

    private fun stereotypesLines(
        s: List<String>,
        indent: String,
    ): List<String> = s.map { "$indent stereotypes += ${quote(it)}" }

    private fun multiplicityString(m: Multiplicity): String {
        val upper = m.upper?.toString() ?: "*"
        return if (m.lower == m.upper) m.lower.toString() else "${m.lower}..$upper"
    }

    /**
     * Renders a `type = …` argument value for the string-or-[UmlTypeRef]
     * overloads (`attribute`, `parameter`). Uses the plain quoted-string form
     * (matching the existing, pre-this-wave output) when [UmlTypeRef.referencedId]
     * is `null`; otherwise falls back to the explicit `typeRef(name, referencedId
     * = …)` call so a classifier-typed attribute/parameter round-trips its
     * referenced-ID link instead of silently losing it.
     */
    private fun typeArg(t: UmlTypeRef): String {
        val refId = t.referencedId
        return if (refId != null) typeRefExpr(t) else quote(t.name)
    }

    /** Renders a `typeRef(...)` expression — used for `returnType = …` and [typeArg]. */
    private fun typeRefExpr(t: UmlTypeRef): String {
        val refId = t.referencedId
        return if (refId != null) {
            "typeRef(${quote(t.name)}, referencedId = ${quote(refId)})"
        } else {
            "typeRef(${quote(t.name)})"
        }
    }

    /**
     * Quotes [s] as a Kotlin string literal, escaping backslash, double-quote,
     * `$` (template interpolation), and the common whitespace control
     * characters so free text (constraint bodies, comment text — both
     * effectively adversarial user/CRDT input) round-trips byte-identical
     * through the compiler.
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
