package dev.kuml.uml.dsl.print

import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty

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
 *         layout { col = 0; row = 0 }
 *     }
 *     generalization(specificId = "Manager", generalId = "Person")
 *     realization(implementingId = "Greeter", interfaceId = "Person")
 *     association(sourceId = "Person", targetId = "Address") {
 *         target { multiplicity("0..*") }
 *     }
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
        elements.filterIsInstance<UmlGeneralization>().forEach { g ->
            printGeneralization(sb, g)
        }
        elements.filterIsInstance<UmlInterfaceRealization>().forEach { r ->
            printRealization(sb, r)
        }
        elements.filterIsInstance<UmlAssociation>().forEach { a ->
            printAssociation(sb, a)
        }

        sb.appendLine("}")
        return sb.toString()
    }

    // ── classifiers ────────────────────────────────────────────────────────

    private fun printEnum(
        sb: StringBuilder,
        e: UmlEnumeration,
    ) {
        sb.appendLine("    enumOf(name = ${quote(e.name)}, id = ${quote(e.id)}) {")
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
        stereotypesLines(i.stereotypes, "        ").forEach(sb::appendLine)
        i.attributes.forEach { printAttribute(sb, it, indent = "        ") }
        i.operations.forEach { printOperation(sb, it, indent = "        ") }
        printLayoutHints(sb, i, indent = "        ")
        sb.appendLine("    }")
    }

    private fun printClass(
        sb: StringBuilder,
        c: UmlClass,
    ) {
        sb.appendLine("    classOf(name = ${quote(c.name)}, id = ${quote(c.id)}) {")
        if (c.isAbstract) sb.appendLine("        isAbstract = true")
        stereotypesLines(c.stereotypes, "        ").forEach(sb::appendLine)
        c.attributes.forEach { printAttribute(sb, it, indent = "        ") }
        c.operations.forEach { printOperation(sb, it, indent = "        ") }
        printLayoutHints(sb, c, indent = "        ")
        sb.appendLine("    }")
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
        if (p.stereotypes.isEmpty()) {
            // Flat, single-expression form — valid because multiplicity/isReadOnly
            // are named parameters on the no-block `attribute(...)` overload.
            val args = mutableListOf("name = ${quote(p.name)}", "type = ${quote(p.type.name)}")
            multExpr?.let { args += "multiplicity = $it" }
            if (p.isReadOnly) args += "isReadOnly = true"
            sb.appendLine("$indent attribute(${args.joinToString(", ")})")
        } else {
            // Block form — required because `stereotypes` is only settable inside
            // the `AttributeBuilder` body, not as a named constructor argument.
            sb.appendLine("$indent attribute(name = ${quote(p.name)}, type = ${quote(p.type.name)}) {")
            multExpr?.let { sb.appendLine("$indent    multiplicity = $it") }
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
        val hasBody = o.returnType != null || o.isAbstract || o.stereotypes.isNotEmpty()
        if (!hasBody) {
            sb.appendLine("$indent operation(name = ${quote(o.name)})")
        } else {
            sb.appendLine("$indent operation(name = ${quote(o.name)}) {")
            o.returnType?.let { sb.appendLine("$indent    returnType = typeRef(${quote(it.name)})") }
            if (o.isAbstract) sb.appendLine("$indent    isAbstract = true")
            o.stereotypes.forEach { sb.appendLine("$indent    stereotypes += ${quote(it)}") }
            sb.appendLine("$indent }")
        }
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

    private fun printAssociation(
        sb: StringBuilder,
        a: UmlAssociation,
    ) {
        if (a.ends.size < 2) return
        val srcId = a.ends[0].typeId
        val tgtId = a.ends[1].typeId
        val srcMul = a.ends[0].multiplicity
        val tgtMul = a.ends[1].multiplicity
        val hasSrcBlock = srcMul != Multiplicity()
        val hasTgtBlock = tgtMul != Multiplicity()
        if (!hasSrcBlock && !hasTgtBlock) {
            sb.appendLine("    association(sourceId = ${quote(srcId)}, targetId = ${quote(tgtId)})")
        } else {
            sb.appendLine("    association(sourceId = ${quote(srcId)}, targetId = ${quote(tgtId)}) {")
            if (hasSrcBlock) sb.appendLine("        source { multiplicity(${quote(multiplicityString(srcMul))}) }")
            if (hasTgtBlock) sb.appendLine("        target { multiplicity(${quote(multiplicityString(tgtMul))}) }")
            sb.appendLine("    }")
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
    private fun hasAnyLayoutHints(elements: List<dev.kuml.core.model.KumlElement>): Boolean =
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

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
