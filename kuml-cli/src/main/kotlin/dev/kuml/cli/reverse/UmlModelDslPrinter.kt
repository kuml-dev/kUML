package dev.kuml.cli.reverse

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty

/**
 * Pretty-prints a [KumlModel] (rooted at a [KumlDiagram]) as a `*.kuml.kts`
 * source string.
 *
 * Idempotent in spirit to `kuml fmt` — round-tripping a model through the
 * printer and back through the DSL should produce an equivalent model.
 *
 * Format:
 * ```
 * classDiagram(name = "X") {
 *     enum(name = "Color") { literal("RED") }
 *     interface(name = "Greeter") { operation(name = "greet") }
 *     class(name = "Person", stereotypes = listOf("data")) {
 *         attribute(name = "name", type = "String")
 *         operation(name = "greet", returnType = "String")
 *     }
 *     generalization(specific = "Manager", general = "Person")
 *     realization(implementing = "Greeter", interface = "Person")
 *     association(source = "Person", target = "Address", targetMul = "0..*")
 * }
 * ```
 */
public object UmlModelDslPrinter {
    public fun print(model: KumlModel): String {
        val diagram =
            model.root as? KumlDiagram
                ?: return "// model root is not a KumlDiagram — cannot serialize\n"
        val sb = StringBuilder()
        sb.appendLine("classDiagram(name = ${quote(diagram.name)}) {")

        val elements = diagram.elements

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
            printGeneralization(sb, g, elements)
        }
        elements.filterIsInstance<UmlInterfaceRealization>().forEach { r ->
            printRealization(sb, r, elements)
        }
        elements.filterIsInstance<UmlAssociation>().forEach { a ->
            printAssociation(sb, a, elements)
        }

        sb.appendLine("}")
        return sb.toString()
    }

    // ── classifiers ────────────────────────────────────────────────────────

    private fun printEnum(
        sb: StringBuilder,
        e: UmlEnumeration,
    ) {
        sb.appendLine("    enum(name = ${quote(e.name)}) {")
        e.literals.forEach { lit -> sb.appendLine("        literal(${quote(lit.name)})") }
        sb.appendLine("    }")
    }

    private fun printInterface(
        sb: StringBuilder,
        i: UmlInterface,
    ) {
        val stereotypesArg = stereotypesArg(i.stereotypes)
        val header =
            if (stereotypesArg != null) {
                "    interface(name = ${quote(i.name)}, $stereotypesArg) {"
            } else {
                "    interface(name = ${quote(i.name)}) {"
            }
        sb.appendLine(header)
        i.attributes.forEach { printAttribute(sb, it, indent = "        ") }
        i.operations.forEach { printOperation(sb, it, indent = "        ") }
        sb.appendLine("    }")
    }

    private fun printClass(
        sb: StringBuilder,
        c: UmlClass,
    ) {
        val stereotypesArg = stereotypesArg(c.stereotypes)
        val abstractArg = if (c.isAbstract) "isAbstract = true" else null
        val args = listOfNotNull("name = ${quote(c.name)}", abstractArg, stereotypesArg)
        sb.appendLine("    class(${args.joinToString(", ")}) {")
        c.attributes.forEach { printAttribute(sb, it, indent = "        ") }
        c.operations.forEach { printOperation(sb, it, indent = "        ") }
        sb.appendLine("    }")
    }

    // ── features ───────────────────────────────────────────────────────────

    private fun printAttribute(
        sb: StringBuilder,
        p: UmlProperty,
        indent: String,
    ) {
        val args = mutableListOf("name = ${quote(p.name)}", "type = ${quote(p.type.name)}")
        if (p.multiplicity != Multiplicity()) {
            args += "multiplicity = ${quote(multiplicityString(p.multiplicity))}"
        }
        if (p.isReadOnly) args += "isReadOnly = true"
        stereotypesArg(p.stereotypes)?.let { args += it }
        sb.appendLine("$indent attribute(${args.joinToString(", ")})")
    }

    private fun printOperation(
        sb: StringBuilder,
        o: UmlOperation,
        indent: String,
    ) {
        val args = mutableListOf("name = ${quote(o.name)}")
        if (o.returnType != null) args += "returnType = ${quote(o.returnType!!.name)}"
        if (o.isAbstract) args += "isAbstract = true"
        stereotypesArg(o.stereotypes)?.let { args += it }
        sb.appendLine("$indent operation(${args.joinToString(", ")})")
    }

    // ── relationships ──────────────────────────────────────────────────────

    private fun printGeneralization(
        sb: StringBuilder,
        g: UmlGeneralization,
        elements: List<dev.kuml.core.model.KumlElement>,
    ) {
        val specific = nameForId(elements, g.specificId) ?: g.specificId
        val general = nameForId(elements, g.generalId) ?: g.generalId
        sb.appendLine("    generalization(specific = ${quote(specific)}, general = ${quote(general)})")
    }

    private fun printRealization(
        sb: StringBuilder,
        r: UmlInterfaceRealization,
        elements: List<dev.kuml.core.model.KumlElement>,
    ) {
        val implementing = nameForId(elements, r.implementingId) ?: r.implementingId
        val iface = nameForId(elements, r.interfaceId) ?: r.interfaceId
        sb.appendLine("    realization(implementing = ${quote(implementing)}, `interface` = ${quote(iface)})")
    }

    private fun printAssociation(
        sb: StringBuilder,
        a: UmlAssociation,
        elements: List<dev.kuml.core.model.KumlElement>,
    ) {
        if (a.ends.size < 2) return
        val source = nameForId(elements, a.ends[0].typeId) ?: a.ends[0].typeId
        val target = nameForId(elements, a.ends[1].typeId) ?: a.ends[1].typeId
        val args =
            mutableListOf(
                "source = ${quote(source)}",
                "target = ${quote(target)}",
            )
        val srcMul = a.ends[0].multiplicity
        val tgtMul = a.ends[1].multiplicity
        if (srcMul != Multiplicity()) args += "sourceMul = ${quote(multiplicityString(srcMul))}"
        if (tgtMul != Multiplicity()) args += "targetMul = ${quote(multiplicityString(tgtMul))}"
        sb.appendLine("    association(${args.joinToString(", ")})")
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun stereotypesArg(s: List<String>): String? =
        if (s.isEmpty()) null else "stereotypes = listOf(${s.joinToString(", ") { quote(it) }})"

    private fun multiplicityString(m: Multiplicity): String {
        val upper = m.upper?.toString() ?: "*"
        return if (m.lower == m.upper) m.lower.toString() else "${m.lower}..$upper"
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun nameForId(
        elements: List<dev.kuml.core.model.KumlElement>,
        id: String,
    ): String? {
        for (el in elements) {
            if (el is UmlClass && el.id == id) return el.name
            if (el is UmlInterface && el.id == id) return el.name
            if (el is UmlEnumeration && el.id == id) return el.name
        }
        return null
    }
}
